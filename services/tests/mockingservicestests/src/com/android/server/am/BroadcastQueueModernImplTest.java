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

package com.android.server.am;

import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_ALARM;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_FOREGROUND;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_INTERACTIVE;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_MANIFEST;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_ORDERED;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_PRIORITIZED;
import static com.android.server.am.BroadcastProcessQueue.insertIntoRunnableList;
import static com.android.server.am.BroadcastProcessQueue.removeFromRunnableList;
import static com.android.server.am.BroadcastQueueTest.CLASS_BLUE;
import static com.android.server.am.BroadcastQueueTest.CLASS_GREEN;
import static com.android.server.am.BroadcastQueueTest.CLASS_RED;
import static com.android.server.am.BroadcastQueueTest.CLASS_YELLOW;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_BLUE;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_GREEN;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_RED;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_YELLOW;
import static com.android.server.am.BroadcastQueueTest.getUidForPackage;
import static com.android.server.am.BroadcastQueueTest.makeManifestReceiver;
import static com.android.server.am.BroadcastQueueTest.withPriority;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.appwidget.AppWidgetManager;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.IndentingPrintWriter;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class BroadcastQueueModernImplTest {
    private static final int TEST_UID = android.os.Process.FIRST_APPLICATION_UID;
    private static final int TEST_UID2 = android.os.Process.FIRST_APPLICATION_UID + 1;

    @Mock ActivityManagerService mAms;
    @Mock ProcessRecord mProcess;

    @Mock BroadcastProcessQueue mQueue1;
    @Mock BroadcastProcessQueue mQueue2;
    @Mock BroadcastProcessQueue mQueue3;
    @Mock BroadcastProcessQueue mQueue4;

    HandlerThread mHandlerThread;

    BroadcastConstants mConstants;
    BroadcastQueueModernImpl mImpl;

    BroadcastProcessQueue mHead;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();

        mConstants = new BroadcastConstants(Settings.Global.BROADCAST_FG_CONSTANTS);
        mConstants.DELAY_URGENT_MILLIS = -120_000;
        mConstants.DELAY_NORMAL_MILLIS = 10_000;
        mConstants.DELAY_CACHED_MILLIS = 120_000;

        final BroadcastSkipPolicy emptySkipPolicy = new BroadcastSkipPolicy(mAms) {
            public boolean shouldSkip(BroadcastRecord r, Object o) {
                // Ignored
                return false;
            }
            public String shouldSkipMessage(BroadcastRecord r, Object o) {
                // Ignored
                return null;
            }
            public boolean disallowBackgroundStart(BroadcastRecord r) {
                // Ignored
                return false;
            }
        };
        final BroadcastHistory emptyHistory = new BroadcastHistory(mConstants) {
            public void addBroadcastToHistoryLocked(BroadcastRecord original) {
                // Ignored
            }
        };


        mImpl = new BroadcastQueueModernImpl(mAms, mHandlerThread.getThreadHandler(),
            mConstants, mConstants, emptySkipPolicy, emptyHistory);

        doReturn(1L).when(mQueue1).getRunnableAt();
        doReturn(2L).when(mQueue2).getRunnableAt();
        doReturn(3L).when(mQueue3).getRunnableAt();
        doReturn(4L).when(mQueue4).getRunnableAt();
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
    }

    private static void assertOrphan(BroadcastProcessQueue queue) {
        assertNull(queue.runnableAtNext);
        assertNull(queue.runnableAtPrev);
    }

    private static void assertRunnableList(@NonNull List<BroadcastProcessQueue> expected,
            @NonNull BroadcastProcessQueue actualHead) {
        BroadcastProcessQueue test = actualHead;
        final int N = expected.size();
        for (int i = 0; i < N; i++) {
            final BroadcastProcessQueue expectedPrev = (i > 0) ? expected.get(i - 1) : null;
            final BroadcastProcessQueue expectedTest = expected.get(i);
            final BroadcastProcessQueue expectedNext = (i < N - 1) ? expected.get(i + 1) : null;

            assertEquals("prev", expectedPrev, test.runnableAtPrev);
            assertEquals("test", expectedTest, test);
            assertEquals("next", expectedNext, test.runnableAtNext);

            test = test.runnableAtNext;
        }
        if (N == 0) {
            assertNull(actualHead);
        }
    }

    private static Intent makeMockIntent() {
        return mock(Intent.class);
    }

    private static ResolveInfo makeMockManifestReceiver() {
        return mock(ResolveInfo.class);
    }

    private static BroadcastFilter makeMockRegisteredReceiver() {
        return mock(BroadcastFilter.class);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent) {
        return makeBroadcastRecord(intent, BroadcastOptions.makeBasic(),
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), false);
    }

    private BroadcastRecord makeOrderedBroadcastRecord(Intent intent) {
        return makeBroadcastRecord(intent, BroadcastOptions.makeBasic(),
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), true);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, List receivers) {
        return makeBroadcastRecord(intent, BroadcastOptions.makeBasic(), receivers, false);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, BroadcastOptions options) {
        return makeBroadcastRecord(intent, options,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), false);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, BroadcastOptions options,
            List receivers, boolean ordered) {
        return makeBroadcastRecord(intent, options, receivers, null, ordered);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, BroadcastOptions options,
            List receivers, IIntentReceiver resultTo, boolean ordered) {
        return new BroadcastRecord(mImpl, intent, mProcess, PACKAGE_RED, null, 21, 42, false, null,
                null, null, null, AppOpsManager.OP_NONE, options, receivers, null, resultTo,
                Activity.RESULT_OK, null, null, ordered, false, false, UserHandle.USER_SYSTEM,
                BackgroundStartPrivileges.NONE, false, null);
    }

    private void enqueueOrReplaceBroadcast(BroadcastProcessQueue queue,
            BroadcastRecord record, int recordIndex, long enqueueTime) {
        queue.enqueueOrReplaceBroadcast(record, recordIndex,
                null /* replacedBroadcastConsumer */, false);
        record.enqueueTime = enqueueTime;
    }

    @Test
    public void testRunnableList_Simple() {
        assertRunnableList(List.of(), mHead);

        mHead = insertIntoRunnableList(mHead, mQueue1);
        assertRunnableList(List.of(mQueue1), mHead);

        mHead = removeFromRunnableList(mHead, mQueue1);
        assertRunnableList(List.of(), mHead);
    }

    @Test
    public void testRunnableList_InsertLast() {
        mHead = insertIntoRunnableList(mHead, mQueue1);
        mHead = insertIntoRunnableList(mHead, mQueue2);
        mHead = insertIntoRunnableList(mHead, mQueue3);
        mHead = insertIntoRunnableList(mHead, mQueue4);
        assertRunnableList(List.of(mQueue1, mQueue2, mQueue3, mQueue4), mHead);
    }

    @Test
    public void testRunnableList_InsertFirst() {
        mHead = insertIntoRunnableList(mHead, mQueue4);
        mHead = insertIntoRunnableList(mHead, mQueue3);
        mHead = insertIntoRunnableList(mHead, mQueue2);
        mHead = insertIntoRunnableList(mHead, mQueue1);
        assertRunnableList(List.of(mQueue1, mQueue2, mQueue3, mQueue4), mHead);
    }

    @Test
    public void testRunnableList_InsertMiddle() {
        mHead = insertIntoRunnableList(mHead, mQueue1);
        mHead = insertIntoRunnableList(mHead, mQueue3);
        mHead = insertIntoRunnableList(mHead, mQueue2);
        assertRunnableList(List.of(mQueue1, mQueue2, mQueue3), mHead);
    }

    @Test
    public void testRunnableList_Remove() {
        mHead = insertIntoRunnableList(mHead, mQueue1);
        mHead = insertIntoRunnableList(mHead, mQueue2);
        mHead = insertIntoRunnableList(mHead, mQueue3);
        mHead = insertIntoRunnableList(mHead, mQueue4);

        mHead = removeFromRunnableList(mHead, mQueue3);
        assertRunnableList(List.of(mQueue1, mQueue2, mQueue4), mHead);

        mHead = removeFromRunnableList(mHead, mQueue1);
        assertRunnableList(List.of(mQueue2, mQueue4), mHead);

        mHead = removeFromRunnableList(mHead, mQueue4);
        assertRunnableList(List.of(mQueue2), mHead);

        mHead = removeFromRunnableList(mHead, mQueue2);
        assertRunnableList(List.of(), mHead);

        // Verify all links cleaned up during removal
        assertOrphan(mQueue1);
        assertOrphan(mQueue2);
        assertOrphan(mQueue3);
        assertOrphan(mQueue4);
    }

    @Test
    public void testProcessQueue_Complex() {
        BroadcastProcessQueue red = mImpl.getOrCreateProcessQueue(PACKAGE_RED, TEST_UID);
        BroadcastProcessQueue green = mImpl.getOrCreateProcessQueue(PACKAGE_GREEN, TEST_UID);
        BroadcastProcessQueue blue = mImpl.getOrCreateProcessQueue(PACKAGE_BLUE, TEST_UID);

        assertEquals(PACKAGE_RED, red.processName);
        assertEquals(PACKAGE_GREEN, green.processName);
        assertEquals(PACKAGE_BLUE, blue.processName);

        // Verify that removing middle queue works
        mImpl.removeProcessQueue(PACKAGE_GREEN, TEST_UID);
        assertEquals(red, mImpl.getProcessQueue(PACKAGE_RED, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_GREEN, TEST_UID));
        assertEquals(blue, mImpl.getProcessQueue(PACKAGE_BLUE, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_YELLOW, TEST_UID));

        // Verify that removing head queue works
        mImpl.removeProcessQueue(PACKAGE_RED, TEST_UID);
        assertNull(mImpl.getProcessQueue(PACKAGE_RED, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_GREEN, TEST_UID));
        assertEquals(blue, mImpl.getProcessQueue(PACKAGE_BLUE, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_YELLOW, TEST_UID));

        // Verify that removing last queue works
        mImpl.removeProcessQueue(PACKAGE_BLUE, TEST_UID);
        assertNull(mImpl.getProcessQueue(PACKAGE_RED, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_GREEN, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_BLUE, TEST_UID));
        assertNull(mImpl.getProcessQueue(PACKAGE_YELLOW, TEST_UID));

        // Verify that removing missing doesn't crash
        mImpl.removeProcessQueue(PACKAGE_YELLOW, TEST_UID);

        // Verify that we can start all over again safely
        BroadcastProcessQueue yellow = mImpl.getOrCreateProcessQueue(PACKAGE_YELLOW, TEST_UID);
        assertEquals(yellow, mImpl.getProcessQueue(PACKAGE_YELLOW, TEST_UID));
    }

    /**
     * Empty queue isn't runnable.
     */
    @Test
    public void testRunnableAt_Empty() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        assertFalse(queue.isRunnable());
        assertEquals(Long.MAX_VALUE, queue.getRunnableAt());
        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());
    }

    /**
     * Queue with a "normal" broadcast is runnable at different times depending
     * on process cached state; when cached it's delayed by some amount.
     */
    @Test
    public void testRunnableAt_Normal() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane,
                List.of(makeMockRegisteredReceiver()));
        queue.enqueueOrReplaceBroadcast(airplaneRecord, 0,
                null /* replacedBroadcastConsumer */, false);

        queue.setProcessCached(false);
        final long notCachedRunnableAt = queue.getRunnableAt();
        queue.setProcessCached(true);
        final long cachedRunnableAt = queue.getRunnableAt();
        assertThat(cachedRunnableAt).isGreaterThan(notCachedRunnableAt);
        assertEquals(ProcessList.SCHED_GROUP_BACKGROUND, queue.getPreferredSchedulingGroupLocked());
    }

    /**
     * Queue with foreground broadcast is always runnable immediately,
     * regardless of process cached state.
     */
    @Test
    public void testRunnableAt_Foreground() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        // enqueue a bg-priority broadcast then a fg-priority one
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        final BroadcastRecord timezoneRecord = makeBroadcastRecord(timezone);
        queue.enqueueOrReplaceBroadcast(timezoneRecord, 0,
                null /* replacedBroadcastConsumer */, false);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane);
        queue.enqueueOrReplaceBroadcast(airplaneRecord, 0,
                null /* replacedBroadcastConsumer */, false);

        // verify that:
        // (a) the queue is immediately runnable by existence of a fg-priority broadcast
        // (b) the next one up is the fg-priority broadcast despite its later enqueue time
        queue.setProcessCached(false);
        assertTrue(queue.isRunnable());
        assertThat(queue.getRunnableAt()).isAtMost(airplaneRecord.enqueueClockTime);
        assertEquals(ProcessList.SCHED_GROUP_DEFAULT, queue.getPreferredSchedulingGroupLocked());
        assertEquals(queue.peekNextBroadcastRecord(), airplaneRecord);

        queue.setProcessCached(true);
        assertTrue(queue.isRunnable());
        assertThat(queue.getRunnableAt()).isAtMost(airplaneRecord.enqueueClockTime);
        assertEquals(ProcessList.SCHED_GROUP_DEFAULT, queue.getPreferredSchedulingGroupLocked());
        assertEquals(queue.peekNextBroadcastRecord(), airplaneRecord);
    }

    /**
     * Queue with ordered broadcast is runnable only once we've made enough
     * progress on earlier blocking items.
     */
    @Test
    public void testRunnableAt_Ordered() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane, null,
                List.of(withPriority(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN), 10),
                        withPriority(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN), 0)), true);
        queue.enqueueOrReplaceBroadcast(airplaneRecord, 1,
                null /* replacedBroadcastConsumer */, false);

        assertFalse(queue.isRunnable());
        assertEquals(BroadcastProcessQueue.REASON_BLOCKED, queue.getRunnableAtReason());

        // Bumping past barrier makes us now runnable
        airplaneRecord.terminalCount++;
        queue.invalidateRunnableAt();
        assertTrue(queue.isRunnable());
        assertNotEquals(BroadcastProcessQueue.REASON_BLOCKED, queue.getRunnableAtReason());
    }

    /**
     * Queue with too many pending broadcasts is runnable.
     */
    @Test
    public void testRunnableAt_Huge() {
        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane,
                List.of(makeMockRegisteredReceiver()));
        queue.enqueueOrReplaceBroadcast(airplaneRecord, 0,
                null /* replacedBroadcastConsumer */, false);

        mConstants.MAX_PENDING_BROADCASTS = 128;
        queue.invalidateRunnableAt();
        assertThat(queue.getRunnableAt()).isGreaterThan(airplaneRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());

        mConstants.MAX_PENDING_BROADCASTS = 1;
        queue.invalidateRunnableAt();
        assertThat(queue.getRunnableAt()).isAtMost(airplaneRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_MAX_PENDING, queue.getRunnableAtReason());
    }

    /**
     * Verify that a cached process that would normally be delayed becomes
     * immediately runnable when the given broadcast is enqueued.
     */
    private void doRunnableAt_Cached(BroadcastRecord testRecord, int testRunnableAtReason) {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        queue.setProcessCached(true);

        final BroadcastRecord lazyRecord = makeBroadcastRecord(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED),
                List.of(makeMockRegisteredReceiver()));

        queue.enqueueOrReplaceBroadcast(lazyRecord, 0,
                null /* replacedBroadcastConsumer */, false);
        assertThat(queue.getRunnableAt()).isGreaterThan(lazyRecord.enqueueTime);
        assertThat(queue.getRunnableAtReason()).isNotEqualTo(testRunnableAtReason);

        queue.enqueueOrReplaceBroadcast(testRecord, 0,
                null /* replacedBroadcastConsumer */, false);
        assertThat(queue.getRunnableAt()).isAtMost(testRecord.enqueueTime);
        assertThat(queue.getRunnableAtReason()).isEqualTo(testRunnableAtReason);
    }

    @Test
    public void testRunnableAt_Cached_Manifest() {
        doRunnableAt_Cached(makeBroadcastRecord(makeMockIntent(), null,
                List.of(makeMockManifestReceiver()), null, false), REASON_CONTAINS_MANIFEST);
    }

    @Test
    public void testRunnableAt_Cached_Ordered() {
        doRunnableAt_Cached(makeBroadcastRecord(makeMockIntent(), null,
                List.of(makeMockRegisteredReceiver()), null, true), REASON_CONTAINS_ORDERED);
    }

    @Test
    public void testRunnableAt_Cached_Foreground() {
        final Intent foregroundIntent = new Intent();
        foregroundIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        doRunnableAt_Cached(makeBroadcastRecord(foregroundIntent, null,
                List.of(makeMockRegisteredReceiver()), null, false), REASON_CONTAINS_FOREGROUND);
    }

    @Test
    public void testRunnableAt_Cached_Interactive() {
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setInteractive(true);
        doRunnableAt_Cached(makeBroadcastRecord(makeMockIntent(), options,
                List.of(makeMockRegisteredReceiver()), null, false), REASON_CONTAINS_INTERACTIVE);
    }

    @Test
    public void testRunnableAt_Cached_Alarm() {
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setAlarmBroadcast(true);
        doRunnableAt_Cached(makeBroadcastRecord(makeMockIntent(), options,
                List.of(makeMockRegisteredReceiver()), null, false), REASON_CONTAINS_ALARM);
    }

    @Test
    public void testRunnableAt_Cached_Prioritized() {
        final List receivers = List.of(
                withPriority(makeManifestReceiver(PACKAGE_RED, PACKAGE_RED), 10),
                withPriority(makeManifestReceiver(PACKAGE_GREEN, PACKAGE_GREEN), -10));
        doRunnableAt_Cached(makeBroadcastRecord(makeMockIntent(), null,
                receivers, null, false), REASON_CONTAINS_PRIORITIZED);
    }

    /**
     * Confirm that we always prefer running pending items marked as "urgent",
     * then "normal", then "offload", dispatching by the relative ordering
     * within each of those clustering groups.
     */
    @Test
    public void testMakeActiveNextPending() {
        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        queue.enqueueOrReplaceBroadcast(
                makeBroadcastRecord(new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0,
                null /* replacedBroadcastConsumer */, false);
        queue.enqueueOrReplaceBroadcast(
                makeBroadcastRecord(new Intent(Intent.ACTION_TIMEZONE_CHANGED)), 0,
                null /* replacedBroadcastConsumer */, false);
        queue.enqueueOrReplaceBroadcast(
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0,
                null /* replacedBroadcastConsumer */, false);
        queue.enqueueOrReplaceBroadcast(
                makeBroadcastRecord(new Intent(Intent.ACTION_ALARM_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0,
                null /* replacedBroadcastConsumer */, false);
        queue.enqueueOrReplaceBroadcast(
                makeBroadcastRecord(new Intent(Intent.ACTION_TIME_TICK)), 0,
                null /* replacedBroadcastConsumer */, false);
        queue.enqueueOrReplaceBroadcast(
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCALE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0,
                null /* replacedBroadcastConsumer */, false);

        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCKED_BOOT_COMPLETED, queue.getActive().intent.getAction());

        // To maximize test coverage, dump current state; we're not worried
        // about the actual output, just that we don't crash
        queue.getActive().setDeliveryState(0, BroadcastRecord.DELIVERY_SCHEDULED);
        queue.dumpLocked(SystemClock.uptimeMillis(),
                new IndentingPrintWriter(new PrintWriter(new ByteArrayOutputStream())));

        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCALE_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIME_TICK, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_AIRPLANE_MODE_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_ALARM_CHANGED, queue.getActive().intent.getAction());
        assertTrue(queue.isEmpty());
    }

    /**
     * Verify that we don't let urgent broadcasts starve delivery of non-urgent
     */
    @Test
    public void testUrgentStarvation() {
        final BroadcastOptions optInteractive = BroadcastOptions.makeBasic();
        optInteractive.setInteractive(true);

        mConstants.MAX_CONSECUTIVE_URGENT_DISPATCHES = 2;
        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        long timeCounter = 100;

        // mix of broadcasts, with more than 2 fg/urgent
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIMEZONE_CHANGED)),
                        0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_ALARM_CHANGED)),
                        0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIME_TICK)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCALE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_APPLICATION_PREFERENCES),
                        optInteractive), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                        optInteractive), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_INPUT_METHOD_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_NEW_OUTGOING_CALL),
                        optInteractive), 0, timeCounter++);

        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCALE_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_APPLICATION_PREFERENCES, queue.getActive().intent.getAction());
        // after MAX_CONSECUTIVE_URGENT_DISPATCHES expect an ordinary one next
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, queue.getActive().intent.getAction());
        // and then back to prioritizing urgent ones
        queue.makeActiveNextPending();
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_INPUT_METHOD_CHANGED, queue.getActive().intent.getAction());
        // verify the reset-count-then-resume worked too
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_ALARM_CHANGED, queue.getActive().intent.getAction());
    }

    /**
     * Verify that offload broadcasts are not starved because of broadcasts in higher priority
     * queues.
     */
    @Test
    public void testOffloadStarvation() {
        final BroadcastOptions optInteractive = BroadcastOptions.makeBasic();
        optInteractive.setInteractive(true);

        mConstants.MAX_CONSECUTIVE_URGENT_DISPATCHES = 1;
        mConstants.MAX_CONSECUTIVE_NORMAL_DISPATCHES = 2;
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        long timeCounter = 100;

        // mix of broadcasts, with more than 2 normal
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIMEZONE_CHANGED)),
                        0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_PACKAGE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_ALARM_CHANGED)),
                0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIME_TICK)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCALE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_APPLICATION_PREFERENCES),
                        optInteractive), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                        optInteractive), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_INPUT_METHOD_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_NEW_OUTGOING_CALL),
                        optInteractive), 0, timeCounter++);

        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCALE_CHANGED, queue.getActive().intent.getAction());
        // after MAX_CONSECUTIVE_URGENT_DISPATCHES expect an ordinary one next
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, queue.getActive().intent.getAction());
        // and then back to prioritizing urgent ones
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_APPLICATION_PREFERENCES, queue.getActive().intent.getAction());
        // after MAX_CONSECUTIVE_URGENT_DISPATCHES, again an ordinary one next
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_ALARM_CHANGED, queue.getActive().intent.getAction());
        // and then back to prioritizing urgent ones
        queue.makeActiveNextPending();
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                queue.getActive().intent.getAction());
        // after MAX_CONSECUTIVE_URGENT_DISPATCHES and MAX_CONSECUTIVE_NORMAL_DISPATCHES,
        // expect an offload one
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_BOOT_COMPLETED, queue.getActive().intent.getAction());
        // and then back to prioritizing urgent ones
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_INPUT_METHOD_CHANGED, queue.getActive().intent.getAction());
    }

    /**
     * Verify that BroadcastProcessQueue#setPrioritizeEarliest() works as expected.
     */
    @Test
    public void testPrioritizeEarliest() {
        final BroadcastOptions optInteractive = BroadcastOptions.makeBasic();
        optInteractive.setInteractive(true);

        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        queue.setPrioritizeEarliest(true);
        long timeCounter = 100;

        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIMEZONE_CHANGED)),
                        0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_PACKAGE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_ALARM_CHANGED)),
                        0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIME_TICK)),
                        0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCALE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                        optInteractive), 0, timeCounter++);

        // When we mark BroadcastProcessQueue to prioritize earliest, we should
        // expect to dispatch broadcasts in the order they were enqueued
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_BOOT_COMPLETED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, queue.getActive().intent.getAction());
        // after MAX_CONSECUTIVE_URGENT_DISPATCHES expect an ordinary one next
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_PACKAGE_CHANGED, queue.getActive().intent.getAction());
        // and then back to prioritizing urgent ones
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_ALARM_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIME_TICK, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCALE_CHANGED, queue.getActive().intent.getAction());
        // verify the reset-count-then-resume worked too
        queue.makeActiveNextPending();
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                queue.getActive().intent.getAction());
    }

    /**
     * Verify that sending a broadcast that removes any matching pending
     * broadcasts is applied as expected.
     */
    @Test
    public void testRemoveMatchingFilter() {
        final Intent screenOn = new Intent(Intent.ACTION_SCREEN_ON);
        final BroadcastOptions optionsOn = BroadcastOptions.makeBasic();
        optionsOn.setRemoveMatchingFilter(new IntentFilter(Intent.ACTION_SCREEN_OFF));

        final Intent screenOff = new Intent(Intent.ACTION_SCREEN_OFF);
        final BroadcastOptions optionsOff = BroadcastOptions.makeBasic();
        optionsOff.setRemoveMatchingFilter(new IntentFilter(Intent.ACTION_SCREEN_ON));

        // Halt all processing so that we get a consistent view
        mHandlerThread.getLooper().getQueue().postSyncBarrier();

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, optionsOn));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, optionsOff));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, optionsOn));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, optionsOff));

        // While we're here, give our health check some test coverage
        mImpl.checkHealthLocked();

        // Marching through the queue we should only have one SCREEN_OFF
        // broadcast, since that's the last state we dispatched
        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_SCREEN_OFF, queue.getActive().intent.getAction());
        assertTrue(queue.isEmpty());
    }

    /**
     * Verify that sending a broadcast with DELIVERY_GROUP_POLICY_MOST_RECENT works as expected.
     */
    @Test
    public void testDeliveryGroupPolicy_mostRecent() {
        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastOptions optionsTimeTick = BroadcastOptions.makeBasic();
        optionsTimeTick.setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);

        final Intent musicVolumeChanged = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
        musicVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_MUSIC);
        final BroadcastOptions optionsMusicVolumeChanged = BroadcastOptions.makeBasic();
        optionsMusicVolumeChanged.setDeliveryGroupPolicy(
                BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
        optionsMusicVolumeChanged.setDeliveryGroupMatchingKey("audio",
                String.valueOf(AudioManager.STREAM_MUSIC));

        final Intent alarmVolumeChanged = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
        alarmVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_ALARM);
        final BroadcastOptions optionsAlarmVolumeChanged = BroadcastOptions.makeBasic();
        optionsAlarmVolumeChanged.setDeliveryGroupPolicy(
                BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
        optionsAlarmVolumeChanged.setDeliveryGroupMatchingKey("audio",
                String.valueOf(AudioManager.STREAM_ALARM));

        // Halt all processing so that we get a consistent view
        mHandlerThread.getLooper().getQueue().postSyncBarrier();

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));

        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        // Verify that the older musicVolumeChanged has been removed.
        verifyPendingRecords(queue,
                List.of(timeTick, alarmVolumeChanged, musicVolumeChanged));

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        // Verify that the older alarmVolumeChanged has been removed.
        verifyPendingRecords(queue,
                List.of(timeTick, musicVolumeChanged, alarmVolumeChanged));

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        // Verify that the older timeTick has been removed.
        verifyPendingRecords(queue,
                List.of(musicVolumeChanged, alarmVolumeChanged, timeTick));
    }

    /**
     * Verify that sending a broadcast with DELIVERY_GROUP_POLICY_MERGED works as expected.
     */
    @Test
    public void testDeliveryGroupPolicy_merged() {
        final BundleMerger extrasMerger = new BundleMerger();
        extrasMerger.setMergeStrategy(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                BundleMerger.STRATEGY_ARRAY_APPEND);

        final Intent packageChangedForUid = createPackageChangedIntent(TEST_UID,
                List.of("com.testuid.component1"));
        final BroadcastOptions optionsPackageChangedForUid = BroadcastOptions.makeBasic();
        optionsPackageChangedForUid.setDeliveryGroupPolicy(
                BroadcastOptions.DELIVERY_GROUP_POLICY_MERGED);
        optionsPackageChangedForUid.setDeliveryGroupMatchingKey("package",
                String.valueOf(TEST_UID));
        optionsPackageChangedForUid.setDeliveryGroupExtrasMerger(extrasMerger);

        final Intent secondPackageChangedForUid = createPackageChangedIntent(TEST_UID,
                List.of("com.testuid.component2", "com.testuid.component3"));

        final Intent packageChangedForUid2 = createPackageChangedIntent(TEST_UID2,
                List.of("com.testuid2.component1"));
        final BroadcastOptions optionsPackageChangedForUid2 = BroadcastOptions.makeBasic();
        optionsPackageChangedForUid.setDeliveryGroupPolicy(
                BroadcastOptions.DELIVERY_GROUP_POLICY_MERGED);
        optionsPackageChangedForUid.setDeliveryGroupMatchingKey("package",
                String.valueOf(TEST_UID2));
        optionsPackageChangedForUid.setDeliveryGroupExtrasMerger(extrasMerger);

        // Halt all processing so that we get a consistent view
        mHandlerThread.getLooper().getQueue().postSyncBarrier();

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(packageChangedForUid,
                optionsPackageChangedForUid));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(packageChangedForUid2,
                optionsPackageChangedForUid2));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(secondPackageChangedForUid,
                optionsPackageChangedForUid));

        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final Intent expectedPackageChangedForUid = createPackageChangedIntent(TEST_UID,
                List.of("com.testuid.component2", "com.testuid.component3",
                        "com.testuid.component1"));
        // Verify that packageChangedForUid and secondPackageChangedForUid broadcasts
        // have been merged.
        verifyPendingRecords(queue, List.of(packageChangedForUid2, expectedPackageChangedForUid));
    }

    @Test
    public void testDeliveryGroupPolicy_matchingFilter() {
        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastOptions optionsTimeTick = BroadcastOptions.makeBasic();
        optionsTimeTick.setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);

        final Intent musicVolumeChanged = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
        musicVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_MUSIC);
        final IntentFilter filterMusicVolumeChanged = new IntentFilter(
                AudioManager.VOLUME_CHANGED_ACTION);
        filterMusicVolumeChanged.addExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_MUSIC);
        final BroadcastOptions optionsMusicVolumeChanged = BroadcastOptions.makeBasic();
        optionsMusicVolumeChanged.setDeliveryGroupPolicy(
                BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
        optionsMusicVolumeChanged.setDeliveryGroupMatchingFilter(filterMusicVolumeChanged);

        final Intent alarmVolumeChanged = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
        alarmVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_ALARM);
        final IntentFilter filterAlarmVolumeChanged = new IntentFilter(
                AudioManager.VOLUME_CHANGED_ACTION);
        filterAlarmVolumeChanged.addExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_ALARM);
        final BroadcastOptions optionsAlarmVolumeChanged = BroadcastOptions.makeBasic();
        optionsAlarmVolumeChanged.setDeliveryGroupPolicy(
                BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
        optionsAlarmVolumeChanged.setDeliveryGroupMatchingFilter(filterAlarmVolumeChanged);

        // Halt all processing so that we get a consistent view
        mHandlerThread.getLooper().getQueue().postSyncBarrier();

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));

        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        // Verify that the older musicVolumeChanged has been removed.
        verifyPendingRecords(queue,
                List.of(timeTick, alarmVolumeChanged, musicVolumeChanged));

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        // Verify that the older alarmVolumeChanged has been removed.
        verifyPendingRecords(queue,
                List.of(timeTick, musicVolumeChanged, alarmVolumeChanged));

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(musicVolumeChanged,
                optionsMusicVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(alarmVolumeChanged,
                optionsAlarmVolumeChanged));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        // Verify that the older timeTick has been removed.
        verifyPendingRecords(queue,
                List.of(musicVolumeChanged, alarmVolumeChanged, timeTick));
    }

    @Test
    public void testVerifyEnqueuedTime_withReplacePending() {
        final Intent userPresent = new Intent(Intent.ACTION_USER_PRESENT);
        userPresent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

        // Halt all processing so that we get a consistent view
        mHandlerThread.getLooper().getQueue().postSyncBarrier();

        final BroadcastRecord userPresentRecord1 = makeBroadcastRecord(userPresent);
        final BroadcastRecord userPresentRecord2 = makeBroadcastRecord(userPresent);

        mImpl.enqueueBroadcastLocked(userPresentRecord1);
        mImpl.enqueueBroadcastLocked(userPresentRecord2);

        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        queue.makeActiveNextPending();

        // Verify that there is only one record pending and its enqueueTime is
        // same as that of userPresentRecord1.
        final BroadcastRecord activeRecord = queue.getActive();
        assertEquals(userPresentRecord1.enqueueTime, activeRecord.enqueueTime);
        assertEquals(userPresentRecord1.enqueueRealTime, activeRecord.enqueueRealTime);
        assertEquals(userPresentRecord1.enqueueClockTime, activeRecord.enqueueClockTime);
        assertThat(activeRecord.originalEnqueueClockTime)
                .isGreaterThan(activeRecord.enqueueClockTime);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void testCleanupDisabledPackageReceiversLocked() {
        final Intent userPresent = new Intent(Intent.ACTION_USER_PRESENT);
        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);

        final BroadcastRecord record1 = makeBroadcastRecord(userPresent, List.of(
                makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                makeManifestReceiver(PACKAGE_RED, CLASS_BLUE),
                makeManifestReceiver(PACKAGE_YELLOW, CLASS_RED),
                makeManifestReceiver(PACKAGE_BLUE, CLASS_GREEN)
        ));
        final BroadcastRecord record2 = makeBroadcastRecord(timeTick, List.of(
                makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                makeManifestReceiver(PACKAGE_RED, CLASS_RED),
                makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW)
        ));

        // Halt all processing so that we get a consistent view
        mHandlerThread.getLooper().getQueue().postSyncBarrier();
        mImpl.enqueueBroadcastLocked(record1);
        mImpl.enqueueBroadcastLocked(record2);

        mImpl.cleanupDisabledPackageReceiversLocked(null, null, UserHandle.USER_SYSTEM);

        // Verify that all receivers have been marked as "skipped".
        for (BroadcastRecord record : new BroadcastRecord[] {record1, record2}) {
            for (int i = 0; i < record.receivers.size(); ++i) {
                final String errMsg = "Unexpected delivery state for record:" + record
                        + "; receiver=" + record.receivers.get(i);
                assertEquals(errMsg, BroadcastRecord.DELIVERY_SKIPPED, record.getDeliveryState(i));
            }
        }
    }

    private Intent createPackageChangedIntent(int uid, List<String> componentNameList) {
        final Intent packageChangedIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        packageChangedIntent.putExtra(Intent.EXTRA_UID, uid);
        packageChangedIntent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                componentNameList.toArray());
        return packageChangedIntent;
    }

    private void verifyPendingRecords(BroadcastProcessQueue queue,
            List<Intent> intents) {
        for (int i = 0; i < intents.size(); i++) {
            queue.makeActiveNextPending();
            final Intent actualIntent = queue.getActive().intent;
            final Intent expectedIntent = intents.get(i);
            final String errMsg = "actual=" + actualIntent + ", expected=" + expectedIntent
                    + ", actual_extras=" + actualIntent.getExtras()
                    + ", expected_extras=" + expectedIntent.getExtras();
            assertTrue(errMsg, actualIntent.filterEquals(expectedIntent));
            assertBundleEquals(expectedIntent.getExtras(), actualIntent.getExtras());
        }
        assertTrue(queue.isEmpty());
    }

    private void assertBundleEquals(Bundle expected, Bundle actual) {
        final String errMsg = "expected=" + expected + ", actual=" + actual;
        if (expected == actual) {
            return;
        } else if (expected == null || actual == null) {
            fail(errMsg);
        }
        if (!expected.keySet().equals(actual.keySet())) {
            fail(errMsg);
        }
        for (String key : expected.keySet()) {
            final Object expectedValue = expected.get(key);
            final Object actualValue = actual.get(key);
            if (expectedValue == actualValue) {
                continue;
            } else if (expectedValue == null || actualValue == null) {
                fail(errMsg);
            }
            assertEquals(errMsg, expectedValue.getClass(), actualValue.getClass());
            if (expectedValue.getClass().isArray()) {
                assertEquals(errMsg, Array.getLength(expectedValue), Array.getLength(actualValue));
                for (int i = 0; i < Array.getLength(expectedValue); ++i) {
                    assertEquals(errMsg, Array.get(expectedValue, i), Array.get(actualValue, i));
                }
            } else if (expectedValue instanceof ArrayList) {
                final ArrayList<?> expectedList = (ArrayList<?>) expectedValue;
                final ArrayList<?> actualList = (ArrayList<?>) actualValue;
                assertEquals(errMsg, expectedList.size(), actualList.size());
                for (int i = 0; i < expectedList.size(); ++i) {
                    assertEquals(errMsg, expectedList.get(i), actualList.get(i));
                }
            } else {
                assertEquals(errMsg, expectedValue, actualValue);
            }
        }
    }
}
