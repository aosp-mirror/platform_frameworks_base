/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.location.timezone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;

import com.android.server.location.timezone.ThreadingDomain.SingleRunnableQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests for {@link HandlerThreadingDomain}. */
@Presubmit
public class HandlerThreadingDomainTest {

    private HandlerThread mHandlerThread;
    private Handler mTestHandler;

    @Before
    public void setUp() {
        mHandlerThread = new HandlerThread("HandlerThreadingDomainTest");
        mHandlerThread.start();
        mTestHandler = new Handler(mHandlerThread.getLooper());
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test
    public void getLockObject() {
        ThreadingDomain domain = new HandlerThreadingDomain(mTestHandler);
        assertSame("LockObject must be consistent", domain.getLockObject(), domain.getLockObject());
    }

    @Test
    public void assertCurrentThread() throws Exception {
        ThreadingDomain domain = new HandlerThreadingDomain(mTestHandler);

        // Expect an exception (current thread != handler thread)
        try {
            domain.assertCurrentThread();
            fail("Expected exception");
        } catch (RuntimeException expected) {
            // Expected
        }

        // Expect no exception (current thread == handler thread)
        AtomicBoolean exceptionThrown = new AtomicBoolean(true);
        LatchedRunnable testCode = new LatchedRunnable(() -> {
            domain.assertCurrentThread();
            exceptionThrown.set(false);
        });
        mTestHandler.post(testCode);
        testCode.assertCompletesWithin(60, TimeUnit.SECONDS);
        assertFalse(exceptionThrown.get());
    }

    @Test
    public void assertNotCurrentThread() throws Exception {
        ThreadingDomain domain = new HandlerThreadingDomain(mTestHandler);

        // Expect no exception (current thread != handler thread)
        domain.assertNotCurrentThread();

        AtomicBoolean exceptionThrown = new AtomicBoolean(false);
        LatchedRunnable testCode = new LatchedRunnable(() -> {
            // Expect an exception (current thread == handler thread)
            try {
                domain.assertNotCurrentThread();
                fail("Expected exception");
            } catch (RuntimeException expected) {
                exceptionThrown.set(true);
            }
        });
        mTestHandler.post(testCode);
        testCode.assertCompletesWithin(60, TimeUnit.SECONDS);
        assertTrue(exceptionThrown.get());
    }

    @Test
    public void post() throws Exception {
        ThreadingDomain domain = new HandlerThreadingDomain(mTestHandler);
        AtomicBoolean ranOnExpectedThread = new AtomicBoolean(false);
        LatchedRunnable testLogic = new LatchedRunnable(() -> {
            ranOnExpectedThread.set(Thread.currentThread() == mTestHandler.getLooper().getThread());
        });
        domain.post(testLogic);
        testLogic.assertCompletesWithin(60, TimeUnit.SECONDS);
        assertTrue(ranOnExpectedThread.get());
    }

    @Test
    public void postDelayed() throws Exception {
        ThreadingDomain domain = new HandlerThreadingDomain(mTestHandler);

        long beforeExecutionNanos = System.nanoTime();
        AtomicBoolean ranOnExpectedThread = new AtomicBoolean(false);
        LatchedRunnable testLogic = new LatchedRunnable(() -> {
            ranOnExpectedThread.set(Thread.currentThread() == mTestHandler.getLooper().getThread());
        });
        domain.postDelayed(testLogic, 5000);

        testLogic.assertCompletesWithin(60, TimeUnit.SECONDS);
        assertTrue(ranOnExpectedThread.get());

        long afterExecutionNanos = System.nanoTime();
        assertTrue(afterExecutionNanos - beforeExecutionNanos >= TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    public void singleRunnableQueue_runDelayed() throws Exception {
        ThreadingDomain domain = new HandlerThreadingDomain(mTestHandler);
        SingleRunnableQueue singleRunnableQueue = domain.createSingleRunnableQueue();

        long beforeExecutionNanos = System.nanoTime();

        Runnable noOpRunnable = () -> {
            // Deliberately do nothing
        };
        LatchedRunnable firstRunnable = new LatchedRunnable(noOpRunnable);
        LatchedRunnable secondRunnable = new LatchedRunnable(noOpRunnable);

        // Calls to SingleRunnableQueue must be made on the handler thread it is associated with,
        // so this uses runWithScissors() to block until the runDelayedTestRunnable has completed.
        Runnable runDelayedTestRunnable = () -> {
            singleRunnableQueue.runDelayed(firstRunnable, TimeUnit.SECONDS.toMillis(10));

            // The second runnable posted must clear the first.
            singleRunnableQueue.runDelayed(secondRunnable, TimeUnit.SECONDS.toMillis(10));
        };
        mTestHandler.runWithScissors(runDelayedTestRunnable, TimeUnit.SECONDS.toMillis(60));

        // Now wait for the second runnable to complete
        secondRunnable.assertCompletesWithin(60, TimeUnit.SECONDS);
        assertFalse(firstRunnable.isComplete());

        long afterExecutionNanos = System.nanoTime();
        assertTrue(afterExecutionNanos - beforeExecutionNanos >= TimeUnit.SECONDS.toNanos(10));
    }

    private static boolean awaitWithRuntimeException(
            CountDownLatch latch, long timeout, TimeUnit timeUnit) {
        try {
            return latch.await(timeout, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static class LatchedRunnable implements Runnable {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final Runnable mRunnable;

        LatchedRunnable(Runnable mRunnable) {
            this.mRunnable = Objects.requireNonNull(mRunnable);
        }

        @Override
        public void run() {
            try {
                mRunnable.run();
            } finally {
                mLatch.countDown();
            }
        }

        boolean isComplete() {
            return mLatch.getCount() == 0;
        }

        boolean waitForCompletion(long timeout, TimeUnit unit) {
            return awaitWithRuntimeException(mLatch, timeout, unit);
        }

        void assertCompletesWithin(long timeout, TimeUnit unit) {
            assertTrue("Runnable did not execute in time", waitForCompletion(timeout, unit));
        }
    }
}
