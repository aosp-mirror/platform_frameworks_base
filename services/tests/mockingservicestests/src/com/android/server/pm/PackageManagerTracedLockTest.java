/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pm;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PackageManagerTracedLockTest {
    PackageManagerTracedLock mLock = new PackageManagerTracedLock();
    PackageManagerTracedLock.RawLock mRawLock = mLock.getRawLock();

    @After
    public void tearDown() {
        if (mRawLock.isHeldByCurrentThread()) {
            mRawLock.unlock();
        }
    }

    @Test
    public void testAcquireLock() {
        assertFalse(mRawLock.isLocked());
        try (var autoClosableLock = mLock.acquireLock()) {
            assertTrue(mRawLock.isHeldByCurrentThread());
            assertTrue(mRawLock.isLocked());
            assertEquals(autoClosableLock.getRawLock(), mRawLock);
            assertEquals(1, mRawLock.getHoldCount());
            try (var autoClosableLock2 = mLock.acquireLock()) {
                assertTrue(mRawLock.isHeldByCurrentThread());
                assertTrue(mRawLock.isLocked());
                assertEquals(autoClosableLock2.getRawLock(), mRawLock);
                assertEquals(2, mRawLock.getHoldCount());
            }
            assertTrue(mRawLock.isHeldByCurrentThread());
            assertTrue(mRawLock.isLocked());
            assertEquals(1, mRawLock.getHoldCount());
        }
        assertFalse(mRawLock.isHeldByCurrentThread());
        assertFalse(mRawLock.isLocked());
        assertEquals(0, mRawLock.getHoldCount());
    }

    @Test
    public void testUnlockInsideTry() {
        assertFalse(mRawLock.isLocked());
        try (var autoClosableLock = mLock.acquireLock()) {
            assertTrue(mRawLock.isHeldByCurrentThread());
            assertTrue(mRawLock.isLocked());
            assertEquals(autoClosableLock.getRawLock(), mRawLock);
            assertEquals(1, mRawLock.getHoldCount());
            mRawLock.unlock();
            assertFalse(mRawLock.isHeldByCurrentThread());
            assertFalse(mRawLock.isLocked());
            assertEquals(0, mRawLock.getHoldCount());
            mRawLock.lock();
        }
        assertFalse(mRawLock.isHeldByCurrentThread());
        assertFalse(mRawLock.isLocked());
        assertEquals(0, mRawLock.getHoldCount());
    }

    @Test
    public void testRawLock() {
        assertFalse(mRawLock.isLocked());
        mRawLock.lock();
        assertTrue(mRawLock.isLocked());
        assertTrue(mRawLock.isHeldByCurrentThread());
        assertEquals(1, mRawLock.getHoldCount());
        assertTrue(mRawLock.tryLock());
        assertTrue(mRawLock.isLocked());
        assertTrue(mRawLock.isHeldByCurrentThread());
        assertEquals(2, mRawLock.getHoldCount());
        mRawLock.unlock();
        assertTrue(mRawLock.isLocked());
        assertTrue(mRawLock.isHeldByCurrentThread());
        assertEquals(1, mRawLock.getHoldCount());
        mRawLock.unlock();
        assertFalse(mRawLock.isLocked());
        assertFalse(mRawLock.isHeldByCurrentThread());
    }

    @Test
    public void testTrylock() throws InterruptedException {
        assertFalse(mRawLock.isLocked());
        HandlerThread thread = new HandlerThread("PackageManagerTracedLockTestThread",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        handler.post(() -> mRawLock.lock());
        waitForHandler(handler);
        assertTrue(mRawLock.isLocked());
        assertFalse(mRawLock.isHeldByCurrentThread());
        assertEquals(0, mRawLock.getHoldCount());
        assertFalse(mRawLock.tryLock());
        handler.post(() -> mRawLock.unlock());
        waitForHandler(handler);
        assertFalse(mRawLock.isLocked());
        assertFalse(mRawLock.isHeldByCurrentThread());
        assertEquals(0, mRawLock.getHoldCount());
        thread.interrupt();
    }

    private void waitForHandler(Handler handler) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(latch::countDown);
        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
