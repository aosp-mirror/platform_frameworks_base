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
    private static final int MAX_LOGS = 5;

    @Mock
    private DumpController mDumpController;
    private SysuiLog mSysuiLog;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testLogDisabled_noLogsWritten() {
        mSysuiLog = new SysuiLog(mDumpController, TEST_ID, MAX_LOGS, false);
        assertEquals(mSysuiLog.mTimeline, null);

        mSysuiLog.log(new Event("msg"));
        assertEquals(mSysuiLog.mTimeline, null);
    }

    @Test
    public void testLogEnabled_logWritten() {
        mSysuiLog = new SysuiLog(mDumpController, TEST_ID, MAX_LOGS, true);
        assertEquals(mSysuiLog.mTimeline.size(), 0);

        mSysuiLog.log(new Event("msg"));
        assertEquals(mSysuiLog.mTimeline.size(), 1);
    }

    @Test
    public void testMaxLogs() {
        mSysuiLog = new SysuiLog(mDumpController, TEST_ID, MAX_LOGS, true);
        assertEquals(mSysuiLog.mTimeline.size(), 0);

        final String msg = "msg";
        for (int i = 0; i < MAX_LOGS + 1; i++) {
            mSysuiLog.log(new Event(msg + i));
        }

        assertEquals(mSysuiLog.mTimeline.size(), MAX_LOGS);

        // check the first message (msg0) is deleted:
        assertEquals(mSysuiLog.mTimeline.getFirst().getMessage(), msg + "1");
    }
}
