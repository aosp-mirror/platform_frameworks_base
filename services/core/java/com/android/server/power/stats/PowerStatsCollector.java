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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.ConditionVariable;
import android.os.Handler;
import android.power.PowerStatsInternal;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Collects snapshots of power-related system statistics.
 * <p>
 * Instances of this class are intended to be used in a serialized fashion using
 * the handler supplied in the constructor. Thus these objects are not thread-safe
 * except where noted.
 */
public abstract class PowerStatsCollector {
    private static final String TAG = "PowerStatsCollector";
    private static final int MILLIVOLTS_PER_VOLT = 1000;
    private static final long POWER_STATS_ENERGY_CONSUMERS_TIMEOUT = 20000;
    private final Handler mHandler;
    protected final PowerStatsUidResolver mUidResolver;
    protected final Clock mClock;
    private final long mThrottlePeriodMs;
    private final Runnable mCollectAndDeliverStats = this::collectAndDeliverStats;
    private boolean mEnabled;
    private long mLastScheduledUpdateMs = -1;

    @GuardedBy("this")
    private volatile List<Consumer<PowerStats>> mConsumerList = Collections.emptyList();

    public PowerStatsCollector(Handler handler, long throttlePeriodMs,
            PowerStatsUidResolver uidResolver, Clock clock) {
        mHandler = handler;
        mThrottlePeriodMs = throttlePeriodMs;
        mUidResolver = uidResolver;
        mUidResolver.addListener(new PowerStatsUidResolver.Listener() {
            @Override
            public void onIsolatedUidAdded(int isolatedUid, int parentUid) {
            }

            @Override
            public void onBeforeIsolatedUidRemoved(int isolatedUid, int parentUid) {
            }

            @Override
            public void onAfterIsolatedUidRemoved(int isolatedUid, int parentUid) {
                mHandler.post(()->onUidRemoved(isolatedUid));
            }
        });
        mClock = clock;
    }

    /**
     * Adds a consumer that will receive a callback every time a snapshot of stats is collected.
     * The method is thread safe.
     */
    public void addConsumer(Consumer<PowerStats> consumer) {
        synchronized (this) {
            if (mConsumerList.contains(consumer)) {
                return;
            }

            List<Consumer<PowerStats>> newList = new ArrayList<>(mConsumerList);
            newList.add(consumer);
            mConsumerList = Collections.unmodifiableList(newList);
        }
    }

