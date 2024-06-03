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

package com.android.server.power.stats;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Uses accumulated battery stats data and PowerCalculators to produce power
 * usage data attributed to subsystems and UIDs.
 */
public class BatteryUsageStatsProvider {
    private static final String TAG = "BatteryUsageStatsProv";
    private final Context mContext;
    private final SparseBooleanArray mPowerStatsExporterEnabled = new SparseBooleanArray();
    private final PowerStatsExporter mPowerStatsExporter;
    private final PowerStatsStore mPowerStatsStore;
    private final PowerProfile mPowerProfile;
    private final CpuScalingPolicies mCpuScalingPolicies;
    private final Clock mClock;
    private final Object mLock = new Object();
    private List<PowerCalculator> mPowerCalculators;

    public BatteryUsageStatsProvider(Context context,
            PowerStatsExporter powerStatsExporter,
            PowerProfile powerProfile, CpuScalingPolicies cpuScalingPolicies,
            PowerStatsStore powerStatsStore, Clock clock) {
        mContext = context;
        mPowerStatsExporter = powerStatsExporter;
        mPowerStatsStore = powerStatsStore;
        mPowerProfile = powerProfile;
        mCpuScalingPolicies = cpuScalingPolicies;
        mClock = clock;
    }

    private List<PowerCalculator> getPowerCalculators() {
        synchronized (mLock) {
            if (mPowerCalculators == null) {
                mPowerCalculators = new ArrayList<>();

                // Power calculators are applied in the order of registration
                mPowerCalculators.add(new BatteryChargeCalculator());
                if (!mPowerStatsExporterEnabled.get(BatteryConsumer.POWER_COMPONENT_CPU)) {
                    mPowerCalculators.add(
                            new CpuPowerCalculator(mCpuScalingPolicies, mPowerProfile));
                }
                mPowerCalculators.add(new MemoryPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new WakelockPowerCalculator(mPowerProfile));
                if (!BatteryStats.checkWifiOnly(mContext)) {
                    if (!mPowerStatsExporterEnabled.get(
                            BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)) {
                        mPowerCalculators.add(new MobileRadioPowerCalculator(mPowerProfile));
                    }
                    if (!mPowerStatsExporterEnabled.get(BatteryConsumer.POWER_COMPONENT_PHONE)) {
                        mPowerCalculators.add(new PhonePowerCalculator(mPowerProfile));
                    }
                }
                if (!mPowerStatsExporterEnabled.get(BatteryConsumer.POWER_COMPONENT_WIFI)) {
                    mPowerCalculators.add(new WifiPowerCalculator(mPowerProfile));
                }
                if (!mPowerStatsExporterEnabled.get(BatteryConsumer.POWER_COMPONENT_BLUETOOTH)) {
                    mPowerCalculators.add(new BluetoothPowerCalculator(mPowerProfile));
                }
                mPowerCalculators.add(new SensorPowerCalculator(
                        mContext.getSystemService(SensorManager.class)));
                mPowerCalculators.add(new GnssPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new CameraPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new FlashlightPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AudioPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new VideoPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new ScreenPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AmbientDisplayPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new IdlePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new CustomEnergyConsumerPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new UserPowerCalculator());

                if (!com.android.server.power.optimization.Flags.disableSystemServicePowerAttr()) {
                    // It is important that SystemServicePowerCalculator be applied last,
                    // because it re-attributes some of the power estimated by the other
                    // calculators.
                    mPowerCalculators.add(
                            new SystemServicePowerCalculator(mCpuScalingPolicies, mPowerProfile));
                }
            }
        }
        return mPowerCalculators;
    }

