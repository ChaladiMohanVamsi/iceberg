/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.MANIFEST_TARGET_SIZE_BYTES;
import static org.apache.iceberg.TableProperties.MANIFEST_TARGET_SIZE_BYTES_DEFAULT;
import static org.apache.iceberg.TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED;
import static org.apache.iceberg.TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED_DEFAULT;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.io.IOException;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.encryption.EncryptingFileIO;
import org.apache.iceberg.events.CreateSnapshotEvent;
import org.apache.iceberg.events.Listeners;
import org.apache.iceberg.exceptions.CleanableFailure;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.metrics.CommitMetrics;
import org.apache.iceberg.metrics.CommitMetricsResult;
import org.apache.iceberg.metrics.DefaultMetricsContext;
import org.apache.iceberg.metrics.ImmutableCommitReport;
import org.apache.iceberg.metrics.LoggingMetricsReporter;
import org.apache.iceberg.metrics.MetricsReporter;
import org.apache.iceberg.metrics.Timer.Timed;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.relocated.com.google.common.math.IntMath;
import org.apache.iceberg.util.Exceptions;
import org.apache.iceberg.util.Pair;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.SnapshotUtil;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps common functionality to create a new snapshot.
 *
 * <p>The number of attempted commits is controlled by {@link TableProperties#COMMIT_NUM_RETRIES}
 * and {@link TableProperties#COMMIT_NUM_RETRIES_DEFAULT} properties.
 */
@SuppressWarnings("UnnecessaryAnonymousClass")
abstract class SnapshotProducer<ThisT> implements SnapshotUpdate<ThisT> {
  private static final Logger LOG = LoggerFactory.getLogger(SnapshotProducer.class);
  static final int MIN_FILE_GROUP_SIZE = 10_000;
  static final Set<ManifestFile> EMPTY_SET = Sets.newHashSet();

  /** Default callback used to delete files. */
  private final Consumer<String> defaultDelete =
      new Consumer<String>() {
        @Override
        public void accept(String file) {
          ops.io().deleteFile(file);
        }
      };

  /** Cache used to enrich ManifestFile instances that are written to a ManifestListWriter. */
  private final LoadingCache<ManifestFile, ManifestFile> manifestsWithMetadata;

  private final TableOperations ops;
  private final boolean strictCleanup;
  private final boolean canInheritSnapshotId;
  private final String commitUUID = UUID.randomUUID().toString();
  private final AtomicInteger manifestCount = new AtomicInteger(0);
  private final AtomicInteger attempt = new AtomicInteger(0);
  private final List<String> manifestLists = Lists.newArrayList();
  private final long targetManifestSizeBytes;
  private MetricsReporter reporter = LoggingMetricsReporter.instance();
  private volatile Long snapshotId = null;
  private TableMetadata base;
  private boolean stageOnly = false;
  private Consumer<String> deleteFunc = defaultDelete;

  private ExecutorService workerPool;
  private String targetBranch = SnapshotRef.MAIN_BRANCH;
  private CommitMetrics commitMetrics;

  protected SnapshotProducer(TableOperations ops) {
    this.ops = ops;
    this.strictCleanup = ops.requireStrictCleanup();
    this.base = ops.current();
    this.manifestsWithMetadata =
        Caffeine.newBuilder()
            .build(
                file -> {
                  if (file.snapshotId() != null) {
                    return file;
                  }
                  return addMetadata(ops, file);
                });
    this.targetManifestSizeBytes =
        ops.current()
            .propertyAsLong(MANIFEST_TARGET_SIZE_BYTES, MANIFEST_TARGET_SIZE_BYTES_DEFAULT);
    boolean snapshotIdInheritanceEnabled =
        ops.current()
            .propertyAsBoolean(
                SNAPSHOT_ID_INHERITANCE_ENABLED, SNAPSHOT_ID_INHERITANCE_ENABLED_DEFAULT);
    this.canInheritSnapshotId = ops.current().formatVersion() > 1 || snapshotIdInheritanceEnabled;
  }

  protected abstract ThisT self();

  @Override
  public ThisT stageOnly() {
    this.stageOnly = true;
    return self();
  }

  @Override
  public ThisT scanManifestsWith(ExecutorService executorService) {
    this.workerPool = executorService;
    return self();
  }

  protected TableOperations ops() {
    return ops;
  }

  protected CommitMetrics commitMetrics() {
    if (commitMetrics == null) {
      this.commitMetrics = CommitMetrics.of(new DefaultMetricsContext());
    }

    return commitMetrics;
  }

  protected ThisT reportWith(MetricsReporter newReporter) {
    this.reporter = newReporter;
    return self();
  }

  /**
   * A setter for the target branch on which snapshot producer operation should be performed
   *
   * @param branch to set as target branch
   */
  protected void targetBranch(String branch) {
    Preconditions.checkArgument(branch != null, "Invalid branch name: null");
    boolean refExists = base.ref(branch) != null;
    Preconditions.checkArgument(
        !refExists || base.ref(branch).isBranch(),
        "%s is a tag, not a branch. Tags cannot be targets for producing snapshots",
        branch);
    this.targetBranch = branch;
  }

  protected String targetBranch() {
    return targetBranch;
  }

  protected ExecutorService workerPool() {
    if (workerPool == null) {
      this.workerPool = ThreadPools.getWorkerPool();
    }

    return workerPool;
  }

  @Override
  public ThisT deleteWith(Consumer<String> deleteCallback) {
    Preconditions.checkArgument(
        this.deleteFunc == defaultDelete, "Cannot set delete callback more than once");
    this.deleteFunc = deleteCallback;
    return self();
  }

  /**
   * Clean up any uncommitted manifests that were created.
   *
   * <p>Manifests may not be committed if apply is called more because a commit conflict has
   * occurred. Implementations may keep around manifests because the same changes will be made by
   * both apply calls. This method instructs the implementation to clean up those manifests and
   * passes the paths of the manifests that were actually committed.
   *
   * @param committed a set of manifest paths that were actually committed
   */
  protected abstract void cleanUncommitted(Set<ManifestFile> committed);

  /**
   * A string that describes the action that produced the new snapshot.
   *
   * @return a string operation
   */
  protected abstract String operation();

  /**
   * Validate the current metadata.
   *
   * <p>Child operations can override this to add custom validation.
   *
   * @param currentMetadata current table metadata to validate
   * @param snapshot ending snapshot on the lineage which is being validated
   */
  protected void validate(TableMetadata currentMetadata, Snapshot snapshot) {}

  /**
   * Apply the update's changes to the given metadata and snapshot. Return the new manifest list.
   *
   * @param metadataToUpdate the base table metadata to apply changes to
   * @param snapshot snapshot to apply the changes to
   * @return a manifest list for the new snapshot.
   */
  protected abstract List<ManifestFile> apply(TableMetadata metadataToUpdate, Snapshot snapshot);

  @Override
  public Snapshot apply() {
    refresh();
    Snapshot parentSnapshot = SnapshotUtil.latestSnapshot(base, targetBranch);

    long sequenceNumber = base.nextSequenceNumber();
    Long parentSnapshotId = parentSnapshot == null ? null : parentSnapshot.snapshotId();

    validate(base, parentSnapshot);
    List<ManifestFile> manifests = apply(base, parentSnapshot);

    OutputFile manifestList = manifestListPath();

    ManifestListWriter writer =
        ManifestLists.write(
            ops.current().formatVersion(),
            manifestList,
            snapshotId(),
            parentSnapshotId,
            sequenceNumber,
            base.nextRowId());

    try (writer) {
      // keep track of the manifest lists created
      manifestLists.add(manifestList.location());

      ManifestFile[] manifestFiles = new ManifestFile[manifests.size()];

      Tasks.range(manifestFiles.length)
          .stopOnFailure()
          .throwFailureWhenFinished()
          .executeWith(workerPool())
          .run(index -> manifestFiles[index] = manifestsWithMetadata.get(manifests.get(index)));

      writer.addAll(Arrays.asList(manifestFiles));
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to write manifest list file");
    }

    Long nextRowId = null;
    Long assignedRows = null;
    if (base.formatVersion() >= 3) {
      nextRowId = base.nextRowId();
      assignedRows = writer.nextRowId() - base.nextRowId();
    }

    Map<String, String> summary = summary();
    String operation = operation();

    if (summary != null && DataOperations.REPLACE.equals(operation)) {
      long addedRecords =
          PropertyUtil.propertyAsLong(summary, SnapshotSummary.ADDED_RECORDS_PROP, 0L);
      long replacedRecords =
          PropertyUtil.propertyAsLong(summary, SnapshotSummary.DELETED_RECORDS_PROP, 0L);

      // added may be less than replaced when records are already deleted by delete files
      Preconditions.checkArgument(
          addedRecords <= replacedRecords,
          "Invalid REPLACE operation: %s added records > %s replaced records",
          addedRecords,
          replacedRecords);
    }

    return new BaseSnapshot(
        sequenceNumber,
        snapshotId(),
        parentSnapshotId,
        System.currentTimeMillis(),
        operation(),
        summary(base),
        base.currentSchemaId(),
        manifestList.location(),
        nextRowId,
        assignedRows,
        null);
  }

  protected abstract Map<String, String> summary();

  /** Returns the snapshot summary from the implementation and updates totals. */
  private Map<String, String> summary(TableMetadata previous) {
    Map<String, String> summary = summary();

    if (summary == null) {
      return ImmutableMap.of();
    }

    Map<String, String> previousSummary;
    SnapshotRef previousBranchHead = previous.ref(targetBranch);
    if (previousBranchHead != null) {
      if (previous.snapshot(previousBranchHead.snapshotId()).summary() != null) {
        previousSummary = previous.snapshot(previousBranchHead.snapshotId()).summary();
      } else {
        // previous snapshot had no summary, use an empty summary
        previousSummary = ImmutableMap.of();
      }
    } else {
      // if there was no previous snapshot, default the summary to start totals at 0
      ImmutableMap.Builder<String, String> summaryBuilder = ImmutableMap.builder();
      summaryBuilder
          .put(SnapshotSummary.TOTAL_RECORDS_PROP, "0")
          .put(SnapshotSummary.TOTAL_FILE_SIZE_PROP, "0")
          .put(SnapshotSummary.TOTAL_DATA_FILES_PROP, "0")
          .put(SnapshotSummary.TOTAL_DELETE_FILES_PROP, "0")
          .put(SnapshotSummary.TOTAL_POS_DELETES_PROP, "0")
          .put(SnapshotSummary.TOTAL_EQ_DELETES_PROP, "0");
      previousSummary = summaryBuilder.build();
    }

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    // copy all summary properties from the implementation
    builder.putAll(summary);

    updateTotal(
        builder,
        previousSummary,
        SnapshotSummary.TOTAL_RECORDS_PROP,
        summary,
        SnapshotSummary.ADDED_RECORDS_PROP,
        SnapshotSummary.DELETED_RECORDS_PROP);
    updateTotal(
        builder,
        previousSummary,
        SnapshotSummary.TOTAL_FILE_SIZE_PROP,
        summary,
        SnapshotSummary.ADDED_FILE_SIZE_PROP,
        SnapshotSummary.REMOVED_FILE_SIZE_PROP);
    updateTotal(
        builder,
        previousSummary,
        SnapshotSummary.TOTAL_DATA_FILES_PROP,
        summary,
        SnapshotSummary.ADDED_FILES_PROP,
        SnapshotSummary.DELETED_FILES_PROP);
    updateTotal(
        builder,
        previousSummary,
        SnapshotSummary.TOTAL_DELETE_FILES_PROP,
        summary,
        SnapshotSummary.ADDED_DELETE_FILES_PROP,
        SnapshotSummary.REMOVED_DELETE_FILES_PROP);
    updateTotal(
        builder,
        previousSummary,
        SnapshotSummary.TOTAL_POS_DELETES_PROP,
        summary,
        SnapshotSummary.ADDED_POS_DELETES_PROP,
        SnapshotSummary.REMOVED_POS_DELETES_PROP);
    updateTotal(
        builder,
        previousSummary,
        SnapshotSummary.TOTAL_EQ_DELETES_PROP,
        summary,
        SnapshotSummary.ADDED_EQ_DELETES_PROP,
        SnapshotSummary.REMOVED_EQ_DELETES_PROP);

    builder.putAll(EnvironmentContext.get());
    return builder.build();
  }

  protected TableMetadata current() {
    return base;
  }

  protected TableMetadata refresh() {
    this.base = ops.refresh();
    return base;
  }

  @Override
  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  public void commit() {
    // this is always set to the latest commit attempt's snapshot
    AtomicReference<Snapshot> stagedSnapshot = new AtomicReference<>();
    try (Timed ignore = commitMetrics().totalDuration().start()) {
      try {
        Tasks.foreach(ops)
            .retry(base.propertyAsInt(COMMIT_NUM_RETRIES, COMMIT_NUM_RETRIES_DEFAULT))
            .exponentialBackoff(
                base.propertyAsInt(COMMIT_MIN_RETRY_WAIT_MS, COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
                base.propertyAsInt(COMMIT_MAX_RETRY_WAIT_MS, COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
                base.propertyAsInt(COMMIT_TOTAL_RETRY_TIME_MS, COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
                2.0 /* exponential */)
            .onlyRetryOn(CommitFailedException.class)
            .countAttempts(commitMetrics().attempts())
            .run(
                taskOps -> {
                  Snapshot newSnapshot = apply();
                  stagedSnapshot.set(newSnapshot);
                  TableMetadata.Builder update = TableMetadata.buildFrom(base);
                  if (base.snapshot(newSnapshot.snapshotId()) != null) {
                    // this is a rollback operation
                    update.setBranchSnapshot(newSnapshot.snapshotId(), targetBranch);
                  } else if (stageOnly) {
                    update.addSnapshot(newSnapshot);
                  } else {
                    update.setBranchSnapshot(newSnapshot, targetBranch);
                  }

                  TableMetadata updated = update.build();
                  if (updated.changes().isEmpty()) {
                    // do not commit if the metadata has not changed. for example, this may happen
                    // when setting the current
                    // snapshot to an ID that is already current. note that this check uses
                    // identity.
                    return;
                  }

                  // if the table UUID is missing, add it here. the UUID will be re-created each
                  // time
                  // this operation retries
                  // to ensure that if a concurrent operation assigns the UUID, this operation will
                  // not fail.
                  taskOps.commit(base, updated.withUUID());
                });

      } catch (CommitStateUnknownException commitStateUnknownException) {
        throw commitStateUnknownException;
      } catch (RuntimeException e) {
        if (!strictCleanup || e instanceof CleanableFailure) {
          Exceptions.suppressAndThrow(e, this::cleanAll);
        }

        throw e;
      }

      // at this point, the commit must have succeeded so the stagedSnapshot is committed
      Snapshot committedSnapshot = stagedSnapshot.get();
      try {
        LOG.info(
            "Committed snapshot {} ({})",
            committedSnapshot.snapshotId(),
            getClass().getSimpleName());

        if (cleanupAfterCommit()) {
          cleanUncommitted(Sets.newHashSet(committedSnapshot.allManifests(ops.io())));
        }
        // also clean up unused manifest lists created by multiple attempts
        for (String manifestList : manifestLists) {
          if (!committedSnapshot.manifestListLocation().equals(manifestList)) {
            deleteFile(manifestList);
          }
        }
      } catch (Throwable e) {
        LOG.warn(
            "Failed to load committed table metadata or during cleanup, skipping further cleanup",
            e);
      }
    }

    try {
      notifyListeners();
    } catch (Throwable e) {
      LOG.warn("Failed to notify event listeners", e);
    }
  }

  private void notifyListeners() {
    try {
      Object event = updateEvent();
      if (event != null) {
        Listeners.notifyAll(event);

        if (event instanceof CreateSnapshotEvent) {
          CreateSnapshotEvent createSnapshotEvent = (CreateSnapshotEvent) event;

          reporter.report(
              ImmutableCommitReport.builder()
                  .tableName(createSnapshotEvent.tableName())
                  .snapshotId(createSnapshotEvent.snapshotId())
                  .operation(createSnapshotEvent.operation())
                  .sequenceNumber(createSnapshotEvent.sequenceNumber())
                  .metadata(EnvironmentContext.get())
                  .commitMetrics(
                      CommitMetricsResult.from(commitMetrics(), createSnapshotEvent.summary()))
                  .build());
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("Failed to notify listeners", e);
    }
  }

  protected void cleanAll() {
    for (String manifestList : manifestLists) {
      deleteFile(manifestList);
    }
    manifestLists.clear();
    cleanUncommitted(EMPTY_SET);
  }

  protected void deleteFile(String path) {
    deleteFunc.accept(path);
  }

  protected OutputFile manifestListPath() {
    return ops.io()
        .newOutputFile(
            ops.metadataFileLocation(
                FileFormat.AVRO.addExtension(
                    String.format(
                        Locale.ROOT,
                        "snap-%d-%d-%s",
                        snapshotId(),
                        attempt.incrementAndGet(),
                        commitUUID))));
  }

  protected EncryptedOutputFile newManifestOutputFile() {
    String manifestFileLocation =
        ops.metadataFileLocation(
            FileFormat.AVRO.addExtension(commitUUID + "-m" + manifestCount.getAndIncrement()));
    return EncryptingFileIO.combine(ops.io(), ops.encryption())
        .newEncryptingOutputFile(manifestFileLocation);
  }

  protected ManifestWriter<DataFile> newManifestWriter(PartitionSpec spec) {
    return ManifestFiles.write(
        ops.current().formatVersion(), spec, newManifestOutputFile(), snapshotId());
  }

  protected ManifestWriter<DeleteFile> newDeleteManifestWriter(PartitionSpec spec) {
    return ManifestFiles.writeDeleteManifest(
        ops.current().formatVersion(), spec, newManifestOutputFile(), snapshotId());
  }

  protected RollingManifestWriter<DataFile> newRollingManifestWriter(PartitionSpec spec) {
    return new RollingManifestWriter<>(() -> newManifestWriter(spec), targetManifestSizeBytes);
  }

  protected RollingManifestWriter<DeleteFile> newRollingDeleteManifestWriter(PartitionSpec spec) {
    return new RollingManifestWriter<>(
        () -> newDeleteManifestWriter(spec), targetManifestSizeBytes);
  }

  protected ManifestReader<DataFile> newManifestReader(ManifestFile manifest) {
    return ManifestFiles.read(manifest, ops.io(), ops.current().specsById());
  }

  protected ManifestReader<DeleteFile> newDeleteManifestReader(ManifestFile manifest) {
    return ManifestFiles.readDeleteManifest(manifest, ops.io(), ops.current().specsById());
  }

  protected long snapshotId() {
    if (snapshotId == null) {
      synchronized (this) {
        while (snapshotId == null || ops.current().snapshot(snapshotId) != null) {
          this.snapshotId = ops.newSnapshotId();
        }
      }
    }
    return snapshotId;
  }

  protected boolean canInheritSnapshotId() {
    return canInheritSnapshotId;
  }

  protected boolean cleanupAfterCommit() {
    return true;
  }

  protected List<ManifestFile> writeDataManifests(Collection<DataFile> files, PartitionSpec spec) {
    return writeDataManifests(files, null /* inherit data seq */, spec);
  }

  protected List<ManifestFile> writeDataManifests(
      Collection<DataFile> files, Long dataSeq, PartitionSpec spec) {
    return writeManifests(files, group -> writeDataFileGroup(group, dataSeq, spec));
  }

  private List<ManifestFile> writeDataFileGroup(
      Collection<DataFile> files, Long dataSeq, PartitionSpec spec) {
    RollingManifestWriter<DataFile> writer = newRollingManifestWriter(spec);

    try (RollingManifestWriter<DataFile> closableWriter = writer) {
      if (dataSeq != null) {
        files.forEach(file -> closableWriter.add(file, dataSeq));
      } else {
        files.forEach(closableWriter::add);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to write data manifests");
    }

    return writer.toManifestFiles();
  }

  protected List<ManifestFile> writeDeleteManifests(
      Collection<DeleteFile> files, PartitionSpec spec) {
    return writeManifests(files, group -> writeDeleteFileGroup(group, spec));
  }

  private List<ManifestFile> writeDeleteFileGroup(
      Collection<DeleteFile> files, PartitionSpec spec) {
    RollingManifestWriter<DeleteFile> writer = newRollingDeleteManifestWriter(spec);

    try (RollingManifestWriter<DeleteFile> closableWriter = writer) {
      for (DeleteFile file : files) {
        Preconditions.checkArgument(
            file instanceof Delegates.PendingDeleteFile,
            "Invalid delete file: must be PendingDeleteFile");
        if (file.dataSequenceNumber() != null) {
          closableWriter.add(file, file.dataSequenceNumber());
        } else {
          closableWriter.add(file);
        }
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to write delete manifests");
    }

    return writer.toManifestFiles();
  }

  private static <F> List<ManifestFile> writeManifests(
      Collection<F> files, Function<List<F>, List<ManifestFile>> writeFunc) {
    int parallelism = manifestWriterCount(ThreadPools.WORKER_THREAD_POOL_SIZE, files.size());
    List<List<F>> groups = divide(files, parallelism);

    // Create a new list pairing each group with its index
    List<Pair<Integer, List<F>>> groupsWithIndex = Lists.newArrayList();
    for (int i = 0; i < groups.size(); i++) {
      groupsWithIndex.add(Pair.of(i, groups.get(i)));
    }

    AtomicReferenceArray<List<ManifestFile>> results = new AtomicReferenceArray<>(groups.size());

    Tasks.foreach(groupsWithIndex)
        .stopOnFailure()
        .throwFailureWhenFinished()
        .executeWith(ThreadPools.getWorkerPool())
        .run(
            indexedGroup -> {
              int index = indexedGroup.first();
              List<F> group = indexedGroup.second();
              List<ManifestFile> groupResults = writeFunc.apply(group);
              results.set(index, groupResults);
            });

    // Collect results in order
    ImmutableList.Builder<ManifestFile> builder = ImmutableList.builder();
    for (int i = 0; i < results.length(); i++) {
      builder.addAll(results.get(i));
    }
    return builder.build();
  }

  private static <T> List<List<T>> divide(Collection<T> collection, int groupCount) {
    List<T> list = Lists.newArrayList(collection);
    int groupSize = IntMath.divide(list.size(), groupCount, RoundingMode.CEILING);
    return Lists.partition(list, groupSize);
  }

  /**
   * Calculates how many manifest writers can be used to concurrently to handle the given number of
   * files without creating too small manifests.
   *
   * @param workerPoolSize the size of the available worker pool
   * @param fileCount the total number of files to be processed
   * @return the number of manifest writers that can be used concurrently
   */
  @VisibleForTesting
  static int manifestWriterCount(int workerPoolSize, int fileCount) {
    int limit = IntMath.divide(fileCount, MIN_FILE_GROUP_SIZE, RoundingMode.HALF_UP);
    return Math.max(1, Math.min(workerPoolSize, limit));
  }

  private static ManifestFile addMetadata(TableOperations ops, ManifestFile manifest) {
    try (ManifestReader<DataFile> reader =
        ManifestFiles.read(manifest, ops.io(), ops.current().specsById())) {
      PartitionSummary stats = new PartitionSummary(ops.current().spec(manifest.partitionSpecId()));
      int addedFiles = 0;
      long addedRows = 0L;
      int existingFiles = 0;
      long existingRows = 0L;
      int deletedFiles = 0;
      long deletedRows = 0L;

      Long snapshotId = null;
      long maxSnapshotId = Long.MIN_VALUE;
      for (ManifestEntry<DataFile> entry : reader.entries()) {
        if (entry.snapshotId() > maxSnapshotId) {
          maxSnapshotId = entry.snapshotId();
        }

        switch (entry.status()) {
          case ADDED:
            addedFiles += 1;
            addedRows += entry.file().recordCount();
            if (snapshotId == null) {
              snapshotId = entry.snapshotId();
            }
            break;
          case EXISTING:
            existingFiles += 1;
            existingRows += entry.file().recordCount();
            break;
          case DELETED:
            deletedFiles += 1;
            deletedRows += entry.file().recordCount();
            if (snapshotId == null) {
              snapshotId = entry.snapshotId();
            }
            break;
        }

        stats.update(entry.file().partition());
      }

      if (snapshotId == null) {
        // if no files were added or deleted, use the largest snapshot ID in the manifest
        snapshotId = maxSnapshotId;
      }

      return new GenericManifestFile(
          manifest.path(),
          manifest.length(),
          manifest.partitionSpecId(),
          ManifestContent.DATA,
          manifest.sequenceNumber(),
          manifest.minSequenceNumber(),
          snapshotId,
          stats.summaries(),
          null,
          addedFiles,
          addedRows,
          existingFiles,
          existingRows,
          deletedFiles,
          deletedRows,
          null);

    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to read manifest: %s", manifest.path());
    }
  }

  private static void updateTotal(
      ImmutableMap.Builder<String, String> summaryBuilder,
      Map<String, String> previousSummary,
      String totalProperty,
      Map<String, String> currentSummary,
      String addedProperty,
      String deletedProperty) {
    String totalStr = previousSummary.get(totalProperty);
    if (totalStr != null) {
      try {
        long newTotal = Long.parseLong(totalStr);

        String addedStr = currentSummary.get(addedProperty);
        if (newTotal >= 0 && addedStr != null) {
          newTotal += Long.parseLong(addedStr);
        }

        String deletedStr = currentSummary.get(deletedProperty);
        if (newTotal >= 0 && deletedStr != null) {
          newTotal -= Long.parseLong(deletedStr);
        }

        if (newTotal >= 0) {
          summaryBuilder.put(totalProperty, String.valueOf(newTotal));
        }

      } catch (NumberFormatException e) {
        // ignore and do not add total
      }
    }
  }
}
