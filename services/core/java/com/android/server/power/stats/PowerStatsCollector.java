/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import android.annotation.Nullable;
import android.os.ConditionVariable;
import android.os.Handler;
import android.util.FastImmutableArraySet;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Collects snapshots of power-related system statistics.
 * <p>
 * Instances of this class are intended to be used in a serialized fashion using
 * the handler supplied in the constructor. Thus these objects are not thread-safe
 * except where noted.
 */
public abstract class PowerStatsCollector {
    private static final int MILLIVOLTS_PER_VOLT = 1000;
    private final Handler mHandler;
    protected final Clock mClock;
    private final long mThrottlePeriodMs;
    private final Runnable mCollectAndDeliverStats = this::collectAndDeliverStats;
    private boolean mEnabled;
    private long mLastScheduledUpdateMs = -1;

    @GuardedBy("this")
    @SuppressWarnings("unchecked")
    private volatile FastImmutableArraySet<Consumer<PowerStats>> mConsumerList =
            new FastImmutableArraySet<Consumer<PowerStats>>(new Consumer[0]);

    public PowerStatsCollector(Handler handler, long throttlePeriodMs, Clock clock) {
        mHandler = handler;
        mThrottlePeriodMs = throttlePeriodMs;
        mClock = clock;
    }

    /**
     * Adds a consumer that will receive a callback every time a snapshot of stats is collected.
     * The method is thread safe.
     */
    @SuppressWarnings("unchecked")
    public void addConsumer(Consumer<PowerStats> consumer) {
        synchronized (this) {
            mConsumerList = new FastImmutableArraySet<Consumer<PowerStats>>(
                    Stream.concat(mConsumerList.stream(), Stream.of(consumer))
                            .toArray(Consumer[]::new));
        }
    }

    /**
     * Removes a consumer.
     * The method is thread safe.
     */
    @SuppressWarnings("unchecked")
    public void removeConsumer(Consumer<PowerStats> consumer) {
        synchronized (this) {
            mConsumerList = new FastImmutableArraySet<Consumer<PowerStats>>(
                    mConsumerList.stream().filter(c -> c != consumer)
                            .toArray(Consumer[]::new));
        }
    }

    /**
     * Should be called at most once, before the first invocation of {@link #schedule} or
     * {@link #forceSchedule}
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Returns true if the collector is enabled.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    @SuppressWarnings("GuardedBy")  // Field is volatile
    private void collectAndDeliverStats() {
        PowerStats stats = collectStats();
        if (stats == null) {
            return;
        }
        for (Consumer<PowerStats> consumer : mConsumerList) {
            consumer.accept(stats);
        }
    }

    /**
     * Schedules a stats snapshot collection, throttled in accordance with the
     * {@link #mThrottlePeriodMs} parameter.
     */
    public boolean schedule() {
        if (!mEnabled) {
            return false;
        }

        long uptimeMillis = mClock.uptimeMillis();
        if (uptimeMillis - mLastScheduledUpdateMs < mThrottlePeriodMs
                && mLastScheduledUpdateMs >= 0) {
            return false;
        }
        mLastScheduledUpdateMs = uptimeMillis;
        mHandler.post(mCollectAndDeliverStats);
        return true;
    }

    /**
     * Schedules an immediate snapshot collection, foregoing throttling.
     */
    public boolean forceSchedule() {
        if (!mEnabled) {
            return false;
        }

        mHandler.removeCallbacks(mCollectAndDeliverStats);
        mHandler.postAtFrontOfQueue(mCollectAndDeliverStats);
        return true;
    }

    @Nullable
    protected abstract PowerStats collectStats();

    /**
     * Collects a fresh stats snapshot and prints it to the supplied printer.
     */
    public void collectAndDump(PrintWriter pw) {
        if (Thread.currentThread() == mHandler.getLooper().getThread()) {
            throw new RuntimeException(
                    "Calling this method from the handler thread would cause a deadlock");
        }

        IndentingPrintWriter out = new IndentingPrintWriter(pw);
        out.print(getClass().getSimpleName());
        if (!isEnabled()) {
            out.println(": disabled");
            return;
        }
        out.println();

        ArrayList<PowerStats> collected = new ArrayList<>();
        Consumer<PowerStats> consumer = collected::add;
        addConsumer(consumer);

        try {
            if (forceSchedule()) {
                awaitCompletion();
            }
        } finally {
            removeConsumer(consumer);
        }

        out.increaseIndent();
        for (PowerStats stats : collected) {
            stats.dump(out);
        }
        out.decreaseIndent();
    }

    private void awaitCompletion() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }

    /** Calculate charge consumption (in microcoulombs) from a given energy and voltage */
    protected long uJtoUc(long deltaEnergyUj, int avgVoltageMv) {
        // To overflow, a 3.7V 10000mAh battery would need to completely drain 69244 times
        // since the last snapshot. Round off to the nearest whole long.
        return (deltaEnergyUj * MILLIVOLTS_PER_VOLT + (avgVoltageMv / 2)) / avgVoltageMv;
    }
}