    /**
     * Returns true if the last update was too long ago for the tolerances specified
     * by the supplied queries.
     */
    public static boolean shouldUpdateStats(List<BatteryUsageStatsQuery> queries,
            long elapsedRealtime, long lastUpdateTimeStampMs) {
        long allowableStatsAge = Long.MAX_VALUE;
        for (int i = queries.size() - 1; i >= 0; i--) {
            BatteryUsageStatsQuery query = queries.get(i);
            allowableStatsAge = Math.min(allowableStatsAge, query.getMaxStatsAge());
        }

        return elapsedRealtime - lastUpdateTimeStampMs > allowableStatsAge;
    }

    /**
     * Returns snapshots of battery attribution data, one per supplied query.
     */
    public List<BatteryUsageStats> getBatteryUsageStats(BatteryStatsImpl stats,
            List<BatteryUsageStatsQuery> queries) {
        ArrayList<BatteryUsageStats> results = new ArrayList<>(queries.size());
        synchronized (stats) {
            stats.prepareForDumpLocked();
        }
        final long currentTimeMillis = mClock.currentTimeMillis();
        for (int i = 0; i < queries.size(); i++) {
            results.add(getBatteryUsageStats(stats, queries.get(i), currentTimeMillis));
        }

        return results;
    }

    /**
     * Returns a snapshot of battery attribution data.
     */
    public BatteryUsageStats getBatteryUsageStats(BatteryStatsImpl stats,
            BatteryUsageStatsQuery query) {
        return getBatteryUsageStats(stats, query, mClock.currentTimeMillis());
    }

    private BatteryUsageStats getBatteryUsageStats(BatteryStatsImpl stats,
            BatteryUsageStatsQuery query, long currentTimeMs) {
        if (query.getToTimestamp() == 0) {
            return getCurrentBatteryUsageStats(stats, query, currentTimeMs);
        } else {
            return getAggregatedBatteryUsageStats(stats, query);
        }
    }

