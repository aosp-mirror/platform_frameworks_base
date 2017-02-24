/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.util.DebugUtils.valueToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Function;

/**
 * Test class for {@link ActivityManagerService}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.server.am.ActivityManagerServiceTest frameworks-services
 *
 * or the following steps:
 *
 * Build: m FrameworksServicesTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -e class com.android.server.am.ActivityManagerServiceTest -w \
 *     com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActivityManagerServiceTest {
    private static final int TEST_UID = 111;

    @Test
    public void testIncrementProcStateSeqIfNeeded() {
        final ActivityManagerService ams = new ActivityManagerService();
        final UidRecord uidRec = new UidRecord(TEST_UID);

        assertEquals("Initially global seq counter should be 0", 0, ams.mProcStateSeqCounter);
        assertEquals("Initially seq counter in uidRecord should be 0", 0, uidRec.curProcStateSeq);

        // Uid state is not moving from background to foreground or vice versa.
        uidRec.setProcState = PROCESS_STATE_TOP;
        uidRec.curProcState = PROCESS_STATE_TOP;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(0, ams.mProcStateSeqCounter);
        assertEquals(0, uidRec.curProcStateSeq);

        // Uid state is moving from foreground to background.
        uidRec.curProcState = PROCESS_STATE_FOREGROUND_SERVICE;
        uidRec.setProcState = PROCESS_STATE_SERVICE;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(1, ams.mProcStateSeqCounter);
        assertEquals(1, uidRec.curProcStateSeq);

        // Explicitly setting the seq counter for more verification.
        ams.mProcStateSeqCounter = 42;

        // Uid state is not moving from background to foreground or vice versa.
        uidRec.setProcState = PROCESS_STATE_IMPORTANT_BACKGROUND;
        uidRec.curProcState = PROCESS_STATE_IMPORTANT_FOREGROUND;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(42, ams.mProcStateSeqCounter);
        assertEquals(1, uidRec.curProcStateSeq);

        // Uid state is moving from background to foreground.
        uidRec.setProcState = PROCESS_STATE_LAST_ACTIVITY;
        uidRec.curProcState = PROCESS_STATE_TOP;
        ams.incrementProcStateSeqIfNeeded(uidRec);
        assertEquals(43, ams.mProcStateSeqCounter);
        assertEquals(43, uidRec.curProcStateSeq);
    }

    @Test
    public void testShouldIncrementProcStateSeq() {
        final ActivityManagerService ams = new ActivityManagerService();
        final UidRecord uidRec = new UidRecord(TEST_UID);

        final String error1 = "Seq should be incremented: prevState: %s, curState: %s";
        final String error2 = "Seq should not be incremented: prevState: %s, curState: %s";
        Function<String, String> errorMsg = errorTemplate -> {
            return String.format(errorTemplate,
                    valueToString(ActivityManager.class, "PROCESS_STATE_", uidRec.setProcState),
                    valueToString(ActivityManager.class, "PROCESS_STATE_", uidRec.curProcState));
        };

        // No change in uid state
        uidRec.setProcState = PROCESS_STATE_RECEIVER;
        uidRec.curProcState = PROCESS_STATE_RECEIVER;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Foreground to foreground
        uidRec.setProcState = PROCESS_STATE_FOREGROUND_SERVICE;
        uidRec.curProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Background to background
        uidRec.setProcState = PROCESS_STATE_CACHED_ACTIVITY;
        uidRec.curProcState = PROCESS_STATE_CACHED_EMPTY;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Background to background
        uidRec.setProcState = PROCESS_STATE_NONEXISTENT;
        uidRec.curProcState = PROCESS_STATE_CACHED_ACTIVITY;
        assertFalse(errorMsg.apply(error2), ams.shouldIncrementProcStateSeq(uidRec));

        // Background to foreground
        uidRec.setProcState = PROCESS_STATE_SERVICE;
        uidRec.curProcState = PROCESS_STATE_FOREGROUND_SERVICE;
        assertTrue(errorMsg.apply(error1), ams.shouldIncrementProcStateSeq(uidRec));

        // Foreground to background
        uidRec.setProcState = PROCESS_STATE_TOP;
        uidRec.curProcState = PROCESS_STATE_LAST_ACTIVITY;
        assertTrue(errorMsg.apply(error1), ams.shouldIncrementProcStateSeq(uidRec));
    }
}