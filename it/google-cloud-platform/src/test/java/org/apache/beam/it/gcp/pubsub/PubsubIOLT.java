/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.it.gcp.pubsub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.apache.beam.it.common.PipelineLauncher;
import org.apache.beam.it.common.PipelineOperator;
import org.apache.beam.it.common.TestProperties;
import org.apache.beam.it.common.utils.ResourceManagerUtils;
import org.apache.beam.it.gcp.IOLoadTestBase;
import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.sdk.extensions.protobuf.Proto3SchemaMessages.Primitive;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubOptions;
import org.apache.beam.sdk.io.synthetic.SyntheticSourceOptions;
import org.apache.beam.sdk.io.synthetic.SyntheticUnboundedSource;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestPipelineOptions;
import org.apache.beam.sdk.testutils.publishing.InfluxDBSettings;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.MoreObjects;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Strings;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * PubsubIO load test.
 *
 * <p>Usage: <br>
 * - To run medium-scale load tests: {@code gradle :it:google-cloud-platform:PubsubLoadTestMedium}
 * <br>
 * - To run large-scale load tests: {@code gradle :it:google-cloud-platform:PubsubLoadTestLarge}
 */
public class PubsubIOLT extends IOLoadTestBase {

  private static final double TOLERANCE_FRACTION = 0.005;
  private static final int NUMBER_OF_BUNDLES_FOR_LOCAL = 10;
  private static final int NUMBER_OF_BUNDLES_FOR_MEDIUM_AND_LARGE = 20;
  private static final String READ_ELEMENT_METRIC_NAME = "read_count";
  private static final String MAP_RECORDS_STEP_NAME = "Map records";
  private static final String WRITE_TO_PUBSUB_STEP_NAME = "Write to PubSub";
  private static final Map<String, Configuration> TEST_CONFIGS_PRESET;
  private static TopicName topicName;
  private static String testConfigName;
  private static Configuration configuration;
  private static SubscriptionName subscription;
  private static InfluxDBSettings influxDBSettings;
  private static PubsubResourceManager resourceManager;

  @Rule public transient TestPipeline writePipeline = TestPipeline.create();
  @Rule public transient TestPipeline readPipeline = TestPipeline.create();

