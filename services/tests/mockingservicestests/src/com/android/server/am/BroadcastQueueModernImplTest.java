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

import static com.android.server.am.BroadcastProcessQueue.insertIntoRunnableList;
import static com.android.server.am.BroadcastProcessQueue.removeFromRunnableList;
import static com.android.server.am.BroadcastQueueTest.CLASS_GREEN;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_BLUE;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_GREEN;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_RED;
import static com.android.server.am.BroadcastQueueTest.PACKAGE_YELLOW;
import static com.android.server.am.BroadcastQueueTest.getUidForPackage;
import static com.android.server.am.BroadcastQueueTest.makeManifestReceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class BroadcastQueueModernImplTest {
    private static final int TEST_UID = android.os.Process.FIRST_APPLICATION_UID;

    @Mock ActivityManagerService mAms;

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
        mImpl = new BroadcastQueueModernImpl(mAms, mHandlerThread.getThreadHandler(),
                mConstants, mConstants);

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

    private BroadcastRecord makeBroadcastRecord(Intent intent) {
        return makeBroadcastRecord(intent, BroadcastOptions.makeBasic(),
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), false);
    }

    private BroadcastRecord makeOrderedBroadcastRecord(Intent intent) {
        return makeBroadcastRecord(intent, BroadcastOptions.makeBasic(),
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), true);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, BroadcastOptions options) {
        return makeBroadcastRecord(intent, options,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), false);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, BroadcastOptions options,
            List receivers, boolean ordered) {
        return new BroadcastRecord(mImpl, intent, null, PACKAGE_RED, null, 21, 42, false, null,
                null, null, null, AppOpsManager.OP_NONE, options, receivers, null,
                Activity.RESULT_OK, null, null, ordered, false, false, UserHandle.USER_SYSTEM,
                false, null, false, null);
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
        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));
        assertFalse(queue.isRunnable());
        assertEquals(Long.MAX_VALUE, queue.getRunnableAt());
    }

    /**
     * Queue with a "normal" broadcast is runnable at different times depending
     * on process cached state; when cached it's delayed by some amount.
     */
    @Test
    public void testRunnableAt_Normal() {
        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane);
        queue.enqueueBroadcast(airplaneRecord, 0);

        queue.setProcessCached(false);
        final long notCachedRunnableAt = queue.getRunnableAt();
        queue.setProcessCached(true);
        final long cachedRunnableAt = queue.getRunnableAt();
        assertTrue(cachedRunnableAt > notCachedRunnableAt);
    }

    /**
     * Queue with foreground broadcast is always runnable immediately,
     * regardless of process cached state.
     */
    @Test
    public void testRunnableAt_Foreground() {
        BroadcastProcessQueue queue = new BroadcastProcessQueue(mConstants,
                PACKAGE_GREEN, getUidForPackage(PACKAGE_GREEN));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane);
        queue.enqueueBroadcast(airplaneRecord, 0);

        queue.setProcessCached(false);
        assertTrue(queue.isRunnable());
        assertEquals(airplaneRecord.enqueueTime, queue.getRunnableAt());

        queue.setProcessCached(true);
        assertTrue(queue.isRunnable());
        assertEquals(airplaneRecord.enqueueTime, queue.getRunnableAt());
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

        // Marching through the queue we should only have one SCREEN_OFF
        // broadcast, since that's the last state we dispatched
        final BroadcastProcessQueue queue = mImpl.getProcessQueue(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        queue.makeActiveNextPending();
        assertEquals(Intent.ACTION_SCREEN_OFF, queue.getActive().intent.getAction());
        assertTrue(queue.isEmpty());
    }
}
