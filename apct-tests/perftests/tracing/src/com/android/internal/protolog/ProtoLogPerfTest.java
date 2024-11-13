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

import android.app.Activity;
import android.os.Bundle;
import android.perftests.utils.Stats;

import androidx.test.InstrumentationRegistry;

import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ProtoLogPerfTest {
    private final boolean mLogToProto;
    private final boolean mLogToLogcat;

    /**
     * Generates the parameters used for this test class
     */
    @Parameters(name = "LOG_TO_{0}")
    public static Collection<Object[]> params() {
        var params = new ArrayList<Object[]>();

        for (LogTo logTo : LogTo.values()) {
            params.add(new Object[] { logTo });
        }

        return params;
    }

    public ProtoLogPerfTest(LogTo logTo) {
        mLogToProto = switch (logTo) {
            case ALL, PROTO_ONLY -> true;
            case LOGCAT_ONLY, NONE -> false;
        };

        mLogToLogcat = switch (logTo) {
            case ALL, LOGCAT_ONLY -> true;
            case PROTO_ONLY, NONE -> false;
        };
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
    public void log_Processed_NoArgs() {
        final var protoLog = ProtoLog.getSingleInstance();
        final var perfMonitor = new PerfMonitor();

        while (perfMonitor.keepRunning()) {
            protoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 123,
                    0, (Object[]) null);
        }
    }

    @Test
    public void log_Processed_WithArgs() {
        final var protoLog = ProtoLog.getSingleInstance();
        final var perfMonitor = new PerfMonitor();

        while (perfMonitor.keepRunning()) {
            protoLog.log(
                    LogLevel.INFO, TestProtoLogGroup.TEST_GROUP, 123,
                    0b1110101001010100,
                    new Object[]{"test", 1, 2, 3, 0.4, 0.5, 0.6, true});
        }
    }

    @Test
    public void log_Unprocessed_NoArgs() {
        final var perfMonitor = new PerfMonitor();

        while (perfMonitor.keepRunning()) {
            ProtoLog.d(TestProtoLogGroup.TEST_GROUP, "Test message");
        }
    }

    @Test
    public void log_Unprocessed_WithArgs() {
        final var perfMonitor = new PerfMonitor();

        while (perfMonitor.keepRunning()) {
            ProtoLog.d(TestProtoLogGroup.TEST_GROUP, "Test message %s, %d, %b", "arg1", 2, true);
        }
    }

    private class PerfMonitor {
        private int mIteration = 0;

        private static final int WARM_UP_ITERATIONS = 10;
        private static final int ITERATIONS = 1000;

        private final ArrayList<Long> mResults = new ArrayList<>();

        private long mStartTimeNs;

        public boolean keepRunning() {
            final long currentTime = System.nanoTime();

            mIteration++;

            if (mIteration > ITERATIONS) {
                reportResults();
                return false;
            }

            if (mIteration > WARM_UP_ITERATIONS) {
                mResults.add(currentTime - mStartTimeNs);
            }

            mStartTimeNs = System.nanoTime();
            return true;
        }

        public void reportResults() {
            final var stats = new Stats(mResults);

            Bundle status = new Bundle();
            status.putLong("protologging_time_mean_ns", (long) stats.getMean());
            status.putLong("protologging_time_median_ns", (long) stats.getMedian());
            InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, status);
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

    private enum LogTo {
        PROTO_ONLY,
        LOGCAT_ONLY,
        ALL,
        NONE,
    }
}
