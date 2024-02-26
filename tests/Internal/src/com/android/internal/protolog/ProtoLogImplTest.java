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

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test class for {@link ProtoLogImpl}.
 */
@SuppressWarnings("ConstantConditions")
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ProtoLogImplTest {
    @After
    public void tearDown() {
        ProtoLogImpl.setSingleInstance(null);
    }

    @Test
    public void getSingleInstance() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        assertSame(mockedProtoLog, ProtoLogImpl.getSingleInstance());
    }

    @Test
    public void d_logCalled() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogImpl.d(TestProtoLogGroup.TEST_GROUP, 1234, 4321, "test %d");
        verify(mockedProtoLog).log(eq(LogLevel.DEBUG), eq(
                TestProtoLogGroup.TEST_GROUP),
                eq(1234L), eq(4321), eq("test %d"), eq(new Object[]{}));
    }

    @Test
    public void v_logCalled() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogImpl.v(TestProtoLogGroup.TEST_GROUP, 1234, 4321, "test %d");
        verify(mockedProtoLog).log(eq(LogLevel.VERBOSE), eq(
                TestProtoLogGroup.TEST_GROUP),
                eq(1234L), eq(4321), eq("test %d"), eq(new Object[]{}));
    }

    @Test
    public void i_logCalled() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogImpl.i(TestProtoLogGroup.TEST_GROUP, 1234, 4321, "test %d");
        verify(mockedProtoLog).log(eq(LogLevel.INFO), eq(
                TestProtoLogGroup.TEST_GROUP),
                eq(1234L), eq(4321), eq("test %d"), eq(new Object[]{}));
    }

    @Test
    public void w_logCalled() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogImpl.w(TestProtoLogGroup.TEST_GROUP, 1234,
                4321, "test %d");
        verify(mockedProtoLog).log(eq(LogLevel.WARN), eq(
                TestProtoLogGroup.TEST_GROUP),
                eq(1234L), eq(4321), eq("test %d"), eq(new Object[]{}));
    }

    @Test
    public void e_logCalled() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogImpl.e(TestProtoLogGroup.TEST_GROUP, 1234, 4321, "test %d");
        verify(mockedProtoLog).log(eq(LogLevel.ERROR), eq(
                TestProtoLogGroup.TEST_GROUP),
                eq(1234L), eq(4321), eq("test %d"), eq(new Object[]{}));
    }

    @Test
    public void wtf_logCalled() {
        IProtoLog mockedProtoLog = mock(IProtoLog.class);
        ProtoLogImpl.setSingleInstance(mockedProtoLog);
        ProtoLogImpl.wtf(TestProtoLogGroup.TEST_GROUP,
                1234, 4321, "test %d");
        verify(mockedProtoLog).log(eq(LogLevel.WTF), eq(
                TestProtoLogGroup.TEST_GROUP),
                eq(1234L), eq(4321), eq("test %d"), eq(new Object[]{}));
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

    }
}
