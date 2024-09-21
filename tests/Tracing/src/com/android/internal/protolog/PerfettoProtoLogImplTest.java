/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.protolog;

import static android.tools.traces.Utils.busyWaitForDataSourceRegistration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.io.File.createTempFile;

import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.tools.ScenarioBuilder;
import android.tools.traces.TraceConfig;
import android.tools.traces.TraceConfigs;
import android.tools.traces.io.ResultReader;
import android.tools.traces.io.ResultWriter;
import android.tools.traces.monitors.PerfettoTraceMonitor;
import android.tools.traces.protolog.ProtoLogTrace;
import android.tracing.perfetto.DataSource;
import android.util.proto.ProtoInputStream;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.protolog.ProtoLogConfigurationServiceImpl.ViewerConfigFileTracer;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogDataType;
import com.android.internal.protolog.common.LogLevel;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import perfetto.protos.Protolog;
import perfetto.protos.ProtologCommon;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for {@link ProtoLogImpl}.
 */
@SuppressWarnings("ConstantConditions")
@Presubmit
@RunWith(JUnit4.class)
public class PerfettoProtoLogImplTest {
    private static final String TEST_PROTOLOG_DATASOURCE_NAME = "test.android.protolog";
    private static final String MOCK_VIEWER_CONFIG_FILE = "my/mock/viewer/config/file.pb";
    private final File mTracingDirectory = InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getFilesDir();

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

    private static ProtoLogConfigurationService sProtoLogConfigurationService;
    private static PerfettoProtoLogImpl sProtoLog;
    private static Protolog.ProtoLogViewerConfig.Builder sViewerConfigBuilder;
    private static Runnable sCacheUpdater;

    private static ProtoLogViewerConfigReader sReader;

    public PerfettoProtoLogImplTest() throws IOException {
    }

