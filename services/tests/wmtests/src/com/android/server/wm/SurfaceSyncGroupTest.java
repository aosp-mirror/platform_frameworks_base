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

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;
import android.server.wm.BuildUtils;
import android.view.SurfaceControl;
import android.window.SurfaceSyncGroup;

import androidx.test.filters.SmallTest;

import com.android.server.testutils.StubTransaction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
public class SurfaceSyncGroupTest {
    private static final String TAG = "SurfaceSyncGroupTest";
    private static final int TIMEOUT_MS = 100;

    private final Executor mExecutor = Runnable::run;

    @Before
    public void setup() {
        SurfaceSyncGroup.setTransactionFactory(StubTransaction::new);
    }

    @After
    public void tearDown() {
        SurfaceSyncGroup.setTransactionFactory(SurfaceControl.Transaction::new);
    }

    @Test
    public void testSyncOne() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
        syncGroup.addSyncCompleteCallback(mExecutor, finishedLatch::countDown);
        SurfaceSyncGroup syncTarget = new SurfaceSyncGroup("FakeSyncTarget");
        syncGroup.add(syncTarget, null /* runnable */);
        syncGroup.markSyncReady();

        syncTarget.markSyncReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testSyncMultiple() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
        syncGroup.addSyncCompleteCallback(mExecutor, finishedLatch::countDown);
        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");
        SurfaceSyncGroup syncTarget3 = new SurfaceSyncGroup("FakeSyncTarget3");

        syncGroup.add(syncTarget1, null /* runnable */);
        syncGroup.add(syncTarget2, null /* runnable */);
        syncGroup.add(syncTarget3, null /* runnable */);
        syncGroup.markSyncReady();

        syncTarget1.markSyncReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncTarget3.markSyncReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncTarget2.markSyncReady();

        finishedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testAddSyncWhenSyncComplete() {
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);

        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");

