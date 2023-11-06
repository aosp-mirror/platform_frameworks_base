/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.tracing.perfetto;

import static java.io.File.createTempFile;
import static java.nio.file.Files.createTempDirectory;

import static perfetto.protos.PerfettoTrace.TestEvent.PAYLOAD;
import static perfetto.protos.PerfettoTrace.TestEvent.TestPayload.SINGLE_INT;
import static perfetto.protos.PerfettoTrace.TracePacket.FOR_TESTING;

import android.tools.common.ScenarioBuilder;
import android.tools.common.Tag;
import android.tools.common.io.TraceType;
import android.tools.device.traces.TraceConfig;
import android.tools.device.traces.TraceConfigs;
import android.tools.device.traces.io.ResultReader;
import android.tools.device.traces.io.ResultWriter;
import android.tools.device.traces.monitors.PerfettoTraceMonitor;
import android.tools.device.traces.monitors.TraceMonitor;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Truth;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import perfetto.protos.PerfettoConfig;
import perfetto.protos.PerfettoTrace;
import perfetto.protos.TracePacketOuterClass;

@RunWith(AndroidJUnit4.class)
public class DataSourceTest {
    private final File mTracingDirectory = createTempDirectory("temp").toFile();

    private final ResultWriter mWriter = new ResultWriter()
            .forScenario(new ScenarioBuilder()
                    .forClass(createTempFile("temp", "").getName()).build())
            .withOutputDir(mTracingDirectory)
            .setRunComplete();

    private final TraceConfigs mTraceConfig = new TraceConfigs(
            new TraceConfig(false, true, false),
            new TraceConfig(false, true, false),
            new TraceConfig(false, true, false),
            new TraceConfig(false, true, false)
    );

    private static TestDataSource sTestDataSource;

    private static TestDataSource.DataSourceInstanceProvider sInstanceProvider;
    private static TestDataSource.TlsStateProvider sTlsStateProvider;
    private static TestDataSource.IncrementalStateProvider sIncrementalStateProvider;

    public DataSourceTest() throws IOException {}

    @BeforeClass
    public static void beforeAll() {
        Producer.init(InitArguments.DEFAULTS);
        setupProviders();
        sTestDataSource = new TestDataSource(
                (ds, idx, configStream) -> sInstanceProvider.provide(ds, idx, configStream),
                args -> sTlsStateProvider.provide(args),
                args -> sIncrementalStateProvider.provide(args));
        sTestDataSource.register(DataSourceParams.DEFAULTS);
    }

    private static void setupProviders() {
        sInstanceProvider = (ds, idx, configStream) ->
                new TestDataSource.TestDataSourceInstance(ds, idx);
        sTlsStateProvider = args -> new TestDataSource.TestTlsState();
        sIncrementalStateProvider = args -> new TestDataSource.TestIncrementalState();
    }

    @Before
    public void setup() {
        setupProviders();
    }

    @Test
    public void canTraceData() throws InvalidProtocolBufferException {
        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();

            sTestDataSource.trace((ctx) -> {
                final ProtoOutputStream protoOutputStream = ctx.newTracePacket();
                long forTestingToken = protoOutputStream.start(FOR_TESTING);
                long payloadToken = protoOutputStream.start(PAYLOAD);
                protoOutputStream.write(SINGLE_INT, 10);
                protoOutputStream.end(payloadToken);
                protoOutputStream.end(forTestingToken);
            });
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final byte[] rawProtoFromFile = reader.readBytes(TraceType.PERFETTO, Tag.ALL);
        assert rawProtoFromFile != null;
        final perfetto.protos.TraceOuterClass.Trace trace = perfetto.protos.TraceOuterClass.Trace
                .parseFrom(rawProtoFromFile);

        Truth.assertThat(trace.getPacketCount()).isGreaterThan(0);
        final List<TracePacketOuterClass.TracePacket> tracePackets = trace.getPacketList()
                .stream().filter(TracePacketOuterClass.TracePacket::hasForTesting).toList();
        final List<TracePacketOuterClass.TracePacket> matchingPackets = tracePackets.stream()
                .filter(it -> it.getForTesting().getPayload().getSingleInt() == 10).toList();
        Truth.assertThat(matchingPackets).hasSize(1);
    }

