/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.sink.bootstrap;

import org.apache.hudi.client.FlinkTaskContextSupplier;
import org.apache.hudi.client.common.HoodieFlinkEngineContext;
import org.apache.hudi.common.config.SerializableConfiguration;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordGlobalLocation;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.log.HoodieMergedLogRecordScanner;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.util.BaseFileUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.sink.bootstrap.aggregate.BootstrapAggFunction;
import org.apache.hudi.table.HoodieFlinkTable;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.format.FormatUtils;
import org.apache.hudi.util.StreamerUtil;

import org.apache.avro.Schema;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.taskexecutor.GlobalAggregateManager;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.util.Collector;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toList;

/**
 * The function to load index from existing hoodieTable.
 *
 * <p>Each subtask of the function triggers the index bootstrap when the first element came in,
 * the record cannot be sent until all the index records have been sent.
 *
 * <p>The output records should then shuffle by the recordKey and thus do scalable write.
 */
public class BootstrapFunction<I, O extends HoodieRecord>
    extends ProcessFunction<I, O> {

  private static final Logger LOG = LoggerFactory.getLogger(BootstrapFunction.class);

  private HoodieTable<?, ?, ?, ?> hoodieTable;

  private final Configuration conf;

  private transient org.apache.hadoop.conf.Configuration hadoopConf;
  private transient HoodieWriteConfig writeConfig;

  private GlobalAggregateManager aggregateManager;

  private final Pattern pattern;
  private boolean alreadyBootstrap;

  public BootstrapFunction(Configuration conf) {
    this.conf = conf;
    this.pattern = Pattern.compile(conf.getString(FlinkOptions.INDEX_PARTITION_REGEX));
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    this.hadoopConf = StreamerUtil.getHadoopConf();
    this.writeConfig = StreamerUtil.getHoodieClientConfig(this.conf);
    this.hoodieTable = getTable();
    this.aggregateManager = ((StreamingRuntimeContext) getRuntimeContext()).getGlobalAggregateManager();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void processElement(I value, Context ctx, Collector<O> out) throws Exception {
    if (!alreadyBootstrap) {
      String basePath = hoodieTable.getMetaClient().getBasePath();
      int taskID = getRuntimeContext().getIndexOfThisSubtask();
      LOG.info("Start loading records in table {} into the index state, taskId = {}", basePath, taskID);
      for (String partitionPath : FSUtils.getAllFoldersWithPartitionMetaFile(FSUtils.getFs(basePath, hadoopConf), basePath)) {
        if (pattern.matcher(partitionPath).matches()) {
          loadRecords(partitionPath, out);
        }
      }

      // wait for others bootstrap task send bootstrap complete.
      waitForBootstrapReady(taskID);

      alreadyBootstrap = true;
      LOG.info("Finish sending index records, taskId = {}.", getRuntimeContext().getIndexOfThisSubtask());
    }

    // send the trigger record
    out.collect((O) value);
  }

  /**
   * Wait for other bootstrap tasks to finish the index bootstrap.
   */
  private void waitForBootstrapReady(int taskID) {
    int taskNum = getRuntimeContext().getNumberOfParallelSubtasks();
    int readyTaskNum = 1;
    while (taskNum != readyTaskNum) {
      try {
        readyTaskNum = aggregateManager.updateGlobalAggregate(BootstrapAggFunction.NAME, taskID, new BootstrapAggFunction());
        LOG.info("Waiting for other bootstrap tasks to complete, taskId = {}.", taskID);

        TimeUnit.SECONDS.sleep(5);
      } catch (Exception e) {
        LOG.warn("Update global task bootstrap summary error", e);
      }
    }
  }

  private HoodieFlinkTable getTable() {
    HoodieFlinkEngineContext context = new HoodieFlinkEngineContext(
        new SerializableConfiguration(this.hadoopConf),
        new FlinkTaskContextSupplier(getRuntimeContext()));
    return HoodieFlinkTable.create(this.writeConfig, context);
  }

  /**
   * Load all the indices of give partition path into the backup state.
   *
   * @param partitionPath The partition path
   */
  @SuppressWarnings("unchecked")
  private void loadRecords(String partitionPath, Collector<O> out) throws Exception {
    long start = System.currentTimeMillis();

    BaseFileUtils fileUtils = BaseFileUtils.getInstance(this.hoodieTable.getBaseFileFormat());
    Schema schema = new TableSchemaResolver(this.hoodieTable.getMetaClient()).getTableAvroSchema();

    final int parallelism = getRuntimeContext().getNumberOfParallelSubtasks();
    final int maxParallelism = getRuntimeContext().getMaxNumberOfParallelSubtasks();
    final int taskID = getRuntimeContext().getIndexOfThisSubtask();

    Option<HoodieInstant> latestCommitTime = this.hoodieTable.getMetaClient().getCommitsTimeline()
        .filterCompletedInstants().lastInstant();

    if (latestCommitTime.isPresent()) {
      List<FileSlice> fileSlices = this.hoodieTable.getSliceView()
          .getLatestFileSlicesBeforeOrOn(partitionPath, latestCommitTime.get().getTimestamp(), true)
          .collect(toList());

      for (FileSlice fileSlice : fileSlices) {
        if (!shouldLoadFile(fileSlice.getFileId(), maxParallelism, parallelism, taskID)) {
          continue;
        }
        LOG.info("Load records from {}.", fileSlice);

        // load parquet records
        fileSlice.getBaseFile().ifPresent(baseFile -> {
          // filter out crushed files
          if (baseFile.getFileSize() <= 0) {
            return;
          }

          final List<HoodieKey> hoodieKeys;
          try {
            hoodieKeys =
                fileUtils.fetchRecordKeyPartitionPath(this.hadoopConf, new Path(baseFile.getPath()));
          } catch (Exception e) {
            throw new HoodieException(String.format("Error when loading record keys from file: %s", baseFile), e);
          }

          for (HoodieKey hoodieKey : hoodieKeys) {
            out.collect((O) new IndexRecord(generateHoodieRecord(hoodieKey, fileSlice)));
          }
        });

        // load avro log records
        List<String> logPaths = fileSlice.getLogFiles()
                // filter out crushed files
                .filter(logFile -> logFile.getFileSize() > 0)
                .map(logFile -> logFile.getPath().toString())
                .collect(toList());
        HoodieMergedLogRecordScanner scanner = FormatUtils.scanLog(logPaths, schema, latestCommitTime.get().getTimestamp(),
            writeConfig, hadoopConf);

        try {
          for (String recordKey : scanner.getRecords().keySet()) {
            out.collect((O) new IndexRecord(generateHoodieRecord(new HoodieKey(recordKey, partitionPath), fileSlice)));
          }
        } catch (Exception e) {
          throw new HoodieException(String.format("Error when loading record keys from files: %s", logPaths), e);
        } finally {
          scanner.close();
        }
      }
    }

    long cost = System.currentTimeMillis() - start;
    LOG.info("Task [{}}:{}}] finish loading the index under partition {} and sending them to downstream, time cost: {} milliseconds.",
        this.getClass().getSimpleName(), taskID, partitionPath, cost);
  }

  @SuppressWarnings("unchecked")
  public static HoodieRecord generateHoodieRecord(HoodieKey hoodieKey, FileSlice fileSlice) {
    HoodieRecord hoodieRecord = new HoodieRecord(hoodieKey, null);
    hoodieRecord.setCurrentLocation(new HoodieRecordGlobalLocation(hoodieKey.getPartitionPath(), fileSlice.getBaseInstantTime(), fileSlice.getFileId()));
    hoodieRecord.seal();

    return hoodieRecord;
  }

  private static boolean shouldLoadFile(String fileId,
                                        int maxParallelism,
                                        int parallelism,
                                        int taskID) {
    return KeyGroupRangeAssignment.assignKeyToParallelOperator(
        fileId, maxParallelism, parallelism) == taskID;
  }

  @VisibleForTesting
  public boolean isAlreadyBootstrap() {
    return alreadyBootstrap;
  }
}
