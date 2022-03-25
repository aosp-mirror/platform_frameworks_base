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
        Syncable syncable = new Syncable();
        mSurfaceSyncer.addToSync(startSyncId, syncable);
        mSurfaceSyncer.markSyncReady(startSyncId);

        syncable.onBufferReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testSyncMultiple() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        int startSyncId = mSurfaceSyncer.setupSync(transaction -> finishedLatch.countDown());
        Syncable syncable1 = new Syncable();
        Syncable syncable2 = new Syncable();
        Syncable syncable3 = new Syncable();

        mSurfaceSyncer.addToSync(startSyncId, syncable1);
        mSurfaceSyncer.addToSync(startSyncId, syncable2);
        mSurfaceSyncer.addToSync(startSyncId, syncable3);
        mSurfaceSyncer.markSyncReady(startSyncId);

        syncable1.onBufferReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncable3.onBufferReady();
        assertNotEquals(0, finishedLatch.getCount());

        syncable2.onBufferReady();

        finishedLatch.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch.getCount());
    }

    @Test
    public void testInvalidSyncId() {
        assertFalse(mSurfaceSyncer.addToSync(0, new Syncable()));
    }

    @Test
    public void testAddSyncWhenSyncComplete() throws InterruptedException {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        int startSyncId = mSurfaceSyncer.setupSync(transaction -> finishedLatch.countDown());

        Syncable syncable1 = new Syncable();
        Syncable syncable2 = new Syncable();

        assertTrue(mSurfaceSyncer.addToSync(startSyncId, syncable1));
        mSurfaceSyncer.markSyncReady(startSyncId);
        // Adding to a sync that has been completed is also invalid since the sync id has been
        // cleared.
        assertFalse(mSurfaceSyncer.addToSync(startSyncId, syncable2));
    }

    @Test
    public void testMultipleSyncSets() throws InterruptedException {
        final CountDownLatch finishedLatch1 = new CountDownLatch(1);
        final CountDownLatch finishedLatch2 = new CountDownLatch(1);
        int startSyncId1 = mSurfaceSyncer.setupSync(transaction -> finishedLatch1.countDown());
        int startSyncId2 = mSurfaceSyncer.setupSync(transaction -> finishedLatch2.countDown());

        Syncable syncable1 = new Syncable();
        Syncable syncable2 = new Syncable();

        assertTrue(mSurfaceSyncer.addToSync(startSyncId1, syncable1));
        assertTrue(mSurfaceSyncer.addToSync(startSyncId2, syncable2));
        mSurfaceSyncer.markSyncReady(startSyncId1);
        mSurfaceSyncer.markSyncReady(startSyncId2);

        syncable1.onBufferReady();

        finishedLatch1.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch1.getCount());
        assertNotEquals(0, finishedLatch2.getCount());

        syncable2.onBufferReady();

        finishedLatch2.await(5, TimeUnit.SECONDS);
        assertEquals(0, finishedLatch2.getCount());
    }

    private static class Syncable implements SurfaceSyncer.SyncTarget {
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