    @Test
    public void canUseTlsStateForCustomState() {
        final int expectedStateTestValue = 10;
        final AtomicInteger actualStateTestValue = new AtomicInteger();

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();

            sTestDataSource.trace((ctx) -> {
                TestDataSource.TestTlsState state = ctx.getCustomTlsState();
                state.testStateValue = expectedStateTestValue;
            });

            sTestDataSource.trace((ctx) -> {
                TestDataSource.TestTlsState state = ctx.getCustomTlsState();
                actualStateTestValue.set(state.testStateValue);
            });
        } finally {
            traceMonitor.stop(mWriter);
        }

        Truth.assertThat(actualStateTestValue.get()).isEqualTo(expectedStateTestValue);
    }

    @Test
    public void eachInstanceHasOwnTlsState() {
        final int[] expectedStateTestValues = new int[] { 1, 2 };
        final int[] actualStateTestValues = new int[] { 0, 0 };

        final TraceMonitor traceMonitor1 = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();
        final TraceMonitor traceMonitor2 = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor1.start();
            try {
                traceMonitor2.start();

                AtomicInteger index = new AtomicInteger(0);
                sTestDataSource.trace((ctx) -> {
                    TestDataSource.TestTlsState state = ctx.getCustomTlsState();
                    state.testStateValue = expectedStateTestValues[index.getAndIncrement()];
                });

                index.set(0);
                sTestDataSource.trace((ctx) -> {
                    TestDataSource.TestTlsState state = ctx.getCustomTlsState();
                    actualStateTestValues[index.getAndIncrement()] = state.testStateValue;
                });
            } finally {
                traceMonitor1.stop(mWriter);
            }
        } finally {
            traceMonitor2.stop(mWriter);
        }

        Truth.assertThat(actualStateTestValues[0]).isEqualTo(expectedStateTestValues[0]);
        Truth.assertThat(actualStateTestValues[1]).isEqualTo(expectedStateTestValues[1]);
    }

    @Test
    public void eachThreadHasOwnTlsState() throws InterruptedException {
        final int thread1ExpectedStateValue = 1;
        final int thread2ExpectedStateValue = 2;

        final AtomicInteger thread1ActualStateValue = new AtomicInteger();
        final AtomicInteger thread2ActualStateValue = new AtomicInteger();

        final CountDownLatch setUpLatch = new CountDownLatch(2);
        final CountDownLatch setStateLatch = new CountDownLatch(2);
        final CountDownLatch setOutStateLatch = new CountDownLatch(2);

        final RunnableCreator createTask = (stateValue, stateOut) -> () -> {
            Producer.init(InitArguments.DEFAULTS);

            setUpLatch.countDown();

            try {
                setUpLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            sTestDataSource.trace((ctx) -> {
                TestDataSource.TestTlsState state = ctx.getCustomTlsState();
                state.testStateValue = stateValue;
                setStateLatch.countDown();
            });

            try {
                setStateLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            sTestDataSource.trace((ctx) -> {
                stateOut.set(ctx.getCustomTlsState().testStateValue);
                setOutStateLatch.countDown();
            });
        };

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();

            new Thread(
                    createTask.create(thread1ExpectedStateValue, thread1ActualStateValue)).start();
            new Thread(
                    createTask.create(thread2ExpectedStateValue, thread2ActualStateValue)).start();

            setOutStateLatch.await(3, TimeUnit.SECONDS);

        } finally {
            traceMonitor.stop(mWriter);
        }

        Truth.assertThat(thread1ActualStateValue.get()).isEqualTo(thread1ExpectedStateValue);
        Truth.assertThat(thread2ActualStateValue.get()).isEqualTo(thread2ExpectedStateValue);
    }

    @Test
    public void incrementalStateIsReset() throws InterruptedException {

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build())
                .setIncrementalTimeout(10)
                .build();

        final AtomicInteger testStateValue = new AtomicInteger();
        try {
            traceMonitor.start();

            sTestDataSource.trace(ctx -> ctx.getIncrementalState().testStateValue = 1);

            // Timeout to make sure the incremental state is cleared.
            Thread.sleep(1000);

            sTestDataSource.trace(ctx ->
                    testStateValue.set(ctx.getIncrementalState().testStateValue));
        } finally {
            traceMonitor.stop(mWriter);
        }

        Truth.assertThat(testStateValue.get()).isNotEqualTo(1);
    }

    @Test
    public void getInstanceConfigOnCreateInstance() throws IOException {
        final int expectedDummyIntValue = 10;
        AtomicReference<ProtoInputStream> configStream = new AtomicReference<>();
        sInstanceProvider = (ds, idx, config) -> {
            configStream.set(config);
            return new TestDataSource.TestDataSourceInstance(ds, idx);
        };

        final TraceMonitor monitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name)
                        .setForTesting(PerfettoConfig.TestConfig.newBuilder().setDummyFields(
                                PerfettoConfig.TestConfig.DummyFields.newBuilder()
                                        .setFieldInt32(expectedDummyIntValue)
                                        .build())
                                .build())
                        .build())
                .build();

        try {
            monitor.start();
        } finally {
            monitor.stop(mWriter);
        }

        int configDummyIntValue = 0;
        while (configStream.get().nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (configStream.get().getFieldNumber()
                    == (int) PerfettoTrace.DataSourceConfig.FOR_TESTING) {
                final long forTestingToken = configStream.get()
                        .start(PerfettoTrace.DataSourceConfig.FOR_TESTING);
                while (configStream.get().nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    if (configStream.get().getFieldNumber()
                            == (int) PerfettoTrace.TestConfig.DUMMY_FIELDS) {
                        final long dummyFieldsToken = configStream.get()
                                .start(PerfettoTrace.TestConfig.DUMMY_FIELDS);
                        while (configStream.get().nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                            if (configStream.get().getFieldNumber()
                                    == (int) PerfettoTrace.TestConfig.DummyFields.FIELD_INT32) {
                                int val = configStream.get().readInt(
                                        PerfettoTrace.TestConfig.DummyFields.FIELD_INT32);
                                if (val != 0) {
                                    configDummyIntValue = val;
                                    break;
                                }
                            }
                        }
                        configStream.get().end(dummyFieldsToken);
                        break;
                    }
                }
                configStream.get().end(forTestingToken);
                break;
            }
        }

        Truth.assertThat(configDummyIntValue).isEqualTo(expectedDummyIntValue);
    }

    @Test
    public void multipleTraceInstances() throws IOException, InterruptedException {
        final int instanceCount = 3;

        final List<TraceMonitor> monitors = new ArrayList<>();
        final List<ResultWriter> writers = new ArrayList<>();

        for (int i = 0; i < instanceCount; i++) {
            final ResultWriter writer = new ResultWriter()
                    .forScenario(new ScenarioBuilder()
                            .forClass(createTempFile("temp", "").getName()).build())
                    .withOutputDir(mTracingDirectory)
                    .setRunComplete();
            writers.add(writer);
        }

        // Start at 1 because 0 is considered null value so payload will be ignored in that case
        TestDataSource.TestTlsState.lastIndex = 1;

        final AtomicInteger traceCallCount = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(instanceCount);

        try {
            // Start instances
            for (int i = 0; i < instanceCount; i++) {
                final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                        .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                                .setName(sTestDataSource.name).build()).build();
                monitors.add(traceMonitor);
                traceMonitor.start();
            }

            // Trace the stateIndex of the tracing instance.
            sTestDataSource.trace(ctx -> {
                final int testIntValue = ctx.getCustomTlsState().stateIndex;
                traceCallCount.incrementAndGet();

                final ProtoOutputStream os = ctx.newTracePacket();
                long forTestingToken = os.start(FOR_TESTING);
                long payloadToken = os.start(PAYLOAD);
                os.write(SINGLE_INT, testIntValue);
                os.end(payloadToken);
                os.end(forTestingToken);

                latch.countDown();
            });
        } finally {
            // Stop instances
            for (int i = 0; i < instanceCount; i++) {
                final TraceMonitor monitor = monitors.get(i);
                final ResultWriter writer = writers.get(i);
                monitor.stop(writer);
            }
        }

        latch.await(3, TimeUnit.SECONDS);
        Truth.assertThat(traceCallCount.get()).isEqualTo(instanceCount);

        for (int i = 0; i < instanceCount; i++) {
            final int expectedTracedValue = i + 1;
            final ResultWriter writer = writers.get(i);
            final ResultReader reader = new ResultReader(writer.write(), mTraceConfig);
            final byte[] rawProtoFromFile = reader.readBytes(TraceType.PERFETTO, Tag.ALL);
            assert rawProtoFromFile != null;
            final perfetto.protos.TraceOuterClass.Trace trace =
                    perfetto.protos.TraceOuterClass.Trace.parseFrom(rawProtoFromFile);

            Truth.assertThat(trace.getPacketCount()).isGreaterThan(0);
            final List<TracePacketOuterClass.TracePacket> tracePackets = trace.getPacketList()
                    .stream().filter(TracePacketOuterClass.TracePacket::hasForTesting).toList();
            Truth.assertWithMessage("One packet has for testing data")
                    .that(tracePackets).hasSize(1);

            final List<TracePacketOuterClass.TracePacket> matchingPackets =
                    tracePackets.stream()
                            .filter(it -> it.getForTesting().getPayload()
                                    .getSingleInt() == expectedTracedValue).toList();
            Truth.assertWithMessage(
                            "One packet has testing data with a payload with the expected value")
                    .that(matchingPackets).hasSize(1);
        }
    }

    @Test
    public void onStartCallbackTriggered() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        final AtomicBoolean callbackCalled = new AtomicBoolean(false);
        sInstanceProvider = (ds, idx, config) -> new TestDataSource.TestDataSourceInstance(
                        ds,
                        idx,
                        (args) -> {
                            callbackCalled.set(true);
                            latch.countDown();
                        },
                        (args) -> {},
                        (args) -> {}
        );

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        Truth.assertThat(callbackCalled.get()).isFalse();
        try {
            traceMonitor.start();
            latch.await(3, TimeUnit.SECONDS);
            Truth.assertThat(callbackCalled.get()).isTrue();
        } finally {
            traceMonitor.stop(mWriter);
        }
    }

    @Test
    public void onFlushCallbackTriggered() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean callbackCalled = new AtomicBoolean(false);
        sInstanceProvider = (ds, idx, config) ->
                new TestDataSource.TestDataSourceInstance(
                        ds,
                        idx,
                        (args) -> {},
                        (args) -> {
                            callbackCalled.set(true);
                            latch.countDown();
                        },
                        (args) -> {}
                );

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();
            Truth.assertThat(callbackCalled.get()).isFalse();
            sTestDataSource.trace((ctx) -> {
                final ProtoOutputStream protoOutputStream = ctx.newTracePacket();
                long forTestingToken = protoOutputStream.start(FOR_TESTING);
                long payloadToken = protoOutputStream.start(PAYLOAD);
                protoOutputStream.write(SINGLE_INT, 10);
                protoOutputStream.end(payloadToken);
                protoOutputStream.end(forTestingToken);
            });
        } finally {
            traceMonitor.stop(mWriter);
        }

        latch.await(3, TimeUnit.SECONDS);
        Truth.assertThat(callbackCalled.get()).isTrue();
    }

    @Test
    public void onStopCallbackTriggered() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean callbackCalled = new AtomicBoolean(false);
        sInstanceProvider = (ds, idx, config) ->
                new TestDataSource.TestDataSourceInstance(
                        ds,
                        idx,
                        (args) -> {},
                        (args) -> {},
                        (args) -> {
                            callbackCalled.set(true);
                            latch.countDown();
                        }
                );

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();
            Truth.assertThat(callbackCalled.get()).isFalse();
        } finally {
            traceMonitor.stop(mWriter);
        }

        latch.await(3, TimeUnit.SECONDS);
        Truth.assertThat(callbackCalled.get()).isTrue();
    }

    @Test
    public void canUseDataSourceInstanceToCreateTlsState() throws InvalidProtocolBufferException {
        final Object testObject = new Object();

        sInstanceProvider = (ds, idx, configStream) -> {
            final TestDataSource.TestDataSourceInstance dsInstance =
                    new TestDataSource.TestDataSourceInstance(ds, idx);
            dsInstance.testObject = testObject;
            return dsInstance;
        };

        sTlsStateProvider = args -> {
            final TestDataSource.TestTlsState tlsState = new TestDataSource.TestTlsState();

            try (TestDataSource.TestDataSourceInstance dataSourceInstance =
                         args.getDataSourceInstanceLocked()) {
                if (dataSourceInstance != null) {
                    tlsState.testStateValue = dataSourceInstance.testObject.hashCode();
                }
            }

            return tlsState;
        };

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();
            sTestDataSource.trace((ctx) -> {
                final ProtoOutputStream protoOutputStream = ctx.newTracePacket();
                long forTestingToken = protoOutputStream.start(FOR_TESTING);
                long payloadToken = protoOutputStream.start(PAYLOAD);
                protoOutputStream.write(SINGLE_INT, ctx.getCustomTlsState().testStateValue);
                protoOutputStream.end(payloadToken);
                protoOutputStream.end(forTestingToken);
            });
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final byte[] rawProtoFromFile = reader.readBytes(TraceType.PERFETTO, Tag.ALL);
        assert rawProtoFromFile != null;
        final perfetto.protos.TraceOuterClass.Trace trace = perfetto.protos.TraceOuterClass.Trace
                .parseFrom(rawProtoFromFile);

        Truth.assertThat(trace.getPacketCount()).isGreaterThan(0);
        final List<TracePacketOuterClass.TracePacket> tracePackets = trace.getPacketList()
                .stream().filter(TracePacketOuterClass.TracePacket::hasForTesting).toList();
        final List<TracePacketOuterClass.TracePacket> matchingPackets = tracePackets.stream()
                .filter(it -> it.getForTesting().getPayload().getSingleInt()
                        == testObject.hashCode()).toList();
        Truth.assertThat(matchingPackets).hasSize(1);
    }

    @Test
    public void canUseDataSourceInstanceToCreateIncrementalState()
            throws InvalidProtocolBufferException {
        final Object testObject = new Object();

        sInstanceProvider = (ds, idx, configStream) -> {
            final TestDataSource.TestDataSourceInstance dsInstance =
                    new TestDataSource.TestDataSourceInstance(ds, idx);
            dsInstance.testObject = testObject;
            return dsInstance;
        };

        sIncrementalStateProvider = args -> {
            final TestDataSource.TestIncrementalState incrementalState =
                    new TestDataSource.TestIncrementalState();

            try (TestDataSource.TestDataSourceInstance dataSourceInstance =
                    args.getDataSourceInstanceLocked()) {
                if (dataSourceInstance != null) {
                    incrementalState.testStateValue = dataSourceInstance.testObject.hashCode();
                }
            }

            return incrementalState;
        };

        final TraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableCustomTrace(PerfettoConfig.DataSourceConfig.newBuilder()
                        .setName(sTestDataSource.name).build()).build();

        try {
            traceMonitor.start();
            sTestDataSource.trace((ctx) -> {
                final ProtoOutputStream protoOutputStream = ctx.newTracePacket();
                long forTestingToken = protoOutputStream.start(FOR_TESTING);
                long payloadToken = protoOutputStream.start(PAYLOAD);
                protoOutputStream.write(SINGLE_INT, ctx.getIncrementalState().testStateValue);
                protoOutputStream.end(payloadToken);
                protoOutputStream.end(forTestingToken);
            });
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final byte[] rawProtoFromFile = reader.readBytes(TraceType.PERFETTO, Tag.ALL);
        assert rawProtoFromFile != null;
        final perfetto.protos.TraceOuterClass.Trace trace = perfetto.protos.TraceOuterClass.Trace
                .parseFrom(rawProtoFromFile);

        Truth.assertThat(trace.getPacketCount()).isGreaterThan(0);
        final List<TracePacketOuterClass.TracePacket> tracePackets = trace.getPacketList()
                .stream().filter(TracePacketOuterClass.TracePacket::hasForTesting).toList();
        final List<TracePacketOuterClass.TracePacket> matchingPackets = tracePackets.stream()
                .filter(it -> it.getForTesting().getPayload().getSingleInt()
                        == testObject.hashCode()).toList();
        Truth.assertThat(matchingPackets).hasSize(1);
    }

    interface RunnableCreator {
        Runnable create(int state, AtomicInteger stateOut);
    }
}
