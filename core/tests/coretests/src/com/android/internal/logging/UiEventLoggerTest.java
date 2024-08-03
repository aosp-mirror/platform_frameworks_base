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

package com.android.internal.logging;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.UiEventLoggerFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UiEventLoggerTest {
    private UiEventLoggerFake mLogger;

    private static final int TEST_EVENT_ID = 42;
    private static final int TEST_INSTANCE_ID = 21;

    private enum MyUiEventEnum implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Example event")
        TEST_EVENT(TEST_EVENT_ID);

        private final int mId;

        MyUiEventEnum(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private InstanceId TEST_INSTANCE = InstanceId.fakeInstanceId(TEST_INSTANCE_ID);

    @Before
    public void setUp() throws Exception {
        mLogger = new UiEventLoggerFake();
    }

    @Test
    public void testEmpty() throws Exception {
        assertThat(mLogger.numLogs()).isEqualTo(0);
    }

    @Test
    public void testSimple() throws Exception {
        mLogger.log(MyUiEventEnum.TEST_EVENT);
        assertThat(mLogger.numLogs()).isEqualTo(1);
        assertThat(mLogger.eventId(0)).isEqualTo(TEST_EVENT_ID);
    }

    @Test
    public void testWithInstance() throws Exception {
        mLogger.log(MyUiEventEnum.TEST_EVENT, TEST_INSTANCE);
        assertThat(mLogger.numLogs()).isEqualTo(1);
        assertThat(mLogger.eventId(0)).isEqualTo(TEST_EVENT_ID);
        assertThat(mLogger.get(0).instanceId.getId()).isEqualTo(TEST_INSTANCE_ID);
    }
}
