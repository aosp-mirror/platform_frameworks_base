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

import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.PersistableBundle;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.ScreenPowerStatsLayout;

import java.util.Arrays;
import java.util.function.IntSupplier;

public class ScreenPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "ScreenPowerStatsCollector";

    public interface ScreenUsageTimeRetriever {
        interface Callback {
            void onUidTopActivityTime(int uid, long topActivityTimeMs);
        }

        void retrieveTopActivityTimes(Callback callback);

        long getScreenOnTimeMs(int display);
        long getBrightnessLevelTimeMs(int display, int brightnessLevel);
        long getScreenDozeTimeMs(int display);
    }

    public interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
        IntSupplier getVoltageSupplier();
        ScreenUsageTimeRetriever getScreenUsageTimeRetriever();
        int getDisplayCount();
    }

    private static final long ENERGY_UNSPECIFIED = -1;

    private final Injector mInjector;
    private boolean mIsInitialized;
    private ScreenPowerStatsLayout mLayout;
    private int mDisplayCount;
    private PowerStats mPowerStats;
    private ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private IntSupplier mVoltageSupplier;
    private ScreenUsageTimeRetriever mScreenUsageTimeRetriever;
    private int[] mEnergyConsumerIds = new int[0];
    private long[] mLastConsumedEnergyUws;
    private int mLastVoltageMv;
    private boolean mFirstSample = true;
    private long[] mLastScreenOnTime;
    private long[][] mLastBrightnessLevelTime;
    private long[] mLastDozeTime;
    private final SparseLongArray mLastTopActivityTime = new SparseLongArray();
    private long mLastCollectionTime;

    public ScreenPowerStatsCollector(Injector injector) {
        super(injector.getHandler(),
                injector.getPowerStatsCollectionThrottlePeriod(
                        BatteryConsumer.powerComponentIdToString(
                                BatteryConsumer.POWER_COMPONENT_SCREEN)),
                injector.getUidResolver(), injector.getClock());
        mInjector = injector;
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        mDisplayCount = mInjector.getDisplayCount();
        mConsumedEnergyRetriever = mInjector.getConsumedEnergyRetriever();
        mVoltageSupplier = mInjector.getVoltageSupplier();
        mScreenUsageTimeRetriever = mInjector.getScreenUsageTimeRetriever();
        mEnergyConsumerIds = mConsumedEnergyRetriever.getEnergyConsumerIds(
                EnergyConsumerType.DISPLAY);
        mLastConsumedEnergyUws = new long[mEnergyConsumerIds.length];
        Arrays.fill(mLastConsumedEnergyUws, ENERGY_UNSPECIFIED);

        mLayout = new ScreenPowerStatsLayout(mEnergyConsumerIds.length,
                mInjector.getDisplayCount());

        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);
        PowerStats.Descriptor powerStatsDescriptor = new PowerStats.Descriptor(
                BatteryConsumer.POWER_COMPONENT_SCREEN, mLayout.getDeviceStatsArrayLength(),
                null, 0, mLayout.getUidStatsArrayLength(),
                extras);

        mLastScreenOnTime = new long[mDisplayCount];
        mLastBrightnessLevelTime = new long[mDisplayCount][BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS];
        mLastDozeTime = new long[mDisplayCount];

        mPowerStats = new PowerStats(powerStatsDescriptor);

        mIsInitialized = true;
        return true;
    }

    @Override
    public PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        if (mEnergyConsumerIds.length != 0) {
            collectEnergyConsumers();
        }

        for (int display = 0; display < mDisplayCount; display++) {
            long screenOnTimeMs = mScreenUsageTimeRetriever.getScreenOnTimeMs(display);
            if (!mFirstSample) {
                mLayout.setScreenOnDuration(mPowerStats.stats, display,
                        screenOnTimeMs - mLastScreenOnTime[display]);
            }
            mLastScreenOnTime[display] = screenOnTimeMs;

            for (int level = 0; level < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; level++) {
                long brightnessLevelTimeMs =
                        mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(display, level);
                if (!mFirstSample) {
                    mLayout.setBrightnessLevelDuration(mPowerStats.stats, display, level,
                            brightnessLevelTimeMs - mLastBrightnessLevelTime[display][level]);
                }
                mLastBrightnessLevelTime[display][level] = brightnessLevelTimeMs;
            }
            long screenDozeTimeMs = mScreenUsageTimeRetriever.getScreenDozeTimeMs(display);
            if (!mFirstSample) {
                mLayout.setScreenDozeDuration(mPowerStats.stats, display,
                        screenDozeTimeMs - mLastDozeTime[display]);
            }
            mLastDozeTime[display] = screenDozeTimeMs;
        }

        mPowerStats.uidStats.clear();

        mScreenUsageTimeRetriever.retrieveTopActivityTimes((uid, topActivityTimeMs) -> {
            long topActivityDuration = topActivityTimeMs - mLastTopActivityTime.get(uid);
            if (topActivityDuration == 0) {
                return;
            }
            mLastTopActivityTime.put(uid, topActivityTimeMs);

            int mappedUid = mUidResolver.mapUid(uid);
            long[] uidStats = mPowerStats.uidStats.get(mappedUid);
            if (uidStats == null) {
                uidStats = new long[mLayout.getUidStatsArrayLength()];
                mPowerStats.uidStats.put(mappedUid, uidStats);
            }

            mLayout.setUidTopActivityDuration(uidStats,
                    mLayout.getUidTopActivityDuration(uidStats) + topActivityDuration);
        });

        long elapsedRealtime = mClock.elapsedRealtime();
        mPowerStats.durationMs = elapsedRealtime - mLastCollectionTime;
        mLastCollectionTime = elapsedRealtime;

        mFirstSample = false;

        return mPowerStats;
    }

    private void collectEnergyConsumers() {
        int voltageMv = mVoltageSupplier.getAsInt();
        if (voltageMv <= 0) {
            Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMv
                    + " mV) when querying energy consumers");
            return;
        }

        int averageVoltage = mLastVoltageMv != 0 ? (mLastVoltageMv + voltageMv) / 2 : voltageMv;
        mLastVoltageMv = voltageMv;

        long[] energyUws = mConsumedEnergyRetriever.getConsumedEnergyUws(mEnergyConsumerIds);
        if (energyUws == null) {
            return;
        }

        for (int i = energyUws.length - 1; i >= 0; i--) {
            long energyDelta = mLastConsumedEnergyUws[i] != ENERGY_UNSPECIFIED
                    ? energyUws[i] - mLastConsumedEnergyUws[i] : 0;
            if (energyDelta < 0) {
                // Likely, restart of powerstats HAL
                energyDelta = 0;
            }
            mLayout.setConsumedEnergy(mPowerStats.stats, i, uJtoUc(energyDelta, averageVoltage));
            mLastConsumedEnergyUws[i] = energyUws[i];
        }
    }

    @Override
    protected void onUidRemoved(int uid) {
        mLastTopActivityTime.delete(uid);
    }
}