    /**
     * Removes a consumer.
     * The method is thread safe.
     */
    public void removeConsumer(Consumer<PowerStats> consumer) {
        synchronized (this) {
            List<Consumer<PowerStats>> newList = new ArrayList<>(mConsumerList);
            newList.remove(consumer);
            mConsumerList = Collections.unmodifiableList(newList);
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

    /**
     * Performs a PowerStats collection pass and delivers the result to registered consumers.
     */
    @SuppressWarnings("GuardedBy")  // Field is volatile
    public void collectAndDeliverStats() {
        deliverStats(collectStats());
    }

    @Nullable
    protected PowerStats collectStats() {
        return null;
    }

    @SuppressWarnings("GuardedBy")  // Field is volatile
    protected void deliverStats(PowerStats stats) {
        if (stats == null) {
            return;
        }

        List<Consumer<PowerStats>> consumerList = mConsumerList;
        for (int i = consumerList.size() - 1; i >= 0; i--) {
            consumerList.get(i).accept(stats);
        }
    }

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

    protected void onUidRemoved(int uid) {
    }

    /** Calculate charge consumption (in microcoulombs) from a given energy and voltage */
    protected static long uJtoUc(long deltaEnergyUj, int avgVoltageMv) {
        // To overflow, a 3.7V 10000mAh battery would need to completely drain 69244 times
        // since the last snapshot. Round off to the nearest whole long.
        return (deltaEnergyUj * MILLIVOLTS_PER_VOLT + (avgVoltageMv / 2)) / avgVoltageMv;
    }

    interface ConsumedEnergyRetriever {
        @NonNull
        int[] getEnergyConsumerIds(@EnergyConsumerType int energyConsumerType, String name);

        String getEnergyConsumerName(int energyConsumerId);

        @Nullable
        EnergyConsumerResult[] getConsumedEnergy(int[] energyConsumerIds);

        @Nullable
        default long[] getConsumedEnergyUws(int[] energyConsumerIds) {
            EnergyConsumerResult[] results = getConsumedEnergy(energyConsumerIds);
            if (results == null) {
                return null;
            }

            long[] energy = new long[energyConsumerIds.length];
            for (int i = 0; i < energyConsumerIds.length; i++) {
                int id = energyConsumerIds[i];
                for (EnergyConsumerResult result : results) {
                    if (result.id == id) {
                        energy[i] = result.energyUWs;
                        break;
                    }
                }
            }
            return energy;
        }

        default int[] getEnergyConsumerIds(@EnergyConsumerType int energyConsumerType) {
            return getEnergyConsumerIds(energyConsumerType, null);
        }
    }

    static class ConsumedEnergyRetrieverImpl implements ConsumedEnergyRetriever {
        private final PowerStatsInternal mPowerStatsInternal;
        private EnergyConsumer[] mEnergyConsumers;

        ConsumedEnergyRetrieverImpl(PowerStatsInternal powerStatsInternal) {
            mPowerStatsInternal = powerStatsInternal;
        }

        private void ensureEnergyConsumers() {
            if (mEnergyConsumers != null) {
                return;
            }

            if (mPowerStatsInternal == null) {
                mEnergyConsumers = new EnergyConsumer[0];
                return;
            }

            mEnergyConsumers = mPowerStatsInternal.getEnergyConsumerInfo();
            if (mEnergyConsumers == null) {
                mEnergyConsumers = new EnergyConsumer[0];
            }
        }

        @Override
        public int[] getEnergyConsumerIds(int energyConsumerType, String name) {
            ensureEnergyConsumers();

            if (mEnergyConsumers.length == 0) {
                return new int[0];
            }

            List<EnergyConsumer> energyConsumers = new ArrayList<>();
            for (EnergyConsumer energyConsumer : mEnergyConsumers) {
                if (energyConsumer.type == energyConsumerType
                        && (name == null || name.equals(energyConsumer.name))) {
                    energyConsumers.add(energyConsumer);
                }
            }
            if (energyConsumers.isEmpty()) {
                return new int[0];
            }

            energyConsumers.sort(Comparator.comparing(c -> c.ordinal));

            int[] ids = new int[energyConsumers.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = energyConsumers.get(i).id;
            }
            return ids;
        }

        @Override
        public EnergyConsumerResult[] getConsumedEnergy(int[] energyConsumerIds) {
            CompletableFuture<EnergyConsumerResult[]> future =
                    mPowerStatsInternal.getEnergyConsumedAsync(energyConsumerIds);
            try {
                return future.get(POWER_STATS_ENERGY_CONSUMERS_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Slog.e(TAG, "Could not obtain energy consumers from PowerStatsService", e);
            }

            return null;
        }

        @Override
        public String getEnergyConsumerName(int energyConsumerId) {
            ensureEnergyConsumers();

            for (EnergyConsumer energyConsumer : mEnergyConsumers) {
                if (energyConsumer.id == energyConsumerId) {
                    return sanitizeCustomPowerComponentName(energyConsumer);
                }
            }

            Slog.e(TAG, "Unsupported energy consumer ID " + energyConsumerId);
            return "unsupported";
        }

        private String sanitizeCustomPowerComponentName(EnergyConsumer energyConsumer) {
            String name = energyConsumer.name;
            if (name == null || name.isBlank()) {
                name = "CUSTOM_" + energyConsumer.id;
            }
            int length = name.length();
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                char c = name.charAt(i);
                if (Character.isWhitespace(c)) {
                    sb.append(' ');
                } else if (Character.isISOControl(c)) {
                    sb.append('_');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
