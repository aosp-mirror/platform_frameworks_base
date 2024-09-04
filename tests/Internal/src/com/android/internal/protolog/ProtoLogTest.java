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

import android.platform.test.annotations.Presubmit;

import com.android.internal.protolog.common.IProtoLogGroup;

import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test class for {@link ProtoLog}. */
@SuppressWarnings("ConstantConditions")
@Presubmit
@RunWith(JUnit4.class)
public class ProtoLogTest {

    @Test
    public void canRunProtoLogInitMultipleTimes() {
        ProtoLog.init(TEST_GROUP_1);
        ProtoLog.init(TEST_GROUP_1);
        ProtoLog.init(TEST_GROUP_2);
        ProtoLog.init(TEST_GROUP_1, TEST_GROUP_2);

        final var instance = ProtoLog.getSingleInstance();
        Truth.assertThat(instance.getRegisteredGroups())
                .containsExactly(TEST_GROUP_1, TEST_GROUP_2);
    }

    private static final IProtoLogGroup TEST_GROUP_1 = new ProtoLogGroup("TEST_TAG_1", 1);
    private static final IProtoLogGroup TEST_GROUP_2 = new ProtoLogGroup("TEST_TAG_2", 2);

    private static class ProtoLogGroup implements IProtoLogGroup {
        private final boolean mEnabled;
        private volatile boolean mLogToProto;
        private volatile boolean mLogToLogcat;
        private final String mTag;
        private final int mId;

        ProtoLogGroup(String tag, int id) {
            this(true, true, false, tag, id);
        }

        ProtoLogGroup(
                boolean enabled, boolean logToProto, boolean logToLogcat, String tag, int id) {
            this.mEnabled = enabled;
            this.mLogToProto = logToProto;
            this.mLogToLogcat = logToLogcat;
            this.mTag = tag;
            this.mId = id;
        }

        @Override
        public String name() {
            return mTag;
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
            return mId;
        }
    }
}