  static {
    try {
      TEST_CONFIGS_PRESET =
          ImmutableMap.of(
              "local",
              Configuration.fromJsonString(
                  "{\"numRecords\":200,\"valueSizeBytes\":1000,\"pipelineTimeout\":7,\"runner\":\"DirectRunner\",\"numWorkers\":1}",
                  Configuration.class), // 0.2 MB
              "medium",
              Configuration.fromJsonString(
                  "{\"numRecords\":10000000,\"valueSizeBytes\":1000,\"pipelineTimeout\":20,\"runner\":\"DataflowRunner\",\"numWorkers\":10}",
                  Configuration.class), // 10 GB
              "large",
              Configuration.fromJsonString(
                  "{\"numRecords\":100000000,\"valueSizeBytes\":1000,\"pipelineTimeout\":50,\"runner\":\"DataflowRunner\",\"numWorkers\":20}",
                  Configuration.class) // 100 GB
              );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Before
  public void setup() throws IOException {
    resourceManager =
        PubsubResourceManager.builder("io-pubsub-lt", project, CREDENTIALS_PROVIDER).build();
    topicName = resourceManager.createTopic("topic");
    subscription = resourceManager.createSubscription(topicName, "subscription");
    PipelineOptionsFactory.register(TestPipelineOptions.class);

    // parse configuration
    testConfigName =
        TestProperties.getProperty("configuration", "local", TestProperties.Type.PROPERTY);
    configuration = TEST_CONFIGS_PRESET.get(testConfigName);
    if (configuration == null) {
      try {
        configuration = Configuration.fromJsonString(testConfigName, Configuration.class);
      } catch (IOException e) {
        throw new IllegalArgumentException(
            String.format(
                "Unknown test configuration: [%s]. Pass to a valid configuration json, or use"
                    + " config presets: %s",
                testConfigName, TEST_CONFIGS_PRESET.keySet()));
      }
    }

    // Explicitly set up number of bundles in SyntheticUnboundedSource since it has a bug in
    // implementation where
    // number of lost data in streaming pipeline equals to number of initial bundles.
    configuration.forceNumInitialBundles =
        testConfigName.equals("local")
            ? NUMBER_OF_BUNDLES_FOR_LOCAL
            : NUMBER_OF_BUNDLES_FOR_MEDIUM_AND_LARGE;

    // tempLocation needs to be set for DataflowRunner
    if (!Strings.isNullOrEmpty(tempBucketName)) {
      String tempLocation = String.format("gs://%s/temp/", tempBucketName);
      writePipeline.getOptions().as(TestPipelineOptions.class).setTempRoot(tempLocation);
      writePipeline.getOptions().setTempLocation(tempLocation);
      readPipeline.getOptions().as(TestPipelineOptions.class).setTempRoot(tempLocation);
      readPipeline.getOptions().setTempLocation(tempLocation);
    }
    writePipeline.getOptions().as(PubsubOptions.class).setProject(project);
    readPipeline.getOptions().as(PubsubOptions.class).setProject(project);
    writePipeline.getOptions().as(DirectOptions.class).setBlockOnRun(false);
    readPipeline.getOptions().as(DirectOptions.class).setBlockOnRun(false);

    if (configuration.exportMetricsToInfluxDB) {
      configuration.influxHost =
          TestProperties.getProperty("influxHost", "", TestProperties.Type.PROPERTY);
      configuration.influxDatabase =
          TestProperties.getProperty("influxDatabase", "", TestProperties.Type.PROPERTY);
      configuration.influxMeasurement =
          TestProperties.getProperty("influxMeasurement", "", TestProperties.Type.PROPERTY);
    }
  }

  @After
  public void tearDownClass() {
    ResourceManagerUtils.cleanResources(resourceManager);
  }

  @Test
  public void testStringWriteAndRead() throws IOException {
    configuration.writeAndReadFormat = WriteAndReadFormat.STRING.toString();
    testWriteAndRead();
  }

  @Test
  public void testAvroGenericClassWriteAndRead() throws IOException {
    configuration.writeAndReadFormat = WriteAndReadFormat.AVRO.toString();
    testWriteAndRead();
  }

  @Test
  public void testProtoPrimitiveWriteAndRead() throws IOException {
    configuration.writeAndReadFormat = WriteAndReadFormat.PROTO.toString();
    testWriteAndRead();
  }

  @Test
  public void testPubsubMessageWriteAndRead() throws IOException {
    configuration.writeAndReadFormat = WriteAndReadFormat.PUBSUB_MESSAGE.toString();
    testWriteAndRead();
  }

  public void testWriteAndRead() throws IOException {
    if (configuration.exportMetricsToInfluxDB) {
      influxDBSettings =
          InfluxDBSettings.builder()
              .withHost(configuration.influxHost)
              .withDatabase(configuration.influxDatabase)
              .withMeasurement(
                  configuration.influxMeasurement
                      + "_"
                      + testConfigName
                      + "_"
                      + configuration.writeAndReadFormat)
              .get();
    }

    WriteAndReadFormat format = WriteAndReadFormat.valueOf(configuration.writeAndReadFormat);
    PipelineLauncher.LaunchInfo writeLaunchInfo = testWrite(format);
    PipelineLauncher.LaunchInfo readLaunchInfo = testRead(format);
    PipelineOperator.Result readResult =
        pipelineOperator.waitUntilDone(
            createConfig(readLaunchInfo, Duration.ofMinutes(configuration.pipelineTimeout)));

    try {
      // Check the initial launch didn't fail
      assertNotEquals(PipelineOperator.Result.LAUNCH_FAILED, readResult);
      // streaming read pipeline does not end itself
      // Fail the test if read pipeline (streaming) not in running state.
      assertEquals(
          PipelineLauncher.JobState.RUNNING,
          pipelineLauncher.getJobStatus(project, region, readLaunchInfo.jobId()));
    } finally {
      cancelJobIfRunning(writeLaunchInfo);
      cancelJobIfRunning(readLaunchInfo);
    }

    // check metrics
    double numRecords =
        pipelineLauncher.getMetric(
            project,
            region,
            readLaunchInfo.jobId(),
            getBeamMetricsName(PipelineMetricsType.COUNTER, READ_ELEMENT_METRIC_NAME));

    // Assert that actual data is within tolerance of expected data number since there might be
    // duplicates when testing big amount of data
    long expectedDataNum = configuration.numRecords - configuration.forceNumInitialBundles;
    long allowedTolerance = (long) (configuration.numRecords * TOLERANCE_FRACTION);
    double delta = Math.abs(numRecords - expectedDataNum);
    assertTrue(delta <= allowedTolerance);

    // export metrics
    MetricsConfiguration writeMetricsConfig =
        MetricsConfiguration.builder()
            .setInputPCollection("Map records.out0")
            .setInputPCollectionV2("Map records/ParMultiDo(MapKVToV).out0")
            .build();

    MetricsConfiguration readMetricsConfig =
        MetricsConfiguration.builder()
            .setOutputPCollection("Counting element.out0")
            .setOutputPCollectionV2("Counting element/ParMultiDo(Counting).out0")
            .build();

    exportMetrics(
        writeLaunchInfo,
        writeMetricsConfig,
        configuration.exportMetricsToInfluxDB,
        influxDBSettings);
    exportMetrics(
        readLaunchInfo, readMetricsConfig, configuration.exportMetricsToInfluxDB, influxDBSettings);
  }

  private PipelineLauncher.LaunchInfo testWrite(WriteAndReadFormat format) throws IOException {
    PCollection<KV<byte[], byte[]>> dataFromSource =
        writePipeline.apply(
            "Read from source", Read.from(new SyntheticUnboundedSource(configuration)));

    switch (format) {
      case STRING:
        dataFromSource
            .apply(MAP_RECORDS_STEP_NAME, ParDo.of(new MapKVtoString()))
            .apply(WRITE_TO_PUBSUB_STEP_NAME, PubsubIO.writeStrings().to(topicName.toString()));
        break;
      case AVRO:
        dataFromSource
            .apply(MAP_RECORDS_STEP_NAME, ParDo.of(new MapKVtoGenericClass()))
            .apply(
                WRITE_TO_PUBSUB_STEP_NAME,
                PubsubIO.writeAvros(GenericClass.class).to(topicName.toString()));
        break;
      case PROTO:
        dataFromSource
            .apply(MAP_RECORDS_STEP_NAME, ParDo.of(new MapKVtoPrimitiveProto()))
            .apply(
                WRITE_TO_PUBSUB_STEP_NAME,
                PubsubIO.writeProtos(Primitive.class).to(topicName.toString()));
        break;
      case PUBSUB_MESSAGE:
        dataFromSource
            .apply(MAP_RECORDS_STEP_NAME, ParDo.of(new MapKVtoPubSubMessage()))
            .apply(WRITE_TO_PUBSUB_STEP_NAME, PubsubIO.writeMessages().to(topicName.toString()));
        break;
    }

    PipelineLauncher.LaunchConfig writeOptions =
        PipelineLauncher.LaunchConfig.builder("write-pubsub")
            .setSdk(PipelineLauncher.Sdk.JAVA)
            .setPipeline(writePipeline)
            .addParameter("runner", configuration.runner)
            .addParameter("streaming", "true")
            .addParameter("numWorkers", String.valueOf(configuration.numWorkers))
            .addParameter("experiments", "use_runner_v2")
            .build();

    return pipelineLauncher.launch(project, region, writeOptions);
  }

  private PipelineLauncher.LaunchInfo testRead(WriteAndReadFormat format) throws IOException {
    PubsubIO.Read<?> read = null;

    switch (format) {
      case STRING:
        read = PubsubIO.readStrings().fromSubscription(subscription.toString());
        break;
      case AVRO:
        read = PubsubIO.readAvros(GenericClass.class).fromSubscription(subscription.toString());
        break;
      case PROTO:
        read = PubsubIO.readProtos(Primitive.class).fromSubscription(subscription.toString());
        break;
      case PUBSUB_MESSAGE:
        read = PubsubIO.readMessages().fromSubscription(subscription.toString());
        break;
    }

    readPipeline
        .apply("Read from PubSub", read)
        .apply("Counting element", ParDo.of(new CountingFn<>(READ_ELEMENT_METRIC_NAME)));

    PipelineLauncher.LaunchConfig readOptions =
        PipelineLauncher.LaunchConfig.builder("read-pubsub")
            .setSdk(PipelineLauncher.Sdk.JAVA)
            .setPipeline(readPipeline)
            .addParameter("runner", configuration.runner)
            .addParameter("streaming", "true")
            .addParameter("numWorkers", String.valueOf(configuration.numWorkers))
            .addParameter("experiments", "use_runner_v2")
            .build();

    return pipelineLauncher.launch(project, region, readOptions);
  }

  private void cancelJobIfRunning(PipelineLauncher.LaunchInfo pipelineLaunchInfo)
      throws IOException {
    if (pipelineLauncher.getJobStatus(project, region, pipelineLaunchInfo.jobId())
        == PipelineLauncher.JobState.RUNNING) {
      pipelineLauncher.cancelJob(project, region, pipelineLaunchInfo.jobId());
    }
  }

  /** Mapper class to convert data from KV<byte[], byte[]> to String. */
  private static class MapKVtoString extends DoFn<KV<byte[], byte[]>, String> {
    @ProcessElement
    public void process(ProcessContext context) {
      byte[] byteValue = Objects.requireNonNull(context.element()).getValue();
      context.output(ByteString.copyFrom(byteValue).toString(StandardCharsets.UTF_8));
    }
  }

  /** Mapper class to convert data from KV<byte[], byte[]> to GenericClass. */
  private static class MapKVtoGenericClass extends DoFn<KV<byte[], byte[]>, GenericClass> {
    @ProcessElement
    public void process(ProcessContext context) {
      byte[] byteValue = Objects.requireNonNull(context.element()).getValue();
      GenericClass pojo = new GenericClass(byteValue);
      context.output(pojo);
    }
  }

  /** Mapper class to convert data from KV<byte[], byte[]> to Proto Primitive. */
  private static class MapKVtoPrimitiveProto extends DoFn<KV<byte[], byte[]>, Primitive> {
    @ProcessElement
    public void process(ProcessContext context) {
      byte[] byteValue = Objects.requireNonNull(context.element()).getValue();
      Primitive proto =
          Primitive.newBuilder()
              .setPrimitiveBytes(ByteString.copyFrom(byteValue))
              .setPrimitiveInt32(ByteBuffer.wrap(byteValue).getInt())
              .build();
      context.output(proto);
    }
  }

  /** Mapper class to convert data from KV<byte[], byte[]> to PubsubMessage. */
  private static class MapKVtoPubSubMessage extends DoFn<KV<byte[], byte[]>, PubsubMessage> {
    @ProcessElement
    public void process(ProcessContext context) {
      byte[] byteValue = Objects.requireNonNull(context.element()).getValue();
      PubsubMessage pubsubMessage = new PubsubMessage(byteValue, Collections.emptyMap());
      context.output(pubsubMessage);
    }
  }

  /** Example of Generic class to test PubsubIO.writeAvros() / readAvros() methods. */
  static class GenericClass implements Serializable {
    byte[] byteField;

    public GenericClass() {}

    public GenericClass(byte[] byteField) {
      this.byteField = byteField;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass()).add("byteField", byteField).toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(byteField));
    }

    @Override
    public boolean equals(@Nullable Object other) {
      if (other == null || !(other instanceof GenericClass)) {
        return false;
      }
      GenericClass o = (GenericClass) other;
      return Arrays.equals(byteField, o.byteField);
    }
  }

