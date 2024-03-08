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

import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST;
import static com.android.server.am.ActivityManagerDebugConfig.LOG_WRITER_INFO;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_ALARM;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_FOREGROUND;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_INTERACTIVE;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_MANIFEST;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_ORDERED;
import static com.android.server.am.BroadcastProcessQueue.REASON_CONTAINS_PRIORITIZED;
import static com.android.server.am.BroadcastProcessQueue.insertIntoRunnableList;
import static com.android.server.am.BroadcastProcessQueue.removeFromRunnableList;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.appwidget.AppWidgetManager;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.DropBoxManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SmallTest
public final class BroadcastQueueModernImplTest extends BaseBroadcastQueueTest {
    private static final String TAG = "BroadcastQueueModernImplTest";

    private static final int TEST_UID = android.os.Process.FIRST_APPLICATION_UID;
    private static final int TEST_UID2 = android.os.Process.FIRST_APPLICATION_UID + 1;

    ProcessRecord mProcess;

    @Mock BroadcastProcessQueue mQueue1;
    @Mock BroadcastProcessQueue mQueue2;
    @Mock BroadcastProcessQueue mQueue3;
    @Mock BroadcastProcessQueue mQueue4;

    BroadcastQueueModernImpl mImpl;

    BroadcastProcessQueue mHead;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mConstants.DELAY_URGENT_MILLIS = -120_000;
        mConstants.DELAY_NORMAL_MILLIS = 10_000;
        mConstants.DELAY_CACHED_MILLIS = 120_000;

        mImpl = new BroadcastQueueModernImpl(mAms, mHandlerThread.getThreadHandler(),
                mConstants, mConstants, mSkipPolicy, mEmptyHistory);
        mAms.setBroadcastQueueForTest(mImpl);

        doReturn(1L).when(mQueue1).getRunnableAt();
        doReturn(2L).when(mQueue2).getRunnableAt();
        doReturn(3L).when(mQueue3).getRunnableAt();
        doReturn(4L).when(mQueue4).getRunnableAt();

        final ApplicationInfo ai = makeApplicationInfo(PACKAGE_ORANGE);
        mProcess = spy(new ProcessRecord(mAms, ai, ai.processName, ai.uid));
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Override
    public String getTag() {
        return TAG;
    }

