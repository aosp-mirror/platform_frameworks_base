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
import android.window.SurfaceSyncer;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
public class SurfaceSyncerTest {
    private SurfaceSyncer mSurfaceSyncer;

    @Before
    public void setup() {
        mSurfaceSyncer = new SurfaceSyncer();
        SurfaceSyncer.setTransactionFactory(StubTransaction::new);
    }

    @Test
    public void testSyncOne() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        int startSyncId = mSurfaceSyncer.setupSync(transaction -> finishedLatch.countDown());
        SyncTarget syncTarget = new SyncTarget();
        mSurfaceSyncer.addToSync(startSyncId, syncTarget);
        mSurfaceSyncer.markSyncReady(startSyncId);

        syncTarget.onBufferReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testSyncMultiple() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        int startSyncId = mSurfaceSyncer.setupSync(transaction -> finishedLatch.countDown());
        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();
        SyncTarget syncTarget3 = new SyncTarget();

        mSurfaceSyncer.addToSync(startSyncId, syncTarget1);
        mSurfaceSyncer.addToSync(startSyncId, syncTarget2);
        mSurfaceSyncer.addToSync(startSyncId, syncTarget3);
        mSurfaceSyncer.markSyncReady(startSyncId);

        syncTarget1.onBufferReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncTarget3.onBufferReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncTarget2.onBufferReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testInvalidSyncId() {
        assertFalse(mSurfaceSyncer.addToSync(0, new SyncTarget()));
    }

    @Test
    public void testAddSyncWhenSyncComplete() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        int startSyncId = mSurfaceSyncer.setupSync(transaction -> finishedLatch.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(mSurfaceSyncer.addToSync(startSyncId, syncTarget1));
        mSurfaceSyncer.markSyncReady(startSyncId);
        // Adding to a sync that has been completed is also invalid since the sync id has been
        // cleared.
        assertFalse(mSurfaceSyncer.addToSync(startSyncId, syncTarget2));
    }

    @Test
    public void testMultipleSyncSets() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        int startSyncId1 = mSurfaceSyncer.setupSync(transaction -> finishedLatch1.countDown());
        int startSyncId2 = mSurfaceSyncer.setupSync(transaction -> finishedLatch2.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(mSurfaceSyncer.addToSync(startSyncId1, syncTarget1));
        assertTrue(mSurfaceSyncer.addToSync(startSyncId2, syncTarget2));
        mSurfaceSyncer.markSyncReady(startSyncId1);
        mSurfaceSyncer.markSyncReady(startSyncId2);

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
        int startSyncId1 = mSurfaceSyncer.setupSync(transaction -> finishedLatch1.countDown());
        int startSyncId2 = mSurfaceSyncer.setupSync(transaction -> finishedLatch2.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(mSurfaceSyncer.addToSync(startSyncId1, syncTarget1));
        assertTrue(mSurfaceSyncer.addToSync(startSyncId2, syncTarget2));
        mSurfaceSyncer.markSyncReady(startSyncId1);
        mSurfaceSyncer.merge(startSyncId2, startSyncId1, mSurfaceSyncer);
        mSurfaceSyncer.markSyncReady(startSyncId2);

        // Finish syncTarget2 first to test that the syncSet is not complete until the merged sync
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
        int startSyncId1 = mSurfaceSyncer.setupSync(transaction -> finishedLatch1.countDown());
        int startSyncId2 = mSurfaceSyncer.setupSync(transaction -> finishedLatch2.countDown());

        SyncTarget syncTarget1 = new SyncTarget();
        SyncTarget syncTarget2 = new SyncTarget();

        assertTrue(mSurfaceSyncer.addToSync(startSyncId1, syncTarget1));
        assertTrue(mSurfaceSyncer.addToSync(startSyncId2, syncTarget2));
        mSurfaceSyncer.markSyncReady(startSyncId1);
        syncTarget1.onBufferReady();

        // The first sync will still get a callback when it's sync requirements are done.
        finishedLatch1.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch1.getCount());

        mSurfaceSyncer.merge(startSyncId2, startSyncId1, mSurfaceSyncer);
        mSurfaceSyncer.markSyncReady(startSyncId2);
        syncTarget2.onBufferReady();

        // Verify that the second sync will receive complete since the merged sync was already
        // completed before the merge.
        finishedLatch2.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    private static class SyncTarget implements SurfaceSyncer.SyncTarget {
        private SurfaceSyncer.SyncBufferCallback mSyncBufferCallback;

        @Override
        public void onReadyToSync(SurfaceSyncer.SyncBufferCallback syncBufferCallback) {
            mSyncBufferCallback = syncBufferCallback;
        }

        void onBufferReady() {
            SurfaceControl.Transaction t = new StubTransaction();
            mSyncBufferCallback.onBufferReady(t);
        }
    }
}
