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

import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.window.SurfaceSyncGroup;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
public class SurfaceSyncGroupTest {

    @Before
    public void setup() {
        SurfaceSyncGroup.setTransactionFactory(StubTransaction::new);
    }

    @Test
    public void testSyncOne() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(transaction -> finishedLatch.countDown());
        SyncTarget syncTarget = new SyncTarget();
        syncGroup.addToSync(syncTarget);
        syncGroup.markSyncReady();

        syncTarget.onBufferReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testSyncMultiple() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(transaction -> finishedLatch.countDown());
        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();
        SyncTarget syncTarget3 = new SyncTarget();

        syncGroup.addToSync(syncTarget1);
        syncGroup.addToSync(syncTarget2);
        syncGroup.addToSync(syncTarget3);
        syncGroup.markSyncReady();

        syncTarget1.onBufferReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncTarget3.onBufferReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncTarget2.onBufferReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testAddSyncWhenSyncComplete() {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(transaction -> finishedLatch.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(syncGroup.addToSync(syncTarget1));
        syncGroup.markSyncReady();
        // Adding to a sync that has been completed is also invalid since the sync id has been
        // cleared.
        assertFalse(syncGroup.addToSync(syncTarget2));
    }

    @Test
    public void testMultiplesyncGroups() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(
                transaction -> finishedLatch1.countDown());
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(
                transaction -> finishedLatch2.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(syncGroup1.addToSync(syncTarget1));
        assertTrue(syncGroup2.addToSync(syncTarget2));
        syncGroup1.markSyncReady();
        syncGroup2.markSyncReady();

        syncTarget1.onBufferReady();

        finishedLatch1.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch1.getCount());
        assertNotEquals(0, finishedLatch2.getCount());

        syncTarget2.onBufferReady();

        finishedLatch2.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testMergeSync() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(
                transaction -> finishedLatch1.countDown());
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(
                transaction -> finishedLatch2.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(syncGroup1.addToSync(syncTarget1));
        assertTrue(syncGroup2.addToSync(syncTarget2));
        syncGroup1.markSyncReady();
        syncGroup2.merge(syncGroup1);
        syncGroup2.markSyncReady();

        // Finish syncTarget2 first to test that the syncGroup is not complete until the merged sync
        // is also done.
        syncTarget2.onBufferReady();
        finishedLatch2.await(1, TimeUnit.SECONDS);
        // Sync did not complete yet
        assertNotEquals(0, finishedLatch2.getCount());

        syncTarget1.onBufferReady();

        // The first sync will still get a callback when it's sync requirements are done.
        finishedLatch1.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch1.getCount());

        finishedLatch2.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    @Test
    public void testMergeSyncAlreadyComplete() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        SurfaceSyncGroup syncGroup1 = new SurfaceSyncGroup(
                transaction -> finishedLatch1.countDown());
        SurfaceSyncGroup syncGroup2 = new SurfaceSyncGroup(
                transaction -> finishedLatch2.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(syncGroup1.addToSync(syncTarget1));
        assertTrue(syncGroup2.addToSync(syncTarget2));
        syncGroup1.markSyncReady();
        syncTarget1.onBufferReady();

        // The first sync will still get a callback when it's sync requirements are done.
        finishedLatch1.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch1.getCount());

        syncGroup2.merge(syncGroup1);
        syncGroup2.markSyncReady();
        syncTarget2.onBufferReady();

        // Verify that the second sync will receive complete since the merged sync was already
        // completed before the merge.
        finishedLatch2.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    private static class SyncTarget implements SurfaceSyncGroup.SyncTarget {
        private SurfaceSyncGroup.TransactionReadyCallback mTransactionReadyCallback;

        @Override
        public void onAddedToSyncGroup(SurfaceSyncGroup parentSyncGroup,
                SurfaceSyncGroup.TransactionReadyCallback transactionReadyCallback) {
            mTransactionReadyCallback = transactionReadyCallback;
        }

        void onBufferReady() {
            SurfaceControl.Transaction t = new StubTransaction();
            mTransactionReadyCallback.onTransactionReady(t);
        }
    }
}
