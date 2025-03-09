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
package com.android.server.power.stats;

import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.PersistableBundle;
import android.util.SparseLongArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.WakelockPowerStatsLayout;

import java.util.Arrays;

class WakelockPowerStatsCollector extends PowerStatsCollector {

    public interface WakelockDurationRetriever {
        interface Callback {
            void onUidWakelockDuration(int uid, long wakelockDurationMs);
        }

        long getWakelockDurationMillis();
        void retrieveUidWakelockDuration(Callback callback);
    }

    public interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        WakelockDurationRetriever getWakelockDurationRetriever();
    }

    private final WakelockDurationRetriever mWakelockDurationRetriever;
    private WakelockPowerStatsLayout mStatsLayout;
    private PowerStats.Descriptor mDescriptor;
    private PowerStats mPowerStats;
    private boolean mIsInitialized;
    private boolean mFirstCollection = true;
    private long mLastCollectionTime;
    private long mLastWakelockDurationMs;
    private final SparseLongArray mLastUidWakelockDurations = new SparseLongArray();

    WakelockPowerStatsCollector(Injector injector) {
        super(injector.getHandler(), injector.getPowerStatsCollectionThrottlePeriod(
                        BatteryConsumer.powerComponentIdToString(
                                BatteryConsumer.POWER_COMPONENT_WAKELOCK)),
                injector.getUidResolver(), injector.getClock());
        mWakelockDurationRetriever = injector.getWakelockDurationRetriever();
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        mStatsLayout = new WakelockPowerStatsLayout();
        PersistableBundle extras = new PersistableBundle();
        mStatsLayout.toExtras(extras);
        mDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                mStatsLayout.getDeviceStatsArrayLength(), null, 0,
                mStatsLayout.getUidStatsArrayLength(), extras);
        mPowerStats = new PowerStats(mDescriptor);
        mIsInitialized = true;
        return true;
    }

    @Nullable
    @Override
    protected PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        Arrays.fill(mPowerStats.stats, 0);
        mPowerStats.uidStats.clear();

        long elapsedRealtime = mClock.elapsedRealtime();
        mPowerStats.durationMs = elapsedRealtime - mLastCollectionTime;

        long wakelockDurationMillis = mWakelockDurationRetriever.getWakelockDurationMillis();

        if (!mFirstCollection) {
            mStatsLayout.setUsageDuration(mPowerStats.stats,
                    Math.max(0, wakelockDurationMillis - mLastWakelockDurationMs));
        }

        mLastWakelockDurationMs = wakelockDurationMillis;

        mWakelockDurationRetriever.retrieveUidWakelockDuration((uid, durationMs) -> {
            if (!mFirstCollection) {
                long diffMs = Math.max(0, durationMs - mLastUidWakelockDurations.get(uid));
                if (diffMs != 0) {
                    long[] uidStats = mPowerStats.uidStats.get(uid);
                    if (uidStats == null) {
                        uidStats = new long[mDescriptor.uidStatsArrayLength];
                        mPowerStats.uidStats.put(uid, uidStats);
                    }

                    mStatsLayout.setUidUsageDuration(uidStats, diffMs);
                }
            }
            mLastUidWakelockDurations.put(uid, durationMs);
        });

        mLastCollectionTime = elapsedRealtime;
        mFirstCollection = false;

        return mPowerStats;
    }
}
