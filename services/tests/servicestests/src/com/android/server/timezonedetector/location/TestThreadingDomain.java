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
package com.android.server.timezonedetector.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A ThreadingDomain that simulates idealized post() semantics. Execution takes place in zero time,
 * exactly when scheduled, when the test code explicitly requests it. Execution takes place on the
 * test's main thread.
 */
class TestThreadingDomain extends ThreadingDomain {

    static class QueuedRunnable {

        static final Comparator<? super QueuedRunnable> COMPARATOR =
                (o1, o2) -> (int) (o1.executionTimeMillis - o2.executionTimeMillis);

        @NonNull public final Runnable runnable;
        @Nullable public final Object token;
        public final long executionTimeMillis;

        QueuedRunnable(@NonNull Runnable runnable, @Nullable Object token,
                long executionTimeMillis) {
            this.runnable = Objects.requireNonNull(runnable);
            this.token = token;
            this.executionTimeMillis = executionTimeMillis;
        }

        @Override
        public String toString() {
            return "QueuedRunnable{"
                    + "runnable=" + runnable
                    + ", token=" + token
                    + ", executionTimeMillis=" + executionTimeMillis
                    + '}';
        }
    }

    private long mCurrentTimeMillis;
    private ArrayList<QueuedRunnable> mQueue = new ArrayList<>();

    TestThreadingDomain() {
        // Pick an arbitrary time.
        mCurrentTimeMillis = 123456L;
    }

    @Override
    Thread getThread() {
        return Thread.currentThread();
    }

    @Override
    void post(Runnable r) {
        postDelayed(r, null, 0);
    }

    @Override
    <V> V postAndWait(Callable<V> callable, long durationMillis) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    void postDelayed(Runnable r, long delayMillis) {
        postDelayed(r, null, delayMillis);
    }

    @Override
    void postDelayed(Runnable r, Object token, long delayMillis) {
        mQueue.add(new QueuedRunnable(r, token, mCurrentTimeMillis + delayMillis));
        mQueue.sort(QueuedRunnable.COMPARATOR);
    }

    @Override
    void removeQueuedRunnables(Object token) {
        mQueue.removeIf(runnable -> runnable.token != null && runnable.token == token);
    }

    void removeAllQueuedRunnables() {
        mQueue.clear();
    }

    void assertSingleDelayedQueueItem(Duration expectedDelay) {
        assertQueueLength(1);
        assertNextQueueItemIsDelayed(expectedDelay);
    }

    void assertSingleImmediateQueueItem() {
        assertQueueLength(1);
        assertNextQueueItemIsImmediate();
    }

    void assertQueueLength(int expectedLength) {
        assertEquals(expectedLength, mQueue.size());
    }

    void assertNextQueueItemIsImmediate() {
        assertTrue(getNextQueueItemDelayMillis() == 0);
    }

    private void assertNextQueueItemIsDelayed(Duration expectedDelay) {
        assertEquals(getNextQueueItemDelayMillis(), expectedDelay.toMillis());
    }

    void assertQueueEmpty() {
        assertTrue(mQueue.isEmpty());
    }

    long getNextQueueItemDelayMillis() {
        assertFalse(mQueue.isEmpty());
        return mQueue.get(0).executionTimeMillis - mCurrentTimeMillis;
    }

    void executeNext() {
        assertFalse(mQueue.isEmpty());
        QueuedRunnable queued = mQueue.remove(0);

        mCurrentTimeMillis = queued.executionTimeMillis;
        queued.runnable.run();
    }
}
