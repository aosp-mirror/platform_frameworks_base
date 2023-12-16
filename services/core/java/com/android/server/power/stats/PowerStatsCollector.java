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
import android.os.PersistableBundle;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final Handler mHandler;
    protected final Clock mClock;
    private final long mThrottlePeriodMs;
    private final Runnable mCollectAndDeliverStats = this::collectAndDeliverStats;
    private boolean mEnabled;
    private long mLastScheduledUpdateMs = -1;

    /**
     * Captures the positions and lengths of sections of the stats array, such as usage duration,
     * power usage estimates etc.
     */
    public static class StatsArrayLayout {
        private static final String EXTRA_DEVICE_POWER_POSITION = "dp";
        private static final String EXTRA_DEVICE_DURATION_POSITION = "dd";
        private static final String EXTRA_DEVICE_ENERGY_CONSUMERS_POSITION = "de";
        private static final String EXTRA_DEVICE_ENERGY_CONSUMERS_COUNT = "dec";
        private static final String EXTRA_UID_POWER_POSITION = "up";

        protected static final double MILLI_TO_NANO_MULTIPLIER = 1000000.0;

        private int mDeviceStatsArrayLength;
        private int mUidStatsArrayLength;

        protected int mDeviceDurationPosition;
        private int mDeviceEnergyConsumerPosition;
        private int mDeviceEnergyConsumerCount;
        private int mDevicePowerEstimatePosition;
        private int mUidPowerEstimatePosition;

        public int getDeviceStatsArrayLength() {
            return mDeviceStatsArrayLength;
        }

        public int getUidStatsArrayLength() {
            return mUidStatsArrayLength;
        }

        protected int addDeviceSection(int length) {
            int position = mDeviceStatsArrayLength;
            mDeviceStatsArrayLength += length;
            return position;
        }

        protected int addUidSection(int length) {
            int position = mUidStatsArrayLength;
            mUidStatsArrayLength += length;
            return position;
        }

        /**
         * Declare that the stats array has a section capturing usage duration
         */
        public void addDeviceSectionUsageDuration() {
            mDeviceDurationPosition = addDeviceSection(1);
        }

        /**
         * Saves the usage duration in the corresponding <code>stats</code> element.
         */
        public void setUsageDuration(long[] stats, long value) {
            stats[mDeviceDurationPosition] = value;
        }

        /**
         * Extracts the usage duration from the corresponding <code>stats</code> element.
         */
        public long getUsageDuration(long[] stats) {
            return stats[mDeviceDurationPosition];
        }

        /**
         * Declares that the stats array has a section capturing EnergyConsumer data from
         * PowerStatsService.
         */
        public void addDeviceSectionEnergyConsumers(int energyConsumerCount) {
            mDeviceEnergyConsumerPosition = addDeviceSection(energyConsumerCount);
            mDeviceEnergyConsumerCount = energyConsumerCount;
        }

        public int getEnergyConsumerCount() {
            return mDeviceEnergyConsumerCount;
        }

        /**
         * Saves the accumulated energy for the specified rail the corresponding
         * <code>stats</code> element.
         */
        public void setConsumedEnergy(long[] stats, int index, long energy) {
            stats[mDeviceEnergyConsumerPosition + index] = energy;
        }

        /**
         * Extracts the EnergyConsumer data from a device stats array for the specified
         * EnergyConsumer.
         */
        public long getConsumedEnergy(long[] stats, int index) {
            return stats[mDeviceEnergyConsumerPosition + index];
        }

        /**
         * Declare that the stats array has a section capturing a power estimate
         */
        public void addDeviceSectionPowerEstimate() {
            mDevicePowerEstimatePosition = addDeviceSection(1);
        }

        /**
         * Converts the supplied mAh power estimate to a long and saves it in the corresponding
         * element of <code>stats</code>.
         */
        public void setDevicePowerEstimate(long[] stats, double power) {
            stats[mDevicePowerEstimatePosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
        }

        /**
         * Extracts the power estimate from a device stats array and converts it to mAh.
         */
        public double getDevicePowerEstimate(long[] stats) {
            return stats[mDevicePowerEstimatePosition] / MILLI_TO_NANO_MULTIPLIER;
        }

        /**
         * Declare that the UID stats array has a section capturing a power estimate
         */
        public void addUidSectionPowerEstimate() {
            mUidPowerEstimatePosition = addUidSection(1);
        }

        /**
         * Converts the supplied mAh power estimate to a long and saves it in the corresponding
         * element of <code>stats</code>.
         */
        public void setUidPowerEstimate(long[] stats, double power) {
            stats[mUidPowerEstimatePosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
        }

        /**
         * Extracts the power estimate from a UID stats array and converts it to mAh.
         */
        public double getUidPowerEstimate(long[] stats) {
            return stats[mUidPowerEstimatePosition] / MILLI_TO_NANO_MULTIPLIER;
        }

        /**
         * Copies the elements of the stats array layout into <code>extras</code>
         */
        public void toExtras(PersistableBundle extras) {
            extras.putInt(EXTRA_DEVICE_DURATION_POSITION, mDeviceDurationPosition);
            extras.putInt(EXTRA_DEVICE_ENERGY_CONSUMERS_POSITION,
                    mDeviceEnergyConsumerPosition);
            extras.putInt(EXTRA_DEVICE_ENERGY_CONSUMERS_COUNT,
                    mDeviceEnergyConsumerCount);
            extras.putInt(EXTRA_DEVICE_POWER_POSITION, mDevicePowerEstimatePosition);
            extras.putInt(EXTRA_UID_POWER_POSITION, mUidPowerEstimatePosition);
        }

        /**
         * Retrieves elements of the stats array layout from <code>extras</code>
         */
        public void fromExtras(PersistableBundle extras) {
            mDeviceDurationPosition = extras.getInt(EXTRA_DEVICE_DURATION_POSITION);
            mDeviceEnergyConsumerPosition = extras.getInt(EXTRA_DEVICE_ENERGY_CONSUMERS_POSITION);
            mDeviceEnergyConsumerCount = extras.getInt(EXTRA_DEVICE_ENERGY_CONSUMERS_COUNT);
            mDevicePowerEstimatePosition = extras.getInt(EXTRA_DEVICE_POWER_POSITION);
            mUidPowerEstimatePosition = extras.getInt(EXTRA_UID_POWER_POSITION);
        }

        protected void putIntArray(PersistableBundle extras, String key, int[] array) {
            if (array == null) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (int value : array) {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(value);
            }
            extras.putString(key, sb.toString());
        }

        protected int[] getIntArray(PersistableBundle extras, String key) {
            String string = extras.getString(key);
            if (string == null) {
                return null;
            }
            String[] values = string.trim().split(",");
            int[] result = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                try {
                    result[i] = Integer.parseInt(values[i]);
                } catch (NumberFormatException e) {
                    Slog.wtf(TAG, "Invalid CSV format: " + string);
                    return null;
                }
            }
            return result;
        }
    }

    @GuardedBy("this")
    @SuppressWarnings("unchecked")
    private volatile List<Consumer<PowerStats>> mConsumerList = Collections.emptyList();

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
    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("GuardedBy")  // Field is volatile
    public void collectAndDeliverStats() {
        PowerStats stats = collectStats();
        if (stats == null) {
            return;
        }
        List<Consumer<PowerStats>> consumerList = mConsumerList;
        for (int i = consumerList.size() - 1; i >= 0; i--) {
            consumerList.get(i).accept(stats);
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