    private BatteryUsageStats getCurrentBatteryUsageStats(BatteryStatsImpl stats,
            BatteryUsageStatsQuery query, long currentTimeMs) {
        final long realtimeUs = mClock.elapsedRealtime() * 1000;
        final long uptimeUs = mClock.uptimeMillis() * 1000;

        final boolean includePowerModels = (query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS) != 0;
        final boolean includeProcessStateData = ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA) != 0)
                && stats.isProcessStateDataAvailable();
        final boolean includeVirtualUids =  ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_VIRTUAL_UIDS) != 0);
        final double minConsumedPowerThreshold = query.getMinConsumedPowerThreshold();

        final BatteryUsageStats.Builder batteryUsageStatsBuilder;
        long monotonicStartTime, monotonicEndTime;
        synchronized (stats) {
            monotonicStartTime = stats.getMonotonicStartTime();
            monotonicEndTime = stats.getMonotonicEndTime();

            batteryUsageStatsBuilder = new BatteryUsageStats.Builder(
                    stats.getCustomEnergyConsumerNames(), includePowerModels,
                    includeProcessStateData, minConsumedPowerThreshold);

            // TODO(b/188068523): use a monotonic clock to ensure resilience of order and duration
            // of batteryUsageStats sessions to wall-clock adjustments
            batteryUsageStatsBuilder.setStatsStartTimestamp(stats.getStartClockTime());
            batteryUsageStatsBuilder.setStatsEndTimestamp(currentTimeMs);
            SparseArray<? extends BatteryStats.Uid> uidStats = stats.getUidStats();
            for (int i = uidStats.size() - 1; i >= 0; i--) {
                final BatteryStats.Uid uid = uidStats.valueAt(i);
                if (!includeVirtualUids && uid.getUid() == Process.SDK_SANDBOX_VIRTUAL_UID) {
                    continue;
                }

                batteryUsageStatsBuilder.getOrCreateUidBatteryConsumerBuilder(uid)
                        .setTimeInProcessStateMs(UidBatteryConsumer.PROCESS_STATE_BACKGROUND,
                                getProcessBackgroundTimeMs(uid, realtimeUs))
                        .setTimeInProcessStateMs(UidBatteryConsumer.PROCESS_STATE_FOREGROUND,
                                getProcessForegroundTimeMs(uid, realtimeUs))
                        .setTimeInProcessStateMs(
                                UidBatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE,
                                getProcessForegroundServiceTimeMs(uid, realtimeUs));
            }

            final int[] powerComponents = query.getPowerComponents();
            final List<PowerCalculator> powerCalculators = getPowerCalculators();
            for (int i = 0, count = powerCalculators.size(); i < count; i++) {
                PowerCalculator powerCalculator = powerCalculators.get(i);
                if (powerComponents != null) {
                    boolean include = false;
                    for (int powerComponent : powerComponents) {
                        if (powerCalculator.isPowerComponentSupported(powerComponent)) {
                            include = true;
                            break;
                        }
                    }
                    if (!include) {
                        continue;
                    }
                }
                powerCalculator.calculate(batteryUsageStatsBuilder, stats, realtimeUs, uptimeUs,
                        query);
            }

            if ((query.getFlags()
                    & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_HISTORY) != 0) {
                batteryUsageStatsBuilder.setBatteryHistory(stats.copyHistory());
            }
        }

        if (mPowerStatsExporterEnabled.indexOfValue(true) >= 0) {
            mPowerStatsExporter.exportAggregatedPowerStats(batteryUsageStatsBuilder,
                    monotonicStartTime, monotonicEndTime);
        }

        BatteryUsageStats batteryUsageStats = batteryUsageStatsBuilder.build();
        if (includeProcessStateData) {
            verify(batteryUsageStats);
        }
        return batteryUsageStats;
    }

    // STOPSHIP(b/229906525): remove verification before shipping
    private static boolean sErrorReported;
    private void verify(BatteryUsageStats stats) {
        if (sErrorReported) {
            return;
        }

        final double precision = 2.0;   // Allow rounding errors up to 2 mAh
        final int[] components =
                {BatteryConsumer.POWER_COMPONENT_CPU,
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                        BatteryConsumer.POWER_COMPONENT_WIFI,
                        BatteryConsumer.POWER_COMPONENT_BLUETOOTH};
        final int[] states =
                {BatteryConsumer.PROCESS_STATE_FOREGROUND,
                        BatteryConsumer.PROCESS_STATE_BACKGROUND,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE,
                        BatteryConsumer.PROCESS_STATE_CACHED};
        for (UidBatteryConsumer ubc : stats.getUidBatteryConsumers()) {
            for (int component : components) {
                double consumedPower = ubc.getConsumedPower(ubc.getKey(component));
                double sumStates = 0;
                for (int state : states) {
                    sumStates += ubc.getConsumedPower(ubc.getKey(component, state));
                }
                if (sumStates > consumedPower + precision) {
                    String error = "Sum of states exceeds total. UID = " + ubc.getUid() + " "
                            + BatteryConsumer.powerComponentIdToString(component)
                            + " total = " + consumedPower + " states = " + sumStates;
                    if (!sErrorReported) {
                        Slog.wtf(TAG, error);
                        sErrorReported = true;
                    } else {
                        Slog.e(TAG, error);
                    }
                    return;
                }
            }
        }
    }

    private long getProcessForegroundTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        final long topStateDurationUs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_TOP,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        long foregroundActivityDurationUs = 0;
        final BatteryStats.Timer foregroundActivityTimer = uid.getForegroundActivityTimer();
        if (foregroundActivityTimer != null) {
            foregroundActivityDurationUs = foregroundActivityTimer.getTotalTimeLocked(realtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED);
        }

        // Use the min value of STATE_TOP time and foreground activity time, since both of these
        // times are imprecise
        long totalForegroundDurationUs = Math.min(topStateDurationUs, foregroundActivityDurationUs);

        totalForegroundDurationUs += uid.getProcessStateTime(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, realtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);

        return totalForegroundDurationUs / 1000;
    }

    private long getProcessBackgroundTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        return uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_BACKGROUND,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED)
                / 1000;
    }

    private long getProcessForegroundServiceTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        return uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED)
                / 1000;
    }

    private BatteryUsageStats getAggregatedBatteryUsageStats(BatteryStatsImpl stats,
            BatteryUsageStatsQuery query) {
        final boolean includePowerModels = (query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_POWER_MODELS) != 0;
        final boolean includeProcessStateData = ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_PROCESS_STATE_DATA) != 0)
                && stats.isProcessStateDataAvailable();
        final double minConsumedPowerThreshold = query.getMinConsumedPowerThreshold();

        final String[] customEnergyConsumerNames = stats.getCustomEnergyConsumerNames();
        final BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                customEnergyConsumerNames, includePowerModels, includeProcessStateData,
                minConsumedPowerThreshold);
        if (mPowerStatsStore == null) {
            Log.e(TAG, "PowerStatsStore is unavailable");
            return builder.build();
        }

        List<PowerStatsSpan.Metadata> toc = mPowerStatsStore.getTableOfContents();
        for (PowerStatsSpan.Metadata spanMetadata : toc) {
            if (!spanMetadata.getSections().contains(BatteryUsageStatsSection.TYPE)) {
                continue;
            }

            // BatteryUsageStatsQuery is expressed in terms of wall-clock time range for the
            // session end time.
            //
            // The following algorithm is correct when there is only one time frame in the span.
            // When the wall-clock time is adjusted in the middle of an stats span,
            // constraining it by wall-clock time becomes ambiguous. In this case, the algorithm
            // only covers some situations, but not others.  When using the resulting data for
            // analysis, we should always pay attention to the full set of included timeframes.
            // TODO(b/298459065): switch to monotonic clock
            long minTime = Long.MAX_VALUE;
            long maxTime = 0;
            for (PowerStatsSpan.TimeFrame timeFrame : spanMetadata.getTimeFrames()) {
                long spanEndTime = timeFrame.startTime + timeFrame.duration;
                minTime = Math.min(minTime, spanEndTime);
                maxTime = Math.max(maxTime, spanEndTime);
            }

            // Per BatteryUsageStatsQuery API, the "from" timestamp is *exclusive*,
            // while the "to" timestamp is *inclusive*.
            boolean isInRange =
                    (query.getFromTimestamp() == 0 || minTime > query.getFromTimestamp())
                    && (query.getToTimestamp() == 0 || maxTime <= query.getToTimestamp());
            if (!isInRange) {
                continue;
            }

            PowerStatsSpan powerStatsSpan = mPowerStatsStore.loadPowerStatsSpan(
                    spanMetadata.getId(), BatteryUsageStatsSection.TYPE);
            if (powerStatsSpan == null) {
                continue;
            }

            for (PowerStatsSpan.Section section : powerStatsSpan.getSections()) {
                BatteryUsageStats snapshot =
                        ((BatteryUsageStatsSection) section).getBatteryUsageStats();
                if (!Arrays.equals(snapshot.getCustomPowerComponentNames(),
                        customEnergyConsumerNames)) {
                    Log.w(TAG, "Ignoring older BatteryUsageStats snapshot, which has different "
                            + "custom power components: "
                            + Arrays.toString(snapshot.getCustomPowerComponentNames()));
                    continue;
                }

                if (includeProcessStateData && !snapshot.isProcessStateDataIncluded()) {
                    Log.w(TAG, "Ignoring older BatteryUsageStats snapshot, which "
                            + " does not include process state data");
                    continue;
                }

                builder.add(snapshot);
            }
        }
        return builder.build();
    }

    /**
     * Specify whether PowerStats based attribution is supported for the specified component.
     */
    public void setPowerStatsExporterEnabled(int powerComponentId, boolean enabled) {
        mPowerStatsExporterEnabled.put(powerComponentId, enabled);
    }
}