        assertTrue(syncGroup.add(syncTarget1, null /* runnable */));
        syncGroup.markSyncReady();
        // Adding to a sync that has been completed is also invalid since the sync id has been
        // cleared.
        assertFalse(syncGroup.add(syncTarget2, null /* runnable */));
    }

    @Test
    public void testMultipleSyncGroups() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(TAG);
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(TAG);

        syncGroup1.addSyncCompleteCallback(mExecutor, finishedLatch1::countDown);
        syncGroup2.addSyncCompleteCallback(mExecutor, finishedLatch2::countDown);

        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");

        assertTrue(syncGroup1.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup2.add(syncTarget2, null /* runnable */));
        syncGroup1.markSyncReady();
        syncGroup2.markSyncReady();

        syncTarget1.markSyncReady();

        finishedLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch1.getCount());
        assertNotEquals(0, finishedLatch2.getCount());

        syncTarget2.markSyncReady();

        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testAddSyncGroup() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(TAG);
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(TAG);

        syncGroup1.addSyncCompleteCallback(mExecutor, finishedLatch1::countDown);
        syncGroup2.addSyncCompleteCallback(mExecutor, finishedLatch2::countDown);

        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");

        assertTrue(syncGroup1.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup2.add(syncTarget2, null /* runnable */));
        syncGroup1.markSyncReady();
        syncGroup2.add(syncGroup1, null /* runnable */);
        syncGroup2.markSyncReady();

        // Finish syncTarget2 first to test that the syncGroup is not complete until the merged sync
        // is also done.
        syncTarget2.markSyncReady();
        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        // Sync did not complete yet
        assertNotEquals(0, finishedLatch2.getCount());

        syncTarget1.markSyncReady();

        // The first sync will still get a callback when it's sync requirements are done.
        finishedLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch1.getCount());

        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testAddSyncAlreadyComplete() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(TAG);
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(TAG);

        syncGroup1.addSyncCompleteCallback(mExecutor, finishedLatch1::countDown);
        syncGroup2.addSyncCompleteCallback(mExecutor, finishedLatch2::countDown);

        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");

        assertTrue(syncGroup1.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup2.add(syncTarget2, null /* runnable */));
        syncGroup1.markSyncReady();
        syncTarget1.markSyncReady();

        // The first sync will still get a callback when it's sync requirements are done.
        finishedLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch1.getCount());

        syncGroup2.add(syncGroup1, null /* runnable */);
        syncGroup2.markSyncReady();
        syncTarget2.markSyncReady();

        // Verify that the second sync will receive complete since the merged sync was already
        // completed before the merge.
        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testAddSyncAlreadyInASync_NewSyncReadyFirst() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(TAG);
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(TAG);

        syncGroup1.addSyncCompleteCallback(mExecutor, finishedLatch1::countDown);
        syncGroup2.addSyncCompleteCallback(mExecutor, finishedLatch2::countDown);

        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");
        SurfaceSyncGroup syncTarget3 = new SurfaceSyncGroup("FakeSyncTarget3");

        assertTrue(syncGroup1.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup1.add(syncTarget2, null /* runnable */));

        // Add syncTarget1 to syncGroup2 so it forces syncGroup1 into syncGroup2
        assertTrue(syncGroup2.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup2.add(syncTarget3, null /* runnable */));

        syncGroup1.markSyncReady();
        syncGroup2.markSyncReady();

        // Make target1 and target3 ready, but not target2. SyncGroup2 should not be ready since
        // SyncGroup2 also waits for all of SyncGroup1 to finish, which includes target2
        syncTarget1.markSyncReady();
        syncTarget3.markSyncReady();

        // Neither SyncGroup will be ready.
        finishedLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(1, finishedLatch1.getCount());
        assertEquals(1, finishedLatch2.getCount());

        syncTarget2.markSyncReady();

        // Both sync groups should be ready after target2 completed.
        finishedLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch1.getCount());
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testAddSyncAlreadyInASync_OldSyncFinishesFirst() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(TAG);
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(TAG);

        syncGroup1.addSyncCompleteCallback(mExecutor, finishedLatch1::countDown);
        syncGroup2.addSyncCompleteCallback(mExecutor, finishedLatch2::countDown);

        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");
        SurfaceSyncGroup syncTarget3 = new SurfaceSyncGroup("FakeSyncTarget3");

        assertTrue(syncGroup1.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup1.add(syncTarget2, null /* runnable */));
        syncTarget2.markSyncReady();

        // Add syncTarget1 to syncGroup2 so it forces syncGroup1 into syncGroup2
        assertTrue(syncGroup2.add(syncTarget1, null /* runnable */));
        assertTrue(syncGroup2.add(syncTarget3, null /* runnable */));

        syncGroup1.markSyncReady();
        syncGroup2.markSyncReady();

        syncTarget1.markSyncReady();

        // Only SyncGroup1 will be ready, but SyncGroup2 still needs its own targets to be ready.
        finishedLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(0, finishedLatch1.getCount());
        assertEquals(1, finishedLatch2.getCount());

        syncTarget3.markSyncReady();

        // SyncGroup2 is finished after target3 completed.
        finishedLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testParentSyncGroupMerge_true() {
        // Temporarily set a new transaction factory so it will return the stub transaction for
        // the sync group.
        SurfaceControl.Transaction parentTransaction = spy(new StubTransaction());
        SurfaceSyncGroup.setTransactionFactory(() -> parentTransaction);

        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
        syncGroup.addSyncCompleteCallback(mExecutor, finishedLatch::countDown);

        SurfaceControl.Transaction targetTransaction = spy(new StubTransaction());
        SurfaceSyncGroup.setTransactionFactory(() -> targetTransaction);

        SurfaceSyncGroup syncTarget = new SurfaceSyncGroup("FakeSyncTarget");
        assertTrue(
                syncGroup.add(syncTarget.mISurfaceSyncGroup, true /* parentSyncGroupMerge */,
                        null /* runnable */));
        syncTarget.markSyncReady();

        // When parentSyncGroupMerge is true, the transaction passed in merges the main SyncGroup
        // transaction first because it knows the previous parentSyncGroup is older so it should
        // be overwritten by anything newer.
        verify(targetTransaction).merge(parentTransaction);
        verify(parentTransaction).merge(targetTransaction);
    }

    @Test
    public void testParentSyncGroupMerge_false() {
        // Temporarily set a new transaction factory so it will return the stub transaction for
        // the sync group.
        SurfaceControl.Transaction parentTransaction = spy(new StubTransaction());
        SurfaceSyncGroup.setTransactionFactory(() -> parentTransaction);

        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
        syncGroup.addSyncCompleteCallback(mExecutor, finishedLatch::countDown);

        SurfaceControl.Transaction targetTransaction = spy(new StubTransaction());
        SurfaceSyncGroup.setTransactionFactory(() -> targetTransaction);

        SurfaceSyncGroup syncTarget = new SurfaceSyncGroup("FakeSyncTarget");
        assertTrue(syncGroup.add(syncTarget, null /* runnable */));
        syncTarget.markSyncReady();

        // When parentSyncGroupMerge is false, the transaction passed in should not merge
        // the main SyncGroup since we don't need to change the transaction order
        verify(targetTransaction, never()).merge(parentTransaction);
        verify(parentTransaction).merge(targetTransaction);
    }

    @Test
    public void testAddToSameParentNoCrash() {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
        syncGroup.addSyncCompleteCallback(mExecutor, finishedLatch::countDown);
        SurfaceSyncGroup syncTarget = new SurfaceSyncGroup("FakeSyncTarget");
        syncGroup.add(syncTarget, null /* runnable */);
        // Add the syncTarget to the same syncGroup and ensure it doesn't crash.
        syncGroup.add(syncTarget, null /* runnable */);
        syncGroup.markSyncReady();

        syncTarget.markSyncReady();

        try {
            finishedLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testSurfaceSyncGroupTimeout() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(TAG);
        syncGroup.addSyncCompleteCallback(mExecutor, finishedLatch::countDown);
        SurfaceSyncGroup syncTarget1 = new SurfaceSyncGroup("FakeSyncTarget1");
        SurfaceSyncGroup syncTarget2 = new SurfaceSyncGroup("FakeSyncTarget2");

        syncGroup.add(syncTarget1, null /* runnable */);
        syncGroup.add(syncTarget2, null /* runnable */);
        syncGroup.markSyncReady();

        syncTarget1.markSyncReady();
        assertNotEquals(0, finishedLatch.getCount());

        // Never finish syncTarget2 so it forces the timeout. Timeout is 1 second so wait a little
        // over 1 second to make sure it completes.
        finishedLatch.await(1100L * BuildUtils.HW_TIMEOUT_MULTIPLIER, TimeUnit.MILLISECONDS);
        assertEquals(0, finishedLatch.getCount());
    }
}
