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
import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.ConditionVariable;
import android.os.Handler;
import android.power.PowerStatsInternal;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.PowerStatsLayout;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

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
    private static final long ENERGY_UNSPECIFIED = -1;

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
        if (!isEnabled()) {
            out.print(getClass().getSimpleName());
            out.println(": disabled");
            return;
        }

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

        for (PowerStats stats : collected) {
            stats.dump(out);
        }
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

    public interface ConsumedEnergyRetriever {

        @NonNull
        int[] getEnergyConsumerIds(@EnergyConsumerType int energyConsumerType);

        String getEnergyConsumerName(int energyConsumerId);

        @Nullable
        EnergyConsumerResult[] getConsumedEnergy(int[] energyConsumerIds);

        /**
         * Returns the last known battery/charger voltage in milli-volts.
         */
        int getVoltageMv();
    }

    static class ConsumedEnergyRetrieverImpl implements ConsumedEnergyRetriever {
        private final PowerStatsInternal mPowerStatsInternal;
        private final IntSupplier mVoltageSupplier;
        private EnergyConsumer[] mEnergyConsumers;

        ConsumedEnergyRetrieverImpl(PowerStatsInternal powerStatsInternal,
                IntSupplier voltageSupplier) {
            mPowerStatsInternal = powerStatsInternal;
            mVoltageSupplier = voltageSupplier;
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

        @NonNull
        @Override
        public int[] getEnergyConsumerIds(int energyConsumerType) {
            ensureEnergyConsumers();

            if (mEnergyConsumers.length == 0) {
                return new int[0];
            }

            List<EnergyConsumer> energyConsumers = new ArrayList<>();
            for (EnergyConsumer energyConsumer : mEnergyConsumers) {
                if (energyConsumer.type == energyConsumerType) {
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
        public int getVoltageMv() {
            return mVoltageSupplier.getAsInt();
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

    class ConsumedEnergyHelper implements PowerStatsUidResolver.Listener {
        private final ConsumedEnergyRetriever mConsumedEnergyRetriever;
        private final @EnergyConsumerType int mEnergyConsumerType;
        private final boolean mPerUidAttributionSupported;

        private boolean mIsInitialized;
        private boolean mFirstCollection = true;
        private int[] mEnergyConsumerIds;
        private long[] mLastConsumedEnergyUws;
        private final SparseLongArray mLastConsumerEnergyPerUid;
        private int mLastVoltageMv;

        ConsumedEnergyHelper(ConsumedEnergyRetriever consumedEnergyRetriever,
                @EnergyConsumerType int energyConsumerType) {
            mConsumedEnergyRetriever = consumedEnergyRetriever;
            mEnergyConsumerType = energyConsumerType;
            mPerUidAttributionSupported = false;
            mLastConsumerEnergyPerUid = null;
        }

        ConsumedEnergyHelper(ConsumedEnergyRetriever consumedEnergyRetriever,
                int energyConsumerId, boolean perUidAttributionSupported) {
            mConsumedEnergyRetriever = consumedEnergyRetriever;
            mEnergyConsumerType = EnergyConsumerType.OTHER;
            mEnergyConsumerIds = new int[]{energyConsumerId};
            mPerUidAttributionSupported = perUidAttributionSupported;
            mLastConsumerEnergyPerUid = mPerUidAttributionSupported ? new SparseLongArray() : null;
        }

        private void ensureInitialized() {
            if (!mIsInitialized) {
                if (mEnergyConsumerIds == null) {
                    mEnergyConsumerIds = mConsumedEnergyRetriever.getEnergyConsumerIds(
                            mEnergyConsumerType);
                }
                mLastConsumedEnergyUws = new long[mEnergyConsumerIds.length];
                Arrays.fill(mLastConsumedEnergyUws, ENERGY_UNSPECIFIED);
                mUidResolver.addListener(this);
                mIsInitialized = true;
            }
        }

        int getEnergyConsumerCount() {
            ensureInitialized();
            return mEnergyConsumerIds.length;
        }

        boolean collectConsumedEnergy(PowerStats powerStats, PowerStatsLayout layout) {
            ensureInitialized();

            if (mEnergyConsumerIds.length == 0) {
                return false;
            }

            int voltageMv = mConsumedEnergyRetriever.getVoltageMv();
            int averageVoltage = mLastVoltageMv != 0 ? (mLastVoltageMv + voltageMv) / 2 : voltageMv;
            if (averageVoltage <= 0) {
                Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMv
                        + " mV) when querying energy consumers");
                return false;
            }

            mLastVoltageMv = voltageMv;

            EnergyConsumerResult[] energy =
                    mConsumedEnergyRetriever.getConsumedEnergy(mEnergyConsumerIds);
            if (energy == null) {
                return false;
            }

            for (int i = 0; i < mEnergyConsumerIds.length; i++) {
                populatePowerStats(powerStats, layout, energy, i, averageVoltage);
            }
            mFirstCollection = false;
            return true;
        }

        private void populatePowerStats(PowerStats powerStats, PowerStatsLayout layout,
                @NonNull EnergyConsumerResult[] energy, int energyConsumerIndex,
                int averageVoltage) {
            long consumedEnergy = energy[energyConsumerIndex].energyUWs;
            long energyDelta = mLastConsumedEnergyUws[energyConsumerIndex] != ENERGY_UNSPECIFIED
                    ? consumedEnergy - mLastConsumedEnergyUws[energyConsumerIndex] : 0;
            mLastConsumedEnergyUws[energyConsumerIndex] = consumedEnergy;
            if (energyDelta < 0) {
                // Likely, restart of powerstats HAL
                energyDelta = 0;
            }

            if (energyDelta == 0 && !mFirstCollection) {
                return;
            }

            layout.setConsumedEnergy(powerStats.stats, energyConsumerIndex,
                    uJtoUc(energyDelta, averageVoltage));

            if (!mPerUidAttributionSupported) {
                return;
            }

            EnergyConsumerAttribution[] perUid = energy[energyConsumerIndex].attribution;
            if (perUid == null) {
                return;
            }

            for (EnergyConsumerAttribution attribution : perUid) {
                int uid = mUidResolver.mapUid(attribution.uid);
                long lastEnergy = mLastConsumerEnergyPerUid.get(uid, ENERGY_UNSPECIFIED);
                mLastConsumerEnergyPerUid.put(uid, attribution.energyUWs);
                if (lastEnergy == ENERGY_UNSPECIFIED) {
                    continue;
                }
                long deltaEnergy = attribution.energyUWs - lastEnergy;
                if (deltaEnergy <= 0) {
                    continue;
                }

                long[] uidStats = powerStats.uidStats.get(uid);
                if (uidStats == null) {
                    uidStats = new long[layout.getUidStatsArrayLength()];
                    powerStats.uidStats.put(uid, uidStats);
                }

                layout.setUidConsumedEnergy(uidStats, energyConsumerIndex,
                        layout.getUidConsumedEnergy(uidStats, energyConsumerIndex)
                                + uJtoUc(deltaEnergy, averageVoltage));
            }
        }

        @Override
        public void onAfterIsolatedUidRemoved(int isolatedUid, int parentUid) {
            if (mLastConsumerEnergyPerUid != null) {
                mHandler.post(() -> mLastConsumerEnergyPerUid.delete(isolatedUid));
            }
        }

        @Override
        public void onIsolatedUidAdded(int isolatedUid, int parentUid) {
        }

        @Override
        public void onBeforeIsolatedUidRemoved(int isolatedUid, int parentUid) {
        }
    }
}
