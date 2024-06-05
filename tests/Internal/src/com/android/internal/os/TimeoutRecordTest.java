/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Intent;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TimeoutRecord}. */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class TimeoutRecordTest {

    @Test
    public void forBroadcastReceiver_returnsCorrectTimeoutRecord() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName("com.example.app", "com.example.app.ExampleClass"));

        TimeoutRecord record = TimeoutRecord.forBroadcastReceiver(intent);

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.BROADCAST_RECEIVER);
        assertEquals(record.mReason,
                "Broadcast of Intent { act=android.intent.action.MAIN cmp=com.example"
                        + ".app/.ExampleClass }");
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forBroadcastReceiver_withPackageAndClass_returnsCorrectTimeoutRecord() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        TimeoutRecord record = TimeoutRecord.forBroadcastReceiver(intent,
                "com.example.app", "com.example.app.ExampleClass");

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.BROADCAST_RECEIVER);
        assertEquals(record.mReason,
                "Broadcast of Intent { act=android.intent.action.MAIN cmp=com.example"
                        + ".app/.ExampleClass }");
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forBroadcastReceiver_withTimeoutDurationMs_returnsCorrectTimeoutRecord() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName("com.example.app", "com.example.app.ExampleClass"));

        TimeoutRecord record = TimeoutRecord.forBroadcastReceiver(intent, 1000L);

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.BROADCAST_RECEIVER);
        assertEquals(record.mReason,
                "Broadcast of Intent { act=android.intent.action.MAIN cmp=com.example"
                        + ".app/.ExampleClass }, waited 1000ms");
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forInputDispatchNoFocusedWindow_returnsCorrectTimeoutRecord() {
        TimeoutRecord record = TimeoutRecord.forInputDispatchNoFocusedWindow("Test ANR reason");

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW);
        assertEquals(record.mReason,
                "Test ANR reason");
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forInputDispatchWindowUnresponsive_returnsCorrectTimeoutRecord() {
        TimeoutRecord record = TimeoutRecord.forInputDispatchWindowUnresponsive("Test ANR reason");

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE);
        assertEquals(record.mReason, "Test ANR reason");
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forServiceExec_returnsCorrectTimeoutRecord() {
        TimeoutRecord record = TimeoutRecord.forServiceExec("com.app.MyService", 1000L);

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.SERVICE_EXEC);
        assertEquals(record.mReason, "executing service com.app.MyService, waited 1000ms");
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forServiceStartWithEndTime_returnsCorrectTimeoutRecord() {
        TimeoutRecord record = TimeoutRecord.forServiceStartWithEndTime("Test ANR reason", 1000L);

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.SERVICE_START);
        assertEquals(record.mReason, "Test ANR reason");
        assertEquals(record.mEndUptimeMillis, 1000L);
        assertTrue(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forContentProvider_returnsCorrectTimeoutRecord() {
        TimeoutRecord record = TimeoutRecord.forContentProvider("Test ANR reason");

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.CONTENT_PROVIDER);
        assertEquals(record.mReason, "Test ANR reason");
        assertFalse(record.mEndTakenBeforeLocks);
    }

    @Test
    public void forApp_returnsCorrectTimeoutRecord() {
        TimeoutRecord record = TimeoutRecord.forApp("Test ANR reason");

        assertNotNull(record);
        assertEquals(record.mKind, TimeoutRecord.TimeoutKind.APP_REGISTERED);
        assertEquals(record.mReason, "Test ANR reason");
        assertFalse(record.mEndTakenBeforeLocks);
    }
}
