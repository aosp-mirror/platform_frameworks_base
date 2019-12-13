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

package com.android.systemui.log;

import static junit.framework.Assert.assertEquals;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.DumpController;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class SysuiLogTest extends SysuiTestCase {
    private static final String TEST_ID = "TestLogger";
    private static final String TEST_MSG = "msg";
    private static final int MAX_LOGS = 5;

    @Mock
    private DumpController mDumpController;
    private SysuiLog<Event> mSysuiLog;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testLogDisabled_noLogsWritten() {
        mSysuiLog = new TestSysuiLog(mDumpController, TEST_ID, MAX_LOGS, false);
        assertEquals(null, mSysuiLog.mTimeline);

        mSysuiLog.log(createEvent(TEST_MSG));
        assertEquals(null, mSysuiLog.mTimeline);
    }

    @Test
    public void testLogEnabled_logWritten() {
        mSysuiLog = new TestSysuiLog(mDumpController, TEST_ID, MAX_LOGS, true);
        assertEquals(0, mSysuiLog.mTimeline.size());

        mSysuiLog.log(createEvent(TEST_MSG));
        assertEquals(1, mSysuiLog.mTimeline.size());
    }

    @Test
    public void testMaxLogs() {
        mSysuiLog = new TestSysuiLog(mDumpController, TEST_ID, MAX_LOGS, true);
        assertEquals(mSysuiLog.mTimeline.size(), 0);

        for (int i = 0; i < MAX_LOGS + 1; i++) {
            mSysuiLog.log(createEvent(TEST_MSG + i));
        }

        assertEquals(MAX_LOGS, mSysuiLog.mTimeline.size());

        // check the first message (msg0) was replaced with msg1:
        assertEquals(TEST_MSG + "1", mSysuiLog.mTimeline.getFirst().getMessage());
    }

    @Test
    public void testRecycleLogs() {
        // GIVEN a SysuiLog with one log
        mSysuiLog = new TestSysuiLog(mDumpController, TEST_ID, MAX_LOGS, true);
        Event e = createEvent(TEST_MSG); // msg
        mSysuiLog.log(e); // Logs: [msg]

        Event recycledEvent = null;
        // WHEN we add MAX_LOGS after the first log
        for (int i = 0; i < MAX_LOGS; i++) {
            recycledEvent = mSysuiLog.log(createEvent(TEST_MSG + i));
        }
        // Logs: [msg1, msg2, msg3, msg4]

        // THEN we see the recycledEvent is e
        assertEquals(e, recycledEvent);
    }

    private Event createEvent(String msg) {
        return new Event().init(msg);
    }

    public class TestSysuiLog extends SysuiLog<Event> {
        protected TestSysuiLog(DumpController dumpController, String id, int maxLogs,
                boolean enabled) {
            super(dumpController, id, maxLogs, enabled, false);
        }
    }
}
