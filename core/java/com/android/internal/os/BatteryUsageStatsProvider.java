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

package com.android.internal.os;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Bundle;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses accumulated battery stats data and PowerCalculators to produce power
 * usage data attributed to subsystems and UIDs.
 */
public class BatteryUsageStatsProvider {
    private final Context mContext;
    private final BatteryStatsImpl mStats;
    private final PowerProfile mPowerProfile;
    private final Object mLock = new Object();
    private List<PowerCalculator> mPowerCalculators;

    public BatteryUsageStatsProvider(Context context, BatteryStatsImpl stats) {
        mContext = context;
        mStats = stats;
        mPowerProfile = new PowerProfile(mContext);
    }

    private List<PowerCalculator> getPowerCalculators() {
        synchronized (mLock) {
            if (mPowerCalculators == null) {
                mPowerCalculators = new ArrayList<>();

                // Power calculators are applied in the order of registration
                mPowerCalculators.add(new BatteryChargeCalculator(mPowerProfile));
                mPowerCalculators.add(new CpuPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new MemoryPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new WakelockPowerCalculator(mPowerProfile));
                if (!BatteryStatsHelper.checkWifiOnly(mContext)) {
                    mPowerCalculators.add(new MobileRadioPowerCalculator(mPowerProfile));
                }
                mPowerCalculators.add(new WifiPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new BluetoothPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new SensorPowerCalculator(
                        mContext.getSystemService(SensorManager.class)));
                mPowerCalculators.add(new GnssPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new CameraPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new FlashlightPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AudioPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new VideoPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new PhonePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new ScreenPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new AmbientDisplayPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new IdlePowerCalculator(mPowerProfile));
                mPowerCalculators.add(new CustomMeasuredPowerCalculator(mPowerProfile));
                mPowerCalculators.add(new UserPowerCalculator());

                // It is important that SystemServicePowerCalculator be applied last,
                // because it re-attributes some of the power estimated by the other
                // calculators.
                mPowerCalculators.add(new SystemServicePowerCalculator(mPowerProfile));
            }
        }
        return mPowerCalculators;
    }

    /**
     * Returns snapshots of battery attribution data, one per supplied query.
     */
    public List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries) {

        // TODO(b/174186345): instead of BatteryStatsHelper, use PowerCalculators directly.
        final BatteryStatsHelper batteryStatsHelper = new BatteryStatsHelper(mContext,
                false /* collectBatteryBroadcast */);
        batteryStatsHelper.create((Bundle) null);
        final List<UserHandle> users = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            BatteryUsageStatsQuery query = queries.get(i);
            for (int userId : query.getUserIds()) {
                UserHandle userHandle = UserHandle.of(userId);
                if (!users.contains(userHandle)) {
                    users.add(userHandle);
                }
            }
        }
        batteryStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, users);

        ArrayList<BatteryUsageStats> results = new ArrayList<>(queries.size());
        for (int i = 0; i < queries.size(); i++) {
            results.add(getBatteryUsageStats(queries.get(i)));
        }
        return results;
    }

    /**
     * Returns a snapshot of battery attribution data.
     */
    @VisibleForTesting
    public BatteryUsageStats getBatteryUsageStats(BatteryUsageStatsQuery query) {
        final long realtimeUs = mStats.mClocks.elapsedRealtime() * 1000;
        final long uptimeUs = mStats.mClocks.uptimeMillis() * 1000;

        final long[] customMeasuredChargesUC =
                mStats.getCustomConsumerMeasuredBatteryConsumptionUC();
        final int customPowerComponentCount = customMeasuredChargesUC != null
                ? customMeasuredChargesUC.length
                : 0;

        // TODO(b/174186358): read extra time component number from configuration
        final int customTimeComponentCount = 0;

        final BatteryUsageStats.Builder batteryUsageStatsBuilder =
                new BatteryUsageStats.Builder(customPowerComponentCount, customTimeComponentCount)
                        .setStatsStartTimestamp(mStats.getStartClockTime());

        SparseArray<? extends BatteryStats.Uid> uidStats = mStats.getUidStats();
        for (int i = uidStats.size() - 1; i >= 0; i--) {
            final BatteryStats.Uid uid = uidStats.valueAt(i);
            batteryUsageStatsBuilder.getOrCreateUidBatteryConsumerBuilder(uid)
                    .setTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND,
                            getProcessBackgroundTimeMs(uid, realtimeUs))
                    .setTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND,
                            getProcessForegroundTimeMs(uid, realtimeUs));
        }

        final List<PowerCalculator> powerCalculators = getPowerCalculators();
        for (int i = 0, count = powerCalculators.size(); i < count; i++) {
            PowerCalculator powerCalculator = powerCalculators.get(i);
            powerCalculator.calculate(batteryUsageStatsBuilder, mStats, realtimeUs, uptimeUs,
                    query);
        }

        if ((query.getFlags()
                & BatteryUsageStatsQuery.FLAG_BATTERY_USAGE_STATS_INCLUDE_HISTORY) != 0) {
            ArrayList<BatteryStats.HistoryTag> tags = new ArrayList<>(
                    mStats.mHistoryTagPool.size());
            for (Map.Entry<BatteryStats.HistoryTag, Integer> entry :
                    mStats.mHistoryTagPool.entrySet()) {
                final BatteryStats.HistoryTag tag = entry.getKey();
                tag.poolIdx = entry.getValue();
                tags.add(tag);
            }

            batteryUsageStatsBuilder.setBatteryHistory(mStats.mHistoryBuffer, tags);
        }

        return batteryUsageStatsBuilder.build();
    }

    private long getProcessForegroundTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        final long topStateDurationMs = uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_TOP,
                realtimeUs, BatteryStats.STATS_SINCE_CHARGED) / 1000;

        long foregroundActivityDurationMs = 0;
        final BatteryStats.Timer foregroundActivityTimer = uid.getForegroundActivityTimer();
        if (foregroundActivityTimer != null) {
            foregroundActivityDurationMs = foregroundActivityTimer.getTotalTimeLocked(realtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED) / 1000;
        }

        // Use the min value of STATE_TOP time and foreground activity time, since both of these
        // times are imprecise
        final long foregroundDurationMs = Math.min(topStateDurationMs,
                foregroundActivityDurationMs);

        long foregroundServiceDurationMs = 0;
        final BatteryStats.Timer foregroundServiceTimer = uid.getForegroundServiceTimer();
        if (foregroundServiceTimer != null) {
            foregroundServiceDurationMs = foregroundServiceTimer.getTotalTimeLocked(realtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED) / 1000;
        }

        return foregroundDurationMs + foregroundServiceDurationMs;
    }

    private long getProcessBackgroundTimeMs(BatteryStats.Uid uid, long realtimeUs) {
        return uid.getProcessStateTime(BatteryStats.Uid.PROCESS_STATE_BACKGROUND, realtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
    }
}
