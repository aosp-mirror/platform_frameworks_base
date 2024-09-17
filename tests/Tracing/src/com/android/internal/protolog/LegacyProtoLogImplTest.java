/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.internal.protolog.LegacyProtoLogImpl.PROTOLOG_VERSION;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.proto.ProtoInputStream;

import androidx.test.filters.SmallTest;

import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.LinkedList;

/**
 * Test class for {@link ProtoLogImpl}.
 */
@SuppressWarnings("ConstantConditions")
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class LegacyProtoLogImplTest {

    private static final byte[] MAGIC_HEADER = new byte[]{
            0x9, 0x50, 0x52, 0x4f, 0x54, 0x4f, 0x4c, 0x4f, 0x47
    };

    private LegacyProtoLogImpl mProtoLog;
    private File mFile;

    @Mock
    private LegacyProtoLogViewerConfigReader mReader;

    private final String mViewerConfigFilename = "unused/file/path";

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        final Context testContext = getInstrumentation().getContext();
        mFile = testContext.getFileStreamPath("tracing_test.dat");
        //noinspection ResultOfMethodCallIgnored
        mFile.delete();
        mProtoLog = new LegacyProtoLogImpl(mFile, mViewerConfigFilename,
                1024 * 1024, mReader, 1024, () -> {});
    }

    @After
    public void tearDown() {
        if (mFile != null) {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
        }
        ProtoLogImpl.setSingleInstance(null);
    }

    @Test
    public void isEnabled_returnsFalseByDefault() {
        assertFalse(mProtoLog.isProtoEnabled());
    }

    @Test
    public void isEnabled_returnsTrueAfterStart() {
        mProtoLog.startProtoLog(mock(PrintWriter.class));
        assertTrue(mProtoLog.isProtoEnabled());
    }

    @Test
    public void isEnabled_returnsFalseAfterStop() {
        mProtoLog.startProtoLog(mock(PrintWriter.class));
        mProtoLog.stopProtoLog(mock(PrintWriter.class), true);
        assertFalse(mProtoLog.isProtoEnabled());
    }

    @Test
    public void logFile_startsWithMagicHeader() throws Exception {
        mProtoLog.startProtoLog(mock(PrintWriter.class));
        mProtoLog.stopProtoLog(mock(PrintWriter.class), true);

        assertTrue("Log file should exist", mFile.exists());

        byte[] header = new byte[MAGIC_HEADER.length];
        try (InputStream is = new FileInputStream(mFile)) {
            assertEquals(MAGIC_HEADER.length, is.read(header));
            assertArrayEquals(MAGIC_HEADER, header);
        }
    }

    @Test
    public void log_logcatEnabledExternalMessage() {
        when(mReader.getViewerString(anyLong())).thenReturn("test %b %d %% 0x%x %s %f");
        LegacyProtoLogImpl implSpy = Mockito.spy(mProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{true, 10000, 30000, "test", 0.000003});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO),
                eq("test true 10000 % 0x7530 test 3.0E-6"));
        verify(mReader).getViewerString(eq(1234L));
    }

    @Test
    public void log_logcatEnabledInvalidMessage() {
        when(mReader.getViewerString(anyLong())).thenReturn("test %b %d %% %x %s %f");
        LegacyProtoLogImpl implSpy = Mockito.spy(mProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{true, 10000, 0.0001, 0.00002, "test"});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO),
                eq("UNKNOWN MESSAGE (1234) true 10000 1.0E-4 2.0E-5 test"));
        verify(mReader).getViewerString(eq(1234L));
    }

    @Test
    public void log_logcatEnabledInlineMessage() {
        when(mReader.getViewerString(anyLong())).thenReturn("test %d");
        LegacyProtoLogImpl implSpy = Mockito.spy(mProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{5});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO), eq("test 5"));
    }

    @Test
    public void log_logcatEnabledNoMessage() {
        when(mReader.getViewerString(anyLong())).thenReturn(null);
        LegacyProtoLogImpl implSpy = Mockito.spy(mProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{5});

        verify(implSpy).passToLogcat(eq(TestProtoLogGroup.TEST_GROUP.getTag()), eq(
                LogLevel.INFO), eq("UNKNOWN MESSAGE (1234) 5"));
        verify(mReader).getViewerString(eq(1234L));
    }

    @Test
    public void log_logcatDisabled() {
        when(mReader.getViewerString(anyLong())).thenReturn("test %d");
        LegacyProtoLogImpl implSpy = Mockito.spy(mProtoLog);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(false);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);

        implSpy.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234, 4321,
                new Object[]{5});

        verify(implSpy, never()).passToLogcat(any(), any(), any());
        verify(mReader, never()).getViewerString(anyLong());
    }

    @Test
    public void loadViewerConfigOnLogcatGroupRegistration() {
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(true);
        mProtoLog.registerGroups(TestProtoLogGroup.TEST_GROUP);
        verify(mReader).loadViewerConfig(any(), any());
    }

    private static class ProtoLogData {
        Long mMessageHash = null;
        Long mElapsedTime = null;
        LinkedList<String> mStrParams = new LinkedList<>();
        LinkedList<Long> mSint64Params = new LinkedList<>();
        LinkedList<Double> mDoubleParams = new LinkedList<>();
        LinkedList<Boolean> mBooleanParams = new LinkedList<>();
    }

    private ProtoLogData readProtoLogSingle(ProtoInputStream ip) throws IOException {
        while (ip.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (ip.getFieldNumber() == (int) ProtoLogFileProto.VERSION) {
                assertEquals(PROTOLOG_VERSION, ip.readString(ProtoLogFileProto.VERSION));
                continue;
            }
            if (ip.getFieldNumber() != (int) ProtoLogFileProto.LOG) {
                continue;
            }
            long token = ip.start(ProtoLogFileProto.LOG);
            ProtoLogData data = new ProtoLogData();
            while (ip.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (ip.getFieldNumber()) {
                    case (int) ProtoLogMessage.MESSAGE_HASH: {
                        data.mMessageHash = ip.readLong(ProtoLogMessage.MESSAGE_HASH);
                        break;
                    }
                    case (int) ProtoLogMessage.ELAPSED_REALTIME_NANOS: {
                        data.mElapsedTime = ip.readLong(ProtoLogMessage.ELAPSED_REALTIME_NANOS);
                        break;
                    }
                    case (int) ProtoLogMessage.STR_PARAMS: {
                        data.mStrParams.add(ip.readString(ProtoLogMessage.STR_PARAMS));
                        break;
                    }
                    case (int) ProtoLogMessage.SINT64_PARAMS: {
                        data.mSint64Params.add(ip.readLong(ProtoLogMessage.SINT64_PARAMS));
                        break;
                    }
                    case (int) ProtoLogMessage.DOUBLE_PARAMS: {
                        data.mDoubleParams.add(ip.readDouble(ProtoLogMessage.DOUBLE_PARAMS));
                        break;
                    }
                    case (int) ProtoLogMessage.BOOLEAN_PARAMS: {
                        data.mBooleanParams.add(ip.readBoolean(ProtoLogMessage.BOOLEAN_PARAMS));
                        break;
                    }
                }
            }
            ip.end(token);
            return data;
        }
        return null;
    }

    @Test
    public void log_protoEnabled() throws Exception {
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(false);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(true);
        mProtoLog.startProtoLog(mock(PrintWriter.class));
        long before = SystemClock.elapsedRealtimeNanos();
        mProtoLog.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234,
                0b1110101001010100,
                new Object[]{"test", 1, 2, 3, 0.4, 0.5, 0.6, true});
        long after = SystemClock.elapsedRealtimeNanos();
        mProtoLog.stopProtoLog(mock(PrintWriter.class), true);
        try (InputStream is = new FileInputStream(mFile)) {
            ProtoInputStream ip = new ProtoInputStream(is);
            ProtoLogData data = readProtoLogSingle(ip);
            assertNotNull(data);
            assertEquals(1234, data.mMessageHash.longValue());
            assertTrue(before <= data.mElapsedTime && data.mElapsedTime <= after);
            assertArrayEquals(new String[]{"test"}, data.mStrParams.toArray());
            assertArrayEquals(new Long[]{1L, 2L, 3L}, data.mSint64Params.toArray());
            assertArrayEquals(new Double[]{0.4, 0.5, 0.6}, data.mDoubleParams.toArray());
            assertArrayEquals(new Boolean[]{true}, data.mBooleanParams.toArray());
        }
    }

    @Test
    public void log_invalidParamsMask() throws Exception {
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(false);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(true);
        mProtoLog.startProtoLog(mock(PrintWriter.class));
        long before = SystemClock.elapsedRealtimeNanos();
        mProtoLog.log(
                LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234,
                0b01100100,
                new Object[]{"test", 1, 0.1, true});
        long after = SystemClock.elapsedRealtimeNanos();
        mProtoLog.stopProtoLog(mock(PrintWriter.class), true);
        try (InputStream is = new FileInputStream(mFile)) {
            ProtoInputStream ip = new ProtoInputStream(is);
            ProtoLogData data = readProtoLogSingle(ip);
            assertNotNull(data);
            assertEquals(1234, data.mMessageHash.longValue());
            assertTrue(before <= data.mElapsedTime && data.mElapsedTime <= after);
            assertArrayEquals(new String[]{"test", "(INVALID PARAMS_MASK) true"},
                    data.mStrParams.toArray());
            assertArrayEquals(new Long[]{1L}, data.mSint64Params.toArray());
            assertArrayEquals(new Double[]{0.1}, data.mDoubleParams.toArray());
            assertArrayEquals(new Boolean[]{}, data.mBooleanParams.toArray());
        }
    }

    @Test
    public void log_protoDisabled() throws Exception {
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(false);
        TestProtoLogGroup.TEST_GROUP.setLogToProto(false);
        mProtoLog.startProtoLog(mock(PrintWriter.class));
        mProtoLog.log(LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 1234,
                0b11, new Object[]{true});
        mProtoLog.stopProtoLog(mock(PrintWriter.class), true);
        try (InputStream is = new FileInputStream(mFile)) {
            ProtoInputStream ip = new ProtoInputStream(is);
            ProtoLogData data = readProtoLogSingle(ip);
            assertNull(data);
        }
    }

    private enum TestProtoLogGroup implements IProtoLogGroup {
        TEST_GROUP(true, true, false, "WindowManagetProtoLogTest");

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
