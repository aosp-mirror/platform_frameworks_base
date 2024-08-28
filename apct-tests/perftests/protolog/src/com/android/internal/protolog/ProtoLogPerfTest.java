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

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ProtoLogPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name="logToProto_{0}_logToLogcat_{1}")
    public static Collection<Object[]> params() {
        return Arrays.asList(new Object[][] {
                { true, true },
                { true, false },
                { false, true },
                { false, false }
        });
    }

    private final boolean mLogToProto;
    private final boolean mLogToLogcat;

    public ProtoLogPerfTest(boolean logToProto, boolean logToLogcat) {
        mLogToProto = logToProto;
        mLogToLogcat = logToLogcat;
    }

    @BeforeClass
    public static void init() {
        ProtoLog.init(TestProtoLogGroup.values());
    }

    @Before
    public void setUp() {
        TestProtoLogGroup.TEST_GROUP.setLogToProto(mLogToProto);
        TestProtoLogGroup.TEST_GROUP.setLogToLogcat(mLogToLogcat);
    }

    @Test
    public void logProcessedProtoLogMessageWithoutArgs() {
        final var protoLog = ProtoLog.getSingleInstance();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            protoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 123,
                    0, (Object[]) null);
        }
    }

    @Test
    public void logProcessedProtoLogMessageWithArgs() {
        final var protoLog = ProtoLog.getSingleInstance();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            protoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 123,
                    0b1110101001010100,
                    new Object[]{"test", 1, 2, 3, 0.4, 0.5, 0.6, true});
        }
    }

    @Test
    public void logNonProcessedProtoLogMessageWithNoArgs() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ProtoLog.d(TestProtoLogGroup.TEST_GROUP, "Test message");
        }
    }

    @Test
    public void logNonProcessedProtoLogMessageWithArgs() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ProtoLog.d(TestProtoLogGroup.TEST_GROUP, "Test messag %s, %d, %b", "arg1", 2, true);
        }
    }

    private enum TestProtoLogGroup implements IProtoLogGroup {
        TEST_GROUP(true, true, false, "WindowManagetProtoLogTest");

        private final boolean mEnabled;
        private volatile boolean mLogToProto;
        private volatile boolean mLogToLogcat;
        private final String mTag;

        /**
         * @param enabled set to false to exclude all log statements for this group from
         *     compilation, they will not be available in runtime.
         * @param logToProto enable binary logging for the group
         * @param logToLogcat enable text logging for the group
         * @param tag name of the source of the logged message
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