    /**
     * Un-pause our handler to process pending events, wait for our queue to go
     * idle, and then re-pause the handler.
     */
    private void waitForIdle() throws Exception {
        mLooper.release();
        mImpl.waitForIdle(LOG_WRITER_INFO);
        mLooper = Objects.requireNonNull(InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mHandlerThread.getLooper()));
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
        return new BroadcastRecord(mImpl, intent, mProcess, PACKAGE_RED, null, 21, TEST_UID, false,
                null, null, null, null, AppOpsManager.OP_NONE, options, receivers, null, resultTo,
                Activity.RESULT_OK, null, null, ordered, false, false, UserHandle.USER_SYSTEM,
                BackgroundStartPrivileges.NONE, false, null, PROCESS_STATE_UNKNOWN);
    }

    private void enqueueOrReplaceBroadcast(BroadcastProcessQueue queue,
            BroadcastRecord record, int recordIndex) {
        enqueueOrReplaceBroadcast(queue, record, recordIndex, 42_000_000L);
    }

    private void enqueueOrReplaceBroadcast(BroadcastProcessQueue queue,
            BroadcastRecord record, int recordIndex, long enqueueTime) {
        record.enqueueTime = enqueueTime;
        record.enqueueRealTime = enqueueTime;
        record.enqueueClockTime = enqueueTime;
        queue.enqueueOrReplaceBroadcast(record, recordIndex, (r, i) -> {
            throw new UnsupportedOperationException();
        });
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
    public void testRunnableList_sameRunnableAt() {
        doReturn(2L).when(mQueue1).getRunnableAt();
        doReturn(2L).when(mQueue2).getRunnableAt();
        doReturn(2L).when(mQueue3).getRunnableAt();
        doReturn(2L).when(mQueue4).getRunnableAt();

        mHead = insertIntoRunnableList(mHead, mQueue1);
        mHead = insertIntoRunnableList(mHead, mQueue2);
        mHead = insertIntoRunnableList(mHead, mQueue3);
        mHead = insertIntoRunnableList(mHead, mQueue4);
        assertRunnableList(List.of(mQueue1, mQueue2, mQueue3, mQueue4), mHead);
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
     * Queue with a "normal" and "deferrable" broadcast is runnable at different times depending
     * on process cached state; when cached it's delayed indefinitely.
     */
    @Test
    public void testRunnableAt_Normal_Deferrable() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastOptions options = BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane, options,
                List.of(makeMockRegisteredReceiver()), false);
        enqueueOrReplaceBroadcast(queue, airplaneRecord, 0);

        queue.setProcessAndUidState(mProcess, false, false);
        final long notCachedRunnableAt = queue.getRunnableAt();
        queue.setProcessAndUidState(mProcess, false, true);
        final long cachedRunnableAt = queue.getRunnableAt();
        assertThat(cachedRunnableAt).isGreaterThan(notCachedRunnableAt);
        assertFalse(queue.isRunnable());
        assertEquals(BroadcastProcessQueue.REASON_CACHED_INFINITE_DEFER,
                queue.getRunnableAtReason());
        assertTrue(queue.shouldBeDeferred());
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
        final BroadcastOptions options = BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane, options,
                List.of(makeMockRegisteredReceiver()), false);
        enqueueOrReplaceBroadcast(queue, airplaneRecord, 0);

        queue.setProcessAndUidState(mProcess, false, false);
        final long notCachedRunnableAt = queue.getRunnableAt();
        queue.setProcessAndUidState(mProcess, false, true);
        final long cachedRunnableAt = queue.getRunnableAt();
        assertThat(cachedRunnableAt).isGreaterThan(notCachedRunnableAt);
        assertTrue(queue.isRunnable());
        assertEquals(BroadcastProcessQueue.REASON_CACHED, queue.getRunnableAtReason());
        assertTrue(queue.shouldBeDeferred());
        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());
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
        enqueueOrReplaceBroadcast(queue, timezoneRecord, 0);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane);
        enqueueOrReplaceBroadcast(queue, airplaneRecord, 0);

        // verify that:
        // (a) the queue is immediately runnable by existence of a fg-priority broadcast
        // (b) the next one up is the fg-priority broadcast despite its later enqueue time
        queue.setProcessAndUidState(null, false, false);
        assertTrue(queue.isRunnable());
        assertThat(queue.getRunnableAt()).isAtMost(airplaneRecord.enqueueClockTime);
        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());
        assertEquals(queue.peekNextBroadcastRecord(), airplaneRecord);

        queue.setProcessAndUidState(null, false, true);
        assertTrue(queue.isRunnable());
        assertThat(queue.getRunnableAt()).isAtMost(airplaneRecord.enqueueClockTime);
        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());
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
        enqueueOrReplaceBroadcast(queue, airplaneRecord, 1);

        assertFalse(queue.isRunnable());
        assertEquals(BroadcastProcessQueue.REASON_BLOCKED, queue.getRunnableAtReason());

        // Bumping past barrier makes us now runnable
        airplaneRecord.setDeliveryState(0, BroadcastRecord.DELIVERY_DELIVERED,
                "testRunnableAt_Ordered");
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
        enqueueOrReplaceBroadcast(queue, airplaneRecord, 0);

        mConstants.MAX_PENDING_BROADCASTS = 128;
        queue.invalidateRunnableAt();
        assertThat(queue.getRunnableAt()).isGreaterThan(airplaneRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());

        mConstants.MAX_PENDING_BROADCASTS = 1;
        queue.invalidateRunnableAt();
        assertThat(queue.getRunnableAt()).isAtMost(airplaneRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_MAX_PENDING, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());
    }

    @Test
    public void testRunnableAt_uidForeground() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants, PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver()));
        enqueueOrReplaceBroadcast(queue, timeTickRecord, 0);

        assertThat(queue.getRunnableAt()).isGreaterThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());

        queue.setProcessAndUidState(mProcess, true, false);
        assertThat(queue.getRunnableAt()).isLessThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_FOREGROUND, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());

        queue.setProcessAndUidState(mProcess, false, false);
        assertThat(queue.getRunnableAt()).isGreaterThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());
    }

    @Test
    public void testRunnableAt_processTop() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants, PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));

        doReturn(ActivityManager.PROCESS_STATE_TOP).when(mProcess).getSetProcState();
        queue.setProcessAndUidState(mProcess, false, false);

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver()));
        enqueueOrReplaceBroadcast(queue, timeTickRecord, 0);

        assertThat(queue.getRunnableAt()).isLessThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_TOP_PROCESS, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());

        doReturn(ActivityManager.PROCESS_STATE_SERVICE).when(mProcess).getSetProcState();
        queue.setProcessAndUidState(mProcess, false, false);

        // The new process state will only be taken into account the next time a broadcast
        // is sent to the process.
        enqueueOrReplaceBroadcast(queue, makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver())), 0);
        assertThat(queue.getRunnableAt()).isGreaterThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());
    }

    @Test
    public void testRunnableAt_persistentProc() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants, PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver()));
        enqueueOrReplaceBroadcast(queue, timeTickRecord, 0);

        assertThat(queue.getRunnableAt()).isGreaterThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());

        doReturn(true).when(mProcess).isPersistent();
        queue.setProcessAndUidState(mProcess, false, false);
        assertThat(queue.getRunnableAt()).isLessThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_PERSISTENT, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());

        doReturn(false).when(mProcess).isPersistent();
        queue.setProcessAndUidState(mProcess, false, false);
        assertThat(queue.getRunnableAt()).isGreaterThan(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());
    }

    @Test
    public void testRunnableAt_coreUid() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                "com.android.bluetooth", Process.BLUETOOTH_UID);

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver()));
        enqueueOrReplaceBroadcast(queue, timeTickRecord, 0);

        assertThat(queue.getRunnableAt()).isEqualTo(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_CORE_UID, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());
    }

    @Test
    public void testRunnableAt_freezableCoreUid() {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                "com.android.bluetooth", Process.BLUETOOTH_UID);

        // Mark the process as freezable
        queue.setProcessAndUidState(mProcess, false, true);
        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastOptions options = BroadcastOptions.makeWithDeferUntilActive(true);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick, options,
                List.of(makeMockRegisteredReceiver()), false);
        enqueueOrReplaceBroadcast(queue, timeTickRecord, 0);

        assertEquals(Long.MAX_VALUE, queue.getRunnableAt());
        assertEquals(BroadcastProcessQueue.REASON_CACHED_INFINITE_DEFER,
                queue.getRunnableAtReason());
        assertTrue(queue.shouldBeDeferred());

        queue.setProcessAndUidState(mProcess, false, false);
        assertThat(queue.getRunnableAt()).isEqualTo(timeTickRecord.enqueueTime);
        assertEquals(BroadcastProcessQueue.REASON_CORE_UID, queue.getRunnableAtReason());
        assertFalse(queue.shouldBeDeferred());
    }

    /**
     * Verify that a cached process that would normally be delayed becomes
     * immediately runnable when the given broadcast is enqueued.
     */
    private void doRunnableAt_Cached(BroadcastRecord testRecord, int testRunnableAtReason) {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        queue.setProcessAndUidState(null, false, true);

        final BroadcastRecord lazyRecord = makeBroadcastRecord(
                new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED),
                List.of(makeMockRegisteredReceiver()));

        enqueueOrReplaceBroadcast(queue, lazyRecord, 0);
        assertThat(queue.getRunnableAt()).isGreaterThan(lazyRecord.enqueueTime);
        assertThat(queue.getRunnableAtReason()).isNotEqualTo(testRunnableAtReason);

        enqueueOrReplaceBroadcast(queue, testRecord, 0);
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
    public void testRunnableAt_Cached_Prioritized_NonDeferrable() {
        final List receivers = List.of(
                withPriority(makeManifestReceiver(PACKAGE_RED, PACKAGE_RED), 10),
                withPriority(makeManifestReceiver(PACKAGE_GREEN, PACKAGE_GREEN), -10));
        final BroadcastOptions options = BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
        doRunnableAt_Cached(makeBroadcastRecord(makeMockIntent(), options,
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

        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIMEZONE_CHANGED)), 0);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_ALARM_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIME_TICK)), 0);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCALE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0);

        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCKED_BOOT_COMPLETED, queue.getActive().intent.getAction());

        // To maximize test coverage, dump current state; we're not worried
        // about the actual output, just that we don't crash
        queue.getActive().setDeliveryState(0, BroadcastRecord.DELIVERY_SCHEDULED, "Test-driven");
        queue.dumpLocked(SystemClock.uptimeMillis(),
                new IndentingPrintWriter(new PrintWriter(Writer.nullWriter())));

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
        queue.addPrioritizeEarliestRequest();
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


        queue.removePrioritizeEarliestRequest();

        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_RECEIVER_OFFLOAD)), 0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_TIMEZONE_CHANGED)),
                0, timeCounter++);
        enqueueOrReplaceBroadcast(queue,
                makeBroadcastRecord(new Intent(Intent.ACTION_LOCALE_CHANGED)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)), 0, timeCounter++);

        // Once the request to prioritize earliest is removed, we should expect broadcasts
        // to be dispatched in the order of foreground, normal and then offload.
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_LOCALE_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, queue.getActive().intent.getAction());
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_BOOT_COMPLETED, queue.getActive().intent.getAction());
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
    public void testDeliveryGroupPolicy_diffReceivers() {
        final Intent screenOn = new Intent(Intent.ACTION_SCREEN_ON);
        final Intent screenOff = new Intent(Intent.ACTION_SCREEN_OFF);
        final BroadcastOptions screenOnOffOptions = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeliveryGroupMatchingKey("screenOnOff", Intent.ACTION_SCREEN_ON);

        final Object greenReceiver = makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN);
        final Object redReceiver = makeManifestReceiver(PACKAGE_RED, CLASS_RED);
        final Object blueReceiver = makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), false));
        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        final BroadcastProcessQueue blueQueue = mImpl.getProcessQueue(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyPendingRecords(greenQueue, List.of(screenOff));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff));

        assertTrue(greenQueue.isEmpty());
        assertTrue(redQueue.isEmpty());
        assertTrue(blueQueue.isEmpty());

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), false));
        verifyPendingRecords(greenQueue, List.of(screenOn));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOn));
    }

    @Test
    public void testDeliveryGroupPolicy_ordered_diffReceivers() {
        final Intent screenOn = new Intent(Intent.ACTION_SCREEN_ON);
        final Intent screenOff = new Intent(Intent.ACTION_SCREEN_OFF);
        final BroadcastOptions screenOnOffOptions = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeliveryGroupMatchingKey("screenOnOff", Intent.ACTION_SCREEN_ON);

        final Object greenReceiver = makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN);
        final Object redReceiver = makeManifestReceiver(PACKAGE_RED, CLASS_RED);
        final Object blueReceiver = makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), true));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), true));
        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        final BroadcastProcessQueue blueQueue = mImpl.getProcessQueue(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyPendingRecords(greenQueue, List.of(screenOff));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff));

        assertTrue(greenQueue.isEmpty());
        assertTrue(redQueue.isEmpty());
        assertTrue(blueQueue.isEmpty());

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), true));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), true));
        verifyPendingRecords(greenQueue, List.of(screenOff, screenOn));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff, screenOn));
    }

    @Test
    public void testDeliveryGroupPolicy_resultTo_diffReceivers() {
        final Intent screenOn = new Intent(Intent.ACTION_SCREEN_ON);
        final Intent screenOff = new Intent(Intent.ACTION_SCREEN_OFF);
        final BroadcastOptions screenOnOffOptions = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeliveryGroupMatchingKey("screenOnOff", Intent.ACTION_SCREEN_ON);

        final Object greenReceiver = makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN);
        final Object redReceiver = makeManifestReceiver(PACKAGE_RED, CLASS_RED);
        final Object blueReceiver = makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE);
        final IIntentReceiver resultTo = mock(IIntentReceiver.class);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), resultTo, false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), resultTo, false));
        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        final BroadcastProcessQueue blueQueue = mImpl.getProcessQueue(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyPendingRecords(greenQueue, List.of(screenOff));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff));

        assertTrue(greenQueue.isEmpty());
        assertTrue(redQueue.isEmpty());
        assertTrue(blueQueue.isEmpty());

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), resultTo, false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), resultTo, false));
        verifyPendingRecords(greenQueue, List.of(screenOff, screenOn));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff, screenOn));

        final BroadcastRecord screenOffRecord = makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), resultTo, false);
        screenOffRecord.setDeliveryState(2, BroadcastRecord.DELIVERY_DEFERRED,
                "testDeliveryGroupPolicy_resultTo_diffReceivers");
        mImpl.enqueueBroadcastLocked(screenOffRecord);
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), resultTo, false));
        verifyPendingRecords(greenQueue, List.of(screenOff, screenOn));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOn));
    }

    @Test
    public void testDeliveryGroupPolicy_prioritized_diffReceivers() {
        final Intent screenOn = new Intent(Intent.ACTION_SCREEN_ON);
        final Intent screenOff = new Intent(Intent.ACTION_SCREEN_OFF);
        final BroadcastOptions screenOnOffOptions = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeliveryGroupMatchingKey("screenOnOff", Intent.ACTION_SCREEN_ON);

        final Object greenReceiver = withPriority(
                makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN), 10);
        final Object redReceiver = withPriority(
                makeManifestReceiver(PACKAGE_RED, CLASS_RED), 5);
        final Object blueReceiver = withPriority(
                makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE), 0);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), false));
        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        final BroadcastProcessQueue blueQueue = mImpl.getProcessQueue(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyPendingRecords(greenQueue, List.of(screenOff));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff));

        assertTrue(greenQueue.isEmpty());
        assertTrue(redQueue.isEmpty());
        assertTrue(blueQueue.isEmpty());

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOff, screenOnOffOptions,
                List.of(greenReceiver, redReceiver, blueReceiver), false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(screenOn, screenOnOffOptions,
                List.of(greenReceiver, blueReceiver), false));
        verifyPendingRecords(greenQueue, List.of(screenOff, screenOn));
        verifyPendingRecords(redQueue, List.of(screenOff));
        verifyPendingRecords(blueQueue, List.of(screenOff, screenOn));
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
    public void testDeliveryGroupPolicy_merged_matchingFilter() {
        final long now = SystemClock.elapsedRealtime();
        final Pair<Intent, BroadcastOptions> dropboxEntryBroadcast1 = createDropboxBroadcast(
                "TAG_A", now, 2);
        final Pair<Intent, BroadcastOptions> dropboxEntryBroadcast2 = createDropboxBroadcast(
                "TAG_B", now + 1000, 4);
        final Pair<Intent, BroadcastOptions> dropboxEntryBroadcast3 = createDropboxBroadcast(
                "TAG_A", now + 2000, 7);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(dropboxEntryBroadcast1.first,
                dropboxEntryBroadcast1.second));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(dropboxEntryBroadcast2.first,
                dropboxEntryBroadcast2.second));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(dropboxEntryBroadcast3.first,
                dropboxEntryBroadcast3.second));

        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        // dropboxEntryBroadcast1 and dropboxEntryBroadcast3 should be merged as they use the same
        // tag and there shouldn't be a change to dropboxEntryBroadcast2.
        final Pair<Intent, BroadcastOptions> expectedMergedBroadcast = createDropboxBroadcast(
                "TAG_A", now + 2000, 10);
        verifyPendingRecords(queue, List.of(
                dropboxEntryBroadcast2.first, expectedMergedBroadcast.first));
    }

    @Test
    public void testDeliveryGroupPolicy_merged_multipleReceivers() {
        final long now = SystemClock.elapsedRealtime();
        final Pair<Intent, BroadcastOptions> dropboxEntryBroadcast1 = createDropboxBroadcast(
                "TAG_A", now, 2);
        final Pair<Intent, BroadcastOptions> dropboxEntryBroadcast2 = createDropboxBroadcast(
                "TAG_A", now + 1000, 4);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(dropboxEntryBroadcast1.first,
                dropboxEntryBroadcast1.second,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_RED, CLASS_RED)),
                false));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(dropboxEntryBroadcast2.first,
                dropboxEntryBroadcast2.second,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_RED, CLASS_RED)),
                false));

        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));

        verifyPendingRecords(greenQueue,
                List.of(dropboxEntryBroadcast1.first, dropboxEntryBroadcast2.first));
        verifyPendingRecords(redQueue,
                List.of(dropboxEntryBroadcast1.first, dropboxEntryBroadcast2.first));
    }

    @Test
    public void testDeliveryGroupPolicy_sameAction_differentMatchingCriteria() {
        final Intent closeSystemDialogs1 = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        final BroadcastOptions optionsCloseSystemDialog1 = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);

        final Intent closeSystemDialogs2 = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                .putExtra("reason", "testing");
        final BroadcastOptions optionsCloseSystemDialog2 = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeliveryGroupMatchingKey(Intent.ACTION_CLOSE_SYSTEM_DIALOGS, "testing");

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(
                closeSystemDialogs1, optionsCloseSystemDialog1));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(
                closeSystemDialogs2, optionsCloseSystemDialog2));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(
                closeSystemDialogs1, optionsCloseSystemDialog1));
        // Verify that only the older broadcast with no extras was removed.
        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        verifyPendingRecords(queue, List.of(closeSystemDialogs2, closeSystemDialogs1));

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(
                closeSystemDialogs2, optionsCloseSystemDialog2));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(
                closeSystemDialogs1, optionsCloseSystemDialog1));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(
                closeSystemDialogs2, optionsCloseSystemDialog2));
        // Verify that only the older broadcast with no extras was removed.
        verifyPendingRecords(queue, List.of(closeSystemDialogs1, closeSystemDialogs2));
    }

    private Pair<Intent, BroadcastOptions> createDropboxBroadcast(String tag, long timestampMs,
            int droppedCount) {
        final Intent dropboxEntryAdded = new Intent(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED);
        dropboxEntryAdded.putExtra(DropBoxManager.EXTRA_TAG, tag);
        dropboxEntryAdded.putExtra(DropBoxManager.EXTRA_TIME, timestampMs);
        dropboxEntryAdded.putExtra(DropBoxManager.EXTRA_DROPPED_COUNT, droppedCount);

        final BundleMerger extrasMerger = new BundleMerger();
        extrasMerger.setDefaultMergeStrategy(BundleMerger.STRATEGY_FIRST);
        extrasMerger.setMergeStrategy(DropBoxManager.EXTRA_TIME,
                BundleMerger.STRATEGY_COMPARABLE_MAX);
        extrasMerger.setMergeStrategy(DropBoxManager.EXTRA_DROPPED_COUNT,
                BundleMerger.STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD);
        final IntentFilter matchingFilter = new IntentFilter(
                DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED);
        matchingFilter.addExtra(DropBoxManager.EXTRA_TAG, tag);
        final BroadcastOptions optionsDropboxEntryAdded = BroadcastOptions.makeBasic()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MERGED)
                .setDeliveryGroupMatchingFilter(matchingFilter)
                .setDeliveryGroupExtrasMerger(extrasMerger);
        return Pair.create(dropboxEntryAdded, optionsDropboxEntryAdded);
    }

    @Test
    public void testVerifyEnqueuedTime_withReplacePending() {
        final Intent userPresent = new Intent(Intent.ACTION_USER_PRESENT);
        userPresent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

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

    @Test
    public void testBroadcastDeliveryEventReported() throws Exception {
        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastOptions optionsTimeTick = BroadcastOptions.makeBasic();
        optionsTimeTick.setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);

        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        mImpl.enqueueBroadcastLocked(makeBroadcastRecord(timeTick, optionsTimeTick));
        waitForIdle();

        // Verify that there is only one delivery event reported since one of the broadcasts
        // should have been skipped.
        verify(() -> FrameworkStatsLog.write(eq(BROADCAST_DELIVERY_EVENT_REPORTED),
                eq(getUidForPackage(PACKAGE_GREEN)), anyInt(), eq(Intent.ACTION_TIME_TICK),
                eq(BROADCAST_DELIVERY_EVENT_REPORTED__RECEIVER_TYPE__MANIFEST),
                eq(BROADCAST_DELIVERY_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD),
                anyLong(), anyLong(), anyLong(), anyInt(), nullable(String.class),
                anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()),
                times(1));
    }

    @Test
    public void testGetPreferredSchedulingGroup() throws Exception {
        final BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        enqueueOrReplaceBroadcast(queue, makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver())), 0);
        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());

        // Make the foreground broadcast as active.
        queue.makeActiveNextPending();
        assertEquals(ProcessList.SCHED_GROUP_DEFAULT, queue.getPreferredSchedulingGroupLocked());

        queue.makeActiveIdle();
        assertEquals(ProcessList.SCHED_GROUP_UNDEFINED, queue.getPreferredSchedulingGroupLocked());

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueOrReplaceBroadcast(queue, makeBroadcastRecord(airplane,
                List.of(makeMockRegisteredReceiver())), 0);

        // Make the background broadcast as active.
        queue.makeActiveNextPending();
        assertEquals(ProcessList.SCHED_GROUP_BACKGROUND, queue.getPreferredSchedulingGroupLocked());

        enqueueOrReplaceBroadcast(queue, makeBroadcastRecord(timeTick,
                List.of(makeMockRegisteredReceiver())), 0);
        // Even though the active broadcast is not a foreground one, scheduling group will be
        // DEFAULT since there is a foreground broadcast waiting to be delivered.
        assertEquals(ProcessList.SCHED_GROUP_DEFAULT, queue.getPreferredSchedulingGroupLocked());
    }

    @Test
    public void testSkipPolicy_atEnqueueTime() throws Exception {
        final Intent userPresent = new Intent(Intent.ACTION_USER_PRESENT);
        final Object greenReceiver = makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN);
        final Object redReceiver = makeManifestReceiver(PACKAGE_RED, CLASS_RED);

        final BroadcastRecord userPresentRecord = makeBroadcastRecord(userPresent,
                List.of(greenReceiver, redReceiver));

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick,
                List.of(greenReceiver, redReceiver));

        doAnswer(invocation -> {
            final BroadcastRecord r = invocation.getArgument(0);
            final Object o = invocation.getArgument(1);
            if (userPresent.getAction().equals(r.intent.getAction())
                    && isReceiverEquals(o, greenReceiver)) {
                return "receiver skipped by test";
            }
            return null;
        }).when(mSkipPolicy).shouldSkipMessage(any(BroadcastRecord.class), any());

        mImpl.enqueueBroadcastLocked(userPresentRecord);
        mImpl.enqueueBroadcastLocked(timeTickRecord);

        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        // There should be only one broadcast for green process as the other would have
        // been skipped.
        verifyPendingRecords(greenQueue, List.of(timeTick));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        verifyPendingRecords(redQueue, List.of(userPresent, timeTick));
    }

    @Test
    public void testDeliveryDeferredForCached() throws Exception {
        final ProcessRecord greenProcess = makeProcessRecord(makeApplicationInfo(PACKAGE_GREEN));
        final ProcessRecord redProcess = makeProcessRecord(makeApplicationInfo(PACKAGE_RED));

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick,
                List.of(makeRegisteredReceiver(greenProcess, 0)));

        final Intent batteryChanged = new Intent(Intent.ACTION_BATTERY_CHANGED);
        final BroadcastOptions optionsBatteryChanged =
                BroadcastOptions.makeWithDeferUntilActive(true);
        final BroadcastRecord batteryChangedRecord = makeBroadcastRecord(batteryChanged,
                optionsBatteryChanged,
                List.of(makeRegisteredReceiver(greenProcess, 10),
                        makeRegisteredReceiver(redProcess, 0)),
                false /* ordered */);

        mImpl.enqueueBroadcastLocked(timeTickRecord);
        mImpl.enqueueBroadcastLocked(batteryChangedRecord);

        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, greenQueue.getRunnableAtReason());
        assertFalse(greenQueue.shouldBeDeferred());
        assertEquals(BroadcastProcessQueue.REASON_BLOCKED, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        // Simulate process state change
        greenQueue.setProcessAndUidState(greenProcess, false /* uidForeground */,
                true /* processFreezable */);
        greenQueue.updateDeferredStates(mImpl.mBroadcastConsumerDeferApply,
                mImpl.mBroadcastConsumerDeferClear);

        assertEquals(BroadcastProcessQueue.REASON_CACHED, greenQueue.getRunnableAtReason());
        assertTrue(greenQueue.shouldBeDeferred());
        // Once the broadcasts to green process are deferred, broadcasts to red process
        // shouldn't be blocked anymore.
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        // All broadcasts to green process should be deferred.
        greenQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_DEFERRED, r.getDeliveryState(i));
        }, false /* andRemove */);
        redQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);

        final Intent packageChanged = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        final BroadcastRecord packageChangedRecord = makeBroadcastRecord(packageChanged,
                List.of(makeRegisteredReceiver(greenProcess, 0)));
        mImpl.enqueueBroadcastLocked(packageChangedRecord);

        assertEquals(BroadcastProcessQueue.REASON_CACHED, greenQueue.getRunnableAtReason());
        assertTrue(greenQueue.shouldBeDeferred());
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        // All broadcasts to the green process, including the newly enqueued one, should be
        // deferred.
        greenQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_DEFERRED, r.getDeliveryState(i));
        }, false /* andRemove */);
        redQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);

        // Simulate process state change
        greenQueue.setProcessAndUidState(greenProcess, false /* uidForeground */,
                false /* processFreezable */);
        greenQueue.updateDeferredStates(mImpl.mBroadcastConsumerDeferApply,
                mImpl.mBroadcastConsumerDeferClear);

        assertEquals(BroadcastProcessQueue.REASON_NORMAL, greenQueue.getRunnableAtReason());
        assertFalse(greenQueue.shouldBeDeferred());
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        greenQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);
        redQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);
    }

    @Test
    public void testDeliveryDeferredForCached_withInfiniteDeferred() throws Exception {
        final ProcessRecord greenProcess = makeProcessRecord(makeApplicationInfo(PACKAGE_GREEN));
        final ProcessRecord redProcess = makeProcessRecord(makeApplicationInfo(PACKAGE_RED));

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastOptions optionsTimeTick = BroadcastOptions.makeWithDeferUntilActive(true);
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick, optionsTimeTick,
                List.of(makeRegisteredReceiver(greenProcess, 0)), false /* ordered */);

        final Intent batteryChanged = new Intent(Intent.ACTION_BATTERY_CHANGED);
        final BroadcastOptions optionsBatteryChanged =
                BroadcastOptions.makeWithDeferUntilActive(true);
        final BroadcastRecord batteryChangedRecord = makeBroadcastRecord(batteryChanged,
                optionsBatteryChanged,
                List.of(makeRegisteredReceiver(greenProcess, 10),
                        makeRegisteredReceiver(redProcess, 0)),
                false /* ordered */);

        mImpl.enqueueBroadcastLocked(timeTickRecord);
        mImpl.enqueueBroadcastLocked(batteryChangedRecord);

        final BroadcastProcessQueue greenQueue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final BroadcastProcessQueue redQueue = mImpl.getProcessQueue(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED));
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, greenQueue.getRunnableAtReason());
        assertFalse(greenQueue.shouldBeDeferred());
        assertEquals(BroadcastProcessQueue.REASON_BLOCKED, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        // Simulate process state change
        greenQueue.setProcessAndUidState(greenProcess, false /* uidForeground */,
                true /* processFreezable */);
        greenQueue.updateDeferredStates(mImpl.mBroadcastConsumerDeferApply,
                mImpl.mBroadcastConsumerDeferClear);

        assertEquals(BroadcastProcessQueue.REASON_CACHED_INFINITE_DEFER,
                greenQueue.getRunnableAtReason());
        assertTrue(greenQueue.shouldBeDeferred());
        // Once the broadcasts to green process are deferred, broadcasts to red process
        // shouldn't be blocked anymore.
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        // All broadcasts to green process should be deferred.
        greenQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_DEFERRED, r.getDeliveryState(i));
        }, false /* andRemove */);
        redQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);

        final Intent packageChanged = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        final BroadcastOptions optionsPackageChanged =
                BroadcastOptions.makeWithDeferUntilActive(true);
        final BroadcastRecord packageChangedRecord = makeBroadcastRecord(packageChanged,
                optionsPackageChanged,
                List.of(makeRegisteredReceiver(greenProcess, 0)), false /* ordered */);
        mImpl.enqueueBroadcastLocked(packageChangedRecord);

        assertEquals(BroadcastProcessQueue.REASON_CACHED_INFINITE_DEFER,
                greenQueue.getRunnableAtReason());
        assertTrue(greenQueue.shouldBeDeferred());
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        // All broadcasts to the green process, including the newly enqueued one, should be
        // deferred.
        greenQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_DEFERRED, r.getDeliveryState(i));
        }, false /* andRemove */);
        redQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);

        // Simulate process state change
        greenQueue.setProcessAndUidState(greenProcess, false /* uidForeground */,
                false /* processFreezable */);
        greenQueue.updateDeferredStates(mImpl.mBroadcastConsumerDeferApply,
                mImpl.mBroadcastConsumerDeferClear);

        assertEquals(BroadcastProcessQueue.REASON_NORMAL, greenQueue.getRunnableAtReason());
        assertFalse(greenQueue.shouldBeDeferred());
        assertEquals(BroadcastProcessQueue.REASON_NORMAL, redQueue.getRunnableAtReason());
        assertFalse(redQueue.shouldBeDeferred());

        greenQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);
        redQueue.forEachMatchingBroadcast(BROADCAST_PREDICATE_ANY, (r, i) -> {
            assertEquals("Unexpected state for " + r,
                    BroadcastRecord.DELIVERY_PENDING, r.getDeliveryState(i));
        }, false /* andRemove */);
    }

    @Test
    public void testIsProcessFreezable() throws Exception {
        final ProcessRecord greenProcess = makeProcessRecord(makeApplicationInfo(PACKAGE_GREEN));

        setProcessFreezable(greenProcess, true /* pendingFreeze */, true /* frozen */);
        mImpl.onProcessFreezableChangedLocked(greenProcess);
        waitForIdle();
        assertTrue(mImpl.isProcessFreezable(greenProcess));

        setProcessFreezable(greenProcess, true /* pendingFreeze */, false /* frozen */);
        mImpl.onProcessFreezableChangedLocked(greenProcess);
        waitForIdle();
        assertTrue(mImpl.isProcessFreezable(greenProcess));

        setProcessFreezable(greenProcess, false /* pendingFreeze */, true /* frozen */);
        mImpl.onProcessFreezableChangedLocked(greenProcess);
        waitForIdle();
        assertTrue(mImpl.isProcessFreezable(greenProcess));

        setProcessFreezable(greenProcess, false /* pendingFreeze */, false /* frozen */);
        mImpl.onProcessFreezableChangedLocked(greenProcess);
        waitForIdle();
        assertFalse(mImpl.isProcessFreezable(greenProcess));
    }

    // TODO: Reuse BroadcastQueueTest.makeActiveProcessRecord()
    private ProcessRecord makeProcessRecord(ApplicationInfo info) {
        final ProcessRecord r = spy(new ProcessRecord(mAms, info, info.processName, info.uid));
        r.setPid(mNextPid.incrementAndGet());
        ProcessRecord.updateProcessRecordNodes(r);
        return r;
    }

    BroadcastFilter makeRegisteredReceiver(ProcessRecord app, int priority) {
        final IIntentReceiver receiver = mock(IIntentReceiver.class);
        final ReceiverList receiverList = new ReceiverList(mAms, app, app.getPid(), app.info.uid,
                UserHandle.getUserId(app.info.uid), receiver);
        return makeRegisteredReceiver(receiverList, priority);
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

            // While we're here, give our health check some test coverage
            queue.assertHealthLocked();
            queue.dumpLocked(0L, new IndentingPrintWriter(Writer.nullWriter()));

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