    @BeforeClass
    public static void setUp() throws Exception {
        sViewerConfigBuilder = Protolog.ProtoLogViewerConfig.newBuilder()
                .addGroups(
                        Protolog.ProtoLogViewerConfig.Group.newBuilder()
                                .setId(1)
                                .setName(TestProtoLogGroup.TEST_GROUP.toString())
                                .setTag(TestProtoLogGroup.TEST_GROUP.getTag())
                ).addMessages(
                        Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(1)
                                .setMessage("My Test Debug Log Message %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_DEBUG)
                                .setGroupId(1)
                                .setLocation("com/test/MyTestClass.java:123")
                ).addMessages(
                        Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(2)
                                .setMessage("My Test Verbose Log Message %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_VERBOSE)
                                .setGroupId(1)
                                .setLocation("com/test/MyTestClass.java:342")
                ).addMessages(
                        Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(3)
                                .setMessage("My Test Warn Log Message %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_WARN)
                                .setGroupId(1)
                                .setLocation("com/test/MyTestClass.java:563")
                ).addMessages(
                        Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(4)
                                .setMessage("My Test Error Log Message %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_ERROR)
                                .setGroupId(1)
                                .setLocation("com/test/MyTestClass.java:156")
                ).addMessages(
                        Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(5)
                                .setMessage("My Test WTF Log Message %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_WTF)
                                .setGroupId(1)
                                .setLocation("com/test/MyTestClass.java:192")
                );

        ViewerConfigInputStreamProvider viewerConfigInputStreamProvider = Mockito.mock(
                ViewerConfigInputStreamProvider.class);
        Mockito.when(viewerConfigInputStreamProvider.getInputStream())
                .thenAnswer(it -> new ProtoInputStream(sViewerConfigBuilder.build().toByteArray()));

        sCacheUpdater = () -> {};
        sReader = Mockito.spy(new ProtoLogViewerConfigReader(viewerConfigInputStreamProvider));

        final ProtoLogDataSourceBuilder dataSourceBuilder =
                (onStart, onFlush, onStop) -> new ProtoLogDataSource(
                        onStart, onFlush, onStop, TEST_PROTOLOG_DATASOURCE_NAME);
        final ViewerConfigFileTracer tracer = (dataSource, viewerConfigFilePath) -> {
            Utils.dumpViewerConfig(dataSource, () -> {
                if (!viewerConfigFilePath.equals(MOCK_VIEWER_CONFIG_FILE)) {
                    throw new RuntimeException(
                            "Unexpected viewer config file path provided");
                }
                return new ProtoInputStream(sViewerConfigBuilder.build().toByteArray());
            });
        };
        sProtoLogConfigurationService =
                new ProtoLogConfigurationServiceImpl(dataSourceBuilder, tracer);

        if (android.tracing.Flags.clientSideProtoLogging()) {
            sProtoLog = new PerfettoProtoLogImpl(
                    MOCK_VIEWER_CONFIG_FILE, sReader, () -> sCacheUpdater.run(),
                    TestProtoLogGroup.values(), dataSourceBuilder, sProtoLogConfigurationService);
        } else {
            sProtoLog = new PerfettoProtoLogImpl(
                    viewerConfigInputStreamProvider, sReader, () -> sCacheUpdater.run(),
                    TestProtoLogGroup.values(), dataSourceBuilder, sProtoLogConfigurationService);
        }

        busyWaitForDataSourceRegistration(TEST_PROTOLOG_DATASOURCE_NAME);
    }

    @Before
    public void before() {
        Mockito.reset(sReader);

        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(false);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);
    }

    @After
    public void tearDown() {
        ProtoLogImpl.setSingleInstance(null);
    }

    @Test
    public void isEnabled_returnsFalseByDefault() {
        assertFalse(sProtoLog.isProtoEnabled());
    }

    @Test
    public void isEnabled_returnsTrueAfterStart() {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            assertTrue(sProtoLog.isProtoEnabled());
        } finally {
            traceMonitor.stop(mWriter);
        }
    }

    @Test
    public void isEnabled_returnsFalseAfterStop() {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            assertTrue(sProtoLog.isProtoEnabled());
        } finally {
            traceMonitor.stop(mWriter);
        }

        assertFalse(sProtoLog.isProtoEnabled());
    }

    @Test
    public void defaultMode() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(false, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            // Shouldn't be logging anything except WTF unless explicitly requested in the group
            // override.
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.VERBOSE, TestProtoLogGroup.TEST_GROUP, 2,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WARN, TestProtoLogGroup.TEST_GROUP, 3,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.ERROR, TestProtoLogGroup.TEST_GROUP, 4,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WTF, TestProtoLogGroup.TEST_GROUP, 5,
                    LogDataType.BOOLEAN, new Object[]{true});
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        Truth.assertThat(protolog.messages.getFirst().getLevel()).isEqualTo(LogLevel.WTF);
    }

    @Test
    public void respectsOverrideConfigs_defaultMode() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(
                        true,
                        List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.DEBUG, true)),
                        TEST_PROTOLOG_DATASOURCE_NAME
                ).build();
        try {
            traceMonitor.start();
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.VERBOSE, TestProtoLogGroup.TEST_GROUP, 2,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WARN, TestProtoLogGroup.TEST_GROUP, 3,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.ERROR, TestProtoLogGroup.TEST_GROUP, 4,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WTF, TestProtoLogGroup.TEST_GROUP, 5,
                    LogDataType.BOOLEAN, new Object[]{true});
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(5);
        Truth.assertThat(protolog.messages.get(0).getLevel()).isEqualTo(LogLevel.DEBUG);
        Truth.assertThat(protolog.messages.get(1).getLevel()).isEqualTo(LogLevel.VERBOSE);
        Truth.assertThat(protolog.messages.get(2).getLevel()).isEqualTo(LogLevel.WARN);
        Truth.assertThat(protolog.messages.get(3).getLevel()).isEqualTo(LogLevel.ERROR);
        Truth.assertThat(protolog.messages.get(4).getLevel()).isEqualTo(LogLevel.WTF);
    }

    @Test
    public void respectsOverrideConfigs_allEnabledMode() throws IOException {
        PerfettoTraceMonitor traceMonitor =
                PerfettoTraceMonitor.newBuilder().enableProtoLog(
                        true,
                        List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.WARN, false)),
                        TEST_PROTOLOG_DATASOURCE_NAME
                    ).build();
        try {
            traceMonitor.start();
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.VERBOSE, TestProtoLogGroup.TEST_GROUP, 2,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WARN, TestProtoLogGroup.TEST_GROUP, 3,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.ERROR, TestProtoLogGroup.TEST_GROUP, 4,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WTF, TestProtoLogGroup.TEST_GROUP, 5,
                    LogDataType.BOOLEAN, new Object[]{true});
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(3);
        Truth.assertThat(protolog.messages.get(0).getLevel()).isEqualTo(LogLevel.WARN);
        Truth.assertThat(protolog.messages.get(1).getLevel()).isEqualTo(LogLevel.ERROR);
        Truth.assertThat(protolog.messages.get(2).getLevel()).isEqualTo(LogLevel.WTF);
    }

    @Test
    public void respectsAllEnabledMode() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.VERBOSE, TestProtoLogGroup.TEST_GROUP, 2,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WARN, TestProtoLogGroup.TEST_GROUP, 3,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.ERROR, TestProtoLogGroup.TEST_GROUP, 4,
                    LogDataType.BOOLEAN, new Object[]{true});
            sProtoLog.log(LogLevel.WTF, TestProtoLogGroup.TEST_GROUP, 5,
                    LogDataType.BOOLEAN, new Object[]{true});
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(5);
        Truth.assertThat(protolog.messages.get(0).getLevel()).isEqualTo(LogLevel.DEBUG);
        Truth.assertThat(protolog.messages.get(1).getLevel()).isEqualTo(LogLevel.VERBOSE);
        Truth.assertThat(protolog.messages.get(2).getLevel()).isEqualTo(LogLevel.WARN);
        Truth.assertThat(protolog.messages.get(3).getLevel()).isEqualTo(LogLevel.ERROR);
        Truth.assertThat(protolog.messages.get(4).getLevel()).isEqualTo(LogLevel.WTF);
    }

    @Test
    public void log_logcatEnabled() {
        when(sReader.getViewerString(anyLong())).thenReturn("test %b %d %% 0x%x %s %f");
        PerfettoProtoLogImpl implSpy = Mockito.spy(sProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{true, 10000, 30000, "test", 0.000003});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO),
                eq("test true 10000 % 0x7530 test 3.0E-6"));
        verify(sReader).getViewerString(eq(1234L));
    }

    @Test
    public void log_logcatEnabledInvalidMessage() {
        when(sReader.getViewerString(anyLong())).thenReturn("test %b %d %% %x %s %f");
        PerfettoProtoLogImpl implSpy = Mockito.spy(sProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{true, 10000, 0.0001, 0.00002, "test"});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO),
                eq("FORMAT_ERROR \"test %b %d %% %x %s %f\", "
                        + "args=(true, 10000, 1.0E-4, 2.0E-5, test)"));
        verify(sReader).getViewerString(eq(1234L));
    }

    @Test
    public void log_logcatEnabledNoMessage() {
        when(sReader.getViewerString(anyLong())).thenReturn(null);
        PerfettoProtoLogImpl implSpy = Mockito.spy(sProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{5});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO), eq("UNKNOWN MESSAGE args = (5)"));
        verify(sReader).getViewerString(eq(1234L));
    }

    @Test
    public void log_logcatDisabled() {
        when(sReader.getViewerString(anyLong())).thenReturn("test %d");
        PerfettoProtoLogImpl implSpy = Mockito.spy(sProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{5});

        verify(implSpy, never()).passToLogcat(any(), any(), any());
        verify(sReader, never()).getViewerString(anyLong());
    }

    @Test
    public void log_protoEnabled() throws Exception {
        final long messageHash = addMessageToConfig(
                ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_INFO,
                "My test message :: %s, %d, %o, %x, %f, %e, %g, %b");

        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        long before;
        long after;
        try {
            assertFalse(sProtoLog.isProtoEnabled());
            traceMonitor.start();
            assertTrue(sProtoLog.isProtoEnabled());

            before = SystemClock.elapsedRealtimeNanos();
            sProtoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, messageHash,
                    0b1110101001010100,
                    new Object[]{"test", 1, 2, 3, 0.4, 0.5, 0.6, true});
            after = SystemClock.elapsedRealtimeNanos();
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        Truth.assertThat(protolog.messages.getFirst().getTimestamp().getElapsedNanos())
                .isAtLeast(before);
        Truth.assertThat(protolog.messages.getFirst().getTimestamp().getElapsedNanos())
                .isAtMost(after);
        Truth.assertThat(protolog.messages.getFirst().getMessage())
                .isEqualTo(
                        "My test message :: test, 1, 2, 3, 0.400000, 5.000000e-01, 0.6, true");
    }

    @Test
    public void log_noProcessing() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        long before;
        long after;
        try {
            traceMonitor.start();
            assertTrue(sProtoLog.isProtoEnabled());

            before = SystemClock.elapsedRealtimeNanos();
            sProtoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP,
                    "My test message :: %s, %d, %x, %f, %b",
                    "test", 1, 3, 0.4, true);
            after = SystemClock.elapsedRealtimeNanos();
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        Truth.assertThat(protolog.messages.getFirst().getTimestamp().getElapsedNanos())
                .isAtLeast(before);
        Truth.assertThat(protolog.messages.getFirst().getTimestamp().getElapsedNanos())
                .isAtMost(after);
        Truth.assertThat(protolog.messages.getFirst().getMessage())
                .isEqualTo("My test message :: test, 1, 3, 0.400000, true");
    }

    @Test
    public  void supportsLocationInformation() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    LogDataType.BOOLEAN, new Object[]{true});
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        Truth.assertThat(protolog.messages.get(0).getLocation())
                .isEqualTo("com/test/MyTestClass.java:123");
    }

    private long addMessageToConfig(ProtologCommon.ProtoLogLevel logLevel, String message) {
        final long messageId = new Random().nextLong();
        sViewerConfigBuilder.addMessages(Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                .setMessageId(messageId)
                .setMessage(message)
                .setLevel(logLevel)
                .setGroupId(1)
        );

        return messageId;
    }

    @Test
    public void log_invalidParamsMask() {
        final long messageHash = addMessageToConfig(
                ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_INFO,
                "My test message :: %s, %d, %f, %b");
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        long before;
        long after;
        try {
            traceMonitor.start();
            before = SystemClock.elapsedRealtimeNanos();
            sProtoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, messageHash,
                    0b01100100,
                    new Object[]{"test", 1, 0.1, true});
            after = SystemClock.elapsedRealtimeNanos();
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        assertThrows(IllegalStateException.class, reader::readProtoLogTrace);
    }

    @Test
    public void log_protoDisabled() throws Exception {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(false, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    0b11, new Object[]{true});
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).isEmpty();
    }

    @Test
    public void stackTraceTrimmed() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(
                        true,
                        List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.DEBUG,
                                true)),
                        TEST_PROTOLOG_DATASOURCE_NAME
                ).build();
        try {
            traceMonitor.start();

            ProtoLogImpl.setSingleInstance(sProtoLog);
            ProtoLogImpl.d(TestProtoLogGroup.TEST_GROUP, 1,
                    0b11, true);
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        String stacktrace = protolog.messages.getFirst().getStacktrace();
        Truth.assertThat(stacktrace)
                .doesNotContain(PerfettoProtoLogImpl.class.getSimpleName() + ".java");
        Truth.assertThat(stacktrace).doesNotContain(DataSource.class.getSimpleName() + ".java");
        Truth.assertThat(stacktrace)
                .doesNotContain(ProtoLogImpl.class.getSimpleName() + ".java");
        Truth.assertThat(stacktrace).contains(PerfettoProtoLogImplTest.class.getSimpleName());
        Truth.assertThat(stacktrace).contains("stackTraceTrimmed");
    }

    @Test
    public void cacheIsUpdatedWhenTracesStartAndStop() {
        final AtomicInteger cacheUpdateCallCount = new AtomicInteger(0);
        sCacheUpdater = cacheUpdateCallCount::incrementAndGet;

        PerfettoTraceMonitor traceMonitor1 = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true,
                        List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.WARN,
                                false)), TEST_PROTOLOG_DATASOURCE_NAME
                ).build();

        PerfettoTraceMonitor traceMonitor2 =
                PerfettoTraceMonitor.newBuilder().enableProtoLog(true,
                                List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                        TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.DEBUG,
                                        false)), TEST_PROTOLOG_DATASOURCE_NAME)
                        .build();

        Truth.assertThat(cacheUpdateCallCount.get()).isEqualTo(0);

        try {
            traceMonitor1.start();

            Truth.assertThat(cacheUpdateCallCount.get()).isEqualTo(1);

            try {
                traceMonitor2.start();

                Truth.assertThat(cacheUpdateCallCount.get()).isEqualTo(2);
            } finally {
                traceMonitor2.stop(mWriter);
            }

            Truth.assertThat(cacheUpdateCallCount.get()).isEqualTo(3);

        } finally {
            traceMonitor1.stop(mWriter);
        }

        Truth.assertThat(cacheUpdateCallCount.get()).isEqualTo(4);
    }

    @Test
    public void isEnabledUpdatesBasedOnRunningTraces() {
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.DEBUG))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.VERBOSE))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.INFO))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WARN))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.ERROR))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WTF)).isFalse();

        PerfettoTraceMonitor traceMonitor1 =
                PerfettoTraceMonitor.newBuilder().enableProtoLog(true,
                                List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                        TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.WARN,
                                        false)), TEST_PROTOLOG_DATASOURCE_NAME)
                        .build();

        PerfettoTraceMonitor traceMonitor2 =
                PerfettoTraceMonitor.newBuilder().enableProtoLog(true,
                                List.of(new PerfettoTraceMonitor.Builder.ProtoLogGroupOverride(
                                        TestProtoLogGroup.TEST_GROUP.toString(), LogLevel.DEBUG,
                                        false)), TEST_PROTOLOG_DATASOURCE_NAME)
                        .build();

        try {
            traceMonitor1.start();

            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.DEBUG))
                    .isFalse();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.VERBOSE))
                    .isFalse();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.INFO))
                    .isFalse();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WARN))
                    .isTrue();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.ERROR))
                    .isTrue();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WTF))
                    .isTrue();

            try {
                traceMonitor2.start();

                Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.DEBUG))
                        .isTrue();
                Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP,
                        LogLevel.VERBOSE)).isTrue();
                Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.INFO))
                        .isTrue();
                Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WARN))
                        .isTrue();
                Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.ERROR))
                        .isTrue();
                Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WTF))
                        .isTrue();
            } finally {
                traceMonitor2.stop(mWriter);
            }

            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.DEBUG))
                    .isFalse();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.VERBOSE))
                    .isFalse();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.INFO))
                    .isFalse();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WARN))
                    .isTrue();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.ERROR))
                    .isTrue();
            Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WTF))
                    .isTrue();
        } finally {
            traceMonitor1.stop(mWriter);
        }

        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.DEBUG))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.VERBOSE))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.INFO))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WARN))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.ERROR))
                .isFalse();
        Truth.assertThat(sProtoLog.isEnabled(TestProtoLogGroup.TEST_GROUP, LogLevel.WTF))
                .isFalse();
    }

    @Test
    public void supportsNullString() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();

        try {
            traceMonitor.start();

            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP,
                    "My test null string: %s", (Object) null);
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        Truth.assertThat(protolog.messages.get(0).getMessage())
                .isEqualTo("My test null string: null");
    }

    @Test
    public void supportNullParams() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();

        try {
            traceMonitor.start();

            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP,
                    "My null args: %d, %f, %b", null, null, null);
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(1);
        Truth.assertThat(protolog.messages.get(0).getMessage())
                .isEqualTo("My null args: 0, 0.000000, false");
    }

    @Test
    public void handlesConcurrentTracingSessions() throws IOException {
        PerfettoTraceMonitor traceMonitor1 = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();

        PerfettoTraceMonitor traceMonitor2 = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(true, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();

        final ResultWriter writer2 = new ResultWriter()
                .forScenario(new ScenarioBuilder()
                        .forClass(createTempFile("temp", "").getName()).build())
                .withOutputDir(mTracingDirectory)
                .setRunComplete();

        try {
            traceMonitor1.start();
            traceMonitor2.start();

            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP, 1,
                    LogDataType.BOOLEAN, new Object[]{true});
        } finally {
            traceMonitor1.stop(mWriter);
            traceMonitor2.stop(writer2);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protologFromMonitor1 = reader.readProtoLogTrace();

        final ResultReader reader2 = new ResultReader(writer2.write(), mTraceConfig);
        final ProtoLogTrace protologFromMonitor2 = reader2.readProtoLogTrace();

        Truth.assertThat(protologFromMonitor1.messages).hasSize(1);
        Truth.assertThat(protologFromMonitor1.messages.get(0).getMessage())
                .isEqualTo("My Test Debug Log Message true");

        Truth.assertThat(protologFromMonitor2.messages).hasSize(1);
        Truth.assertThat(protologFromMonitor2.messages.get(0).getMessage())
                .isEqualTo("My Test Debug Log Message true");
    }

    @Test
    public void usesDefaultLogFromLevel() throws IOException {
        PerfettoTraceMonitor traceMonitor = PerfettoTraceMonitor.newBuilder()
                .enableProtoLog(LogLevel.WARN, List.of(), TEST_PROTOLOG_DATASOURCE_NAME)
                .build();
        try {
            traceMonitor.start();
            sProtoLog.log(LogLevel.DEBUG, TestProtoLogGroup.TEST_GROUP,
                    "This message should not be logged");
            sProtoLog.log(LogLevel.WARN, TestProtoLogGroup.TEST_GROUP,
                    "This message should be logged %d", 123);
            sProtoLog.log(LogLevel.ERROR, TestProtoLogGroup.TEST_GROUP,
                    "This message should also be logged %d", 567);
        } finally {
            traceMonitor.stop(mWriter);
        }

        final ResultReader reader = new ResultReader(mWriter.write(), mTraceConfig);
        final ProtoLogTrace protolog = reader.readProtoLogTrace();

        Truth.assertThat(protolog.messages).hasSize(2);

        Truth.assertThat(protolog.messages.get(0).getLevel())
                .isEqualTo(LogLevel.WARN);
        Truth.assertThat(protolog.messages.get(0).getMessage())
                .isEqualTo("This message should be logged 123");

        Truth.assertThat(protolog.messages.get(1).getLevel())
                .isEqualTo(LogLevel.ERROR);
        Truth.assertThat(protolog.messages.get(1).getMessage())
                .isEqualTo("This message should also be logged 567");
    }

    private enum TestProtoLogGroup implements IProtoLogGroup {
        TEST_GROUP(true, true, false, "TEST_TAG");

        private final boolean mEnabled;
        private volatile boolean mLogToProto;
        private volatile boolean mLogToLogcat;
        private final String mTag;

        /**
         * @param enabled     set to false to exclude all log statements for this group from
         *                    compilation,
         *                    they will not be available in runtime.
         * @param logToProto  enable binary logging for the group
         * @param logToLogcat enable text logging for the group
         * @param tag         name of the source of the logged message
         */
        TestProtoLogGroup(boolean enabled, boolean logToProto, boolean logToLogcat, String tag) {
            this.mEnabled = enabled;
            this.mLogToProto = logToProto;
            this.mLogToLogcat = logToLogcat;
            this.mTag = tag;
        }

        @Override
        public boolean isEnabled() {
            return mEnabled;
        }

        @Override
        public boolean isLogToProto() {
            return mLogToProto;
        }

        @Override
        public boolean isLogToLogcat() {
            return mLogToLogcat;
        }

        @Override
        public boolean isLogToAny() {
            return mLogToLogcat || mLogToProto;
        }

        @Override
        public String getTag() {
            return mTag;
        }

        @Override
        public void setLogToProto(boolean logToProto) {
            this.mLogToProto = logToProto;
        }

        @Override
        public void setLogToLogcat(boolean logToLogcat) {
            this.mLogToLogcat = logToLogcat;
        }

        @Override
        public int getId() {
            return ordinal();
        }

    }
}