  private enum WriteAndReadFormat {
    STRING,
    AVRO,
    PROTO,
    PUBSUB_MESSAGE
  }

  /** Options for Pubsub IO load test. */
  static class Configuration extends SyntheticSourceOptions {
    /** Pipeline timeout in minutes. Must be a positive value. */
    @JsonProperty public int pipelineTimeout = 20;

    /** Runner specified to run the pipeline. */
    @JsonProperty public String runner = "DirectRunner";

    /** PubSub write and read format: STRING/AVRO/PROTO/PUBSUB_MESSAGE. */
    @JsonProperty public String writeAndReadFormat = "STRING";

    /** Number of workers for the pipeline. */
    @JsonProperty public int numWorkers = 20;

    /**
     * Determines the destination for exporting metrics. If set to true, metrics will be exported to
     * InfluxDB and displayed using Grafana. If set to false, metrics will be exported to BigQuery
     * and displayed with Looker Studio.
     */
    @JsonProperty public boolean exportMetricsToInfluxDB = true;

    /** InfluxDB measurement to publish results to. * */
    @JsonProperty public String influxMeasurement;

    /** InfluxDB host to publish metrics. * */
    @JsonProperty public String influxHost;

    /** InfluxDB database to publish metrics. * */
    @JsonProperty public String influxDatabase;
  }
}
