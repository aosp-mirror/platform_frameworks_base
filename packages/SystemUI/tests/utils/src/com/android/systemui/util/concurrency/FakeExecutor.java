/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util.concurrency;

import com.android.systemui.util.time.FakeSystemClock;

import java.util.Collections;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeExecutor implements DelayableExecutor {
    private final FakeSystemClock mClock;
    private PriorityQueue<QueuedRunnable> mQueuedRunnables = new PriorityQueue<>();
    private boolean mIgnoreClockUpdates;
    private boolean mExecuting;

    /**
     * Initializes a fake executor.
     *
     * @param clock FakeSystemClock allowing control over delayed runnables. It is strongly
     *              recommended that this clock have its auto-increment setting set to false to
     *              prevent unexpected advancement of the time.
     */
    public FakeExecutor(FakeSystemClock clock) {
        mClock = clock;
        mClock.addListener(() -> {
            if (!mIgnoreClockUpdates) {
                runAllReady();
            }
        });
    }

    /**
     * Runs a single runnable if it's scheduled to run according to the internal clock.
     *
     * If constructed to advance the clock automatically, this will advance the clock enough to
     * run the next pending item.
     *
     * This method does not advance the clock past the item that was run.
     *
     * @return Returns true if an item was run.
     */
    public boolean runNextReady() {
        if (!mQueuedRunnables.isEmpty() && mQueuedRunnables.peek().mWhen <= mClock.uptimeMillis()) {
            mExecuting = true;
            mQueuedRunnables.poll().mRunnable.run();
            mExecuting = false;
            return true;
        }

        return false;
    }

    /**
     * Runs all Runnables that are scheduled to run according to the internal clock.
     *
     * If constructed to advance the clock automatically, this will advance the clock enough to
     * run all the pending items. This method does not advance the clock past items that were
     * run. It is equivalent to calling {@link #runNextReady()} in a loop.
     *
     * @return Returns the number of items that ran.
     */
    public int runAllReady() {
        int num = 0;
        while (runNextReady()) {
            num++;
        }

        return num;
    }

    /**
     * Advances the internal clock to the next item to run.
     *
     * The clock will only move forward. If the next item is set to run in the past or there is no
     * next item, the clock does not change.
     *
     * Note that this will cause one or more items to actually run.
     *
     * @return The delta in uptimeMillis that the clock advanced, or 0 if the clock did not advance.
     */
    public long advanceClockToNext() {
        if (mQueuedRunnables.isEmpty()) {
            return 0;
        }

        long startTime = mClock.uptimeMillis();
        long nextTime = mQueuedRunnables.peek().mWhen;
        if (nextTime <= startTime) {
            return 0;
        }
        updateClock(nextTime);

        return nextTime - startTime;
    }


    /**
     * Advances the internal clock to the last item to run.
     *
     * The clock will only move forward. If the last item is set to run in the past or there is no
     * next item, the clock does not change.
     *
     * @return The delta in uptimeMillis that the clock advanced, or 0 if the clock did not advance.
     */
    public long advanceClockToLast() {
        if (mQueuedRunnables.isEmpty()) {
            return 0;
        }

        long startTime = mClock.uptimeMillis();
        long nextTime = Collections.max(mQueuedRunnables).mWhen;
        if (nextTime <= startTime) {
            return 0;
        }

        updateClock(nextTime);

        return nextTime - startTime;
    }

    /**
     * Returns the number of un-executed runnables waiting to run.
     */
    public int numPending() {
        return mQueuedRunnables.size();
    }

    @Override
    public Runnable executeDelayed(Runnable r, long delay, TimeUnit unit) {
        if (delay < 0) {
            delay = 0;
        }
        return executeAtTime(r, mClock.uptimeMillis() + unit.toMillis(delay));
    }

    @Override
    public Runnable executeAtTime(Runnable r, long uptime, TimeUnit unit) {
        long uptimeMillis = unit.toMillis(uptime);

        QueuedRunnable container = new QueuedRunnable(r, uptimeMillis);

        mQueuedRunnables.offer(container);

        return () -> mQueuedRunnables.remove(container);
    }

    @Override
    public void execute(Runnable command) {
        executeDelayed(command, 0);
    }

    public boolean isExecuting() {
        return mExecuting;
    }

    /**
     * Run all Executors in a loop until they all report they have no ready work to do.
     *
     * Useful if you have Executors the post work to other Executors, and you simply want to
     * run them all until they stop posting work.
     */
    public static void exhaustExecutors(FakeExecutor ...executors) {
        boolean didAnything;
        do {
            didAnything = false;
            for (FakeExecutor executor : executors) {
                didAnything = didAnything || executor.runAllReady() != 0;
            }
        } while (didAnything);
    }

    private void updateClock(long nextTime) {
        mIgnoreClockUpdates = true;
        mClock.setUptimeMillis(nextTime);
        mIgnoreClockUpdates = false;
    }

    private static class QueuedRunnable implements Comparable<QueuedRunnable> {
        private static AtomicInteger sCounter = new AtomicInteger();

        Runnable mRunnable;
        long mWhen;
        private int mCounter;

        private QueuedRunnable(Runnable r, long when) {
            mRunnable = r;
            mWhen = when;

            // PrioirityQueue orders items arbitrarily when equal. We want to ensure that
            // otherwise-equal elements are ordered according to their insertion order. Because this
            // class only is constructed right before insertion, we use a static counter to track
            // insertion order of otherwise equal elements.
            mCounter = sCounter.incrementAndGet();
        }

        @Override
        public int compareTo(QueuedRunnable other) {
            long diff = mWhen - other.mWhen;

            if (diff == 0) {
                return mCounter - other.mCounter;
            }

            return diff > 0 ? 1 : -1;
        }
    }
}
