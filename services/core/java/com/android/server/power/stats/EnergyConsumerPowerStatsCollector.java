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

import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.Handler;
import android.os.PersistableBundle;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;

import java.util.function.IntSupplier;

public class EnergyConsumerPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "EnergyConsumerPowerStatsCollector";

    private static final long ENERGY_UNSPECIFIED = -1;

    interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
        IntSupplier getVoltageSupplier();
    }

    private final Injector mInjector;
    private final int mPowerComponentId;
    private final String mPowerComponentName;
    private final int mEnergyConsumerType;
    private final String mEnergyConsumerName;

    private final EnergyConsumerPowerStatsLayout mLayout;
    private boolean mIsInitialized;

    private PowerStats mPowerStats;
    private ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private IntSupplier mVoltageSupplier;
    private int[] mEnergyConsumerIds;
    private long mLastConsumedEnergyUws = ENERGY_UNSPECIFIED;
    private SparseLongArray mLastConsumerEnergyPerUid = new SparseLongArray();
    private int mLastVoltageMv;
    private long mLastUpdateTimestamp;
    private boolean mFirstCollection = true;

    EnergyConsumerPowerStatsCollector(Injector injector, int powerComponentId,
            String powerComponentName, @EnergyConsumerType int energyConsumerType,
            String energyConsumerName, EnergyConsumerPowerStatsLayout statsLayout) {
        super(injector.getHandler(),
                injector.getPowerStatsCollectionThrottlePeriod(powerComponentName),
                injector.getUidResolver(), injector.getClock());
        mInjector = injector;
        mPowerComponentId = powerComponentId;
        mPowerComponentName = powerComponentName;
        mEnergyConsumerType = energyConsumerType;
        mEnergyConsumerName = energyConsumerName;
        mLayout = statsLayout;
    }

    EnergyConsumerPowerStatsCollector(Injector injector, int powerComponentId,
            String powerComponentName, int energyConsumerId,
            EnergyConsumerPowerStatsLayout statsLayout) {
        super(injector.getHandler(),
                injector.getPowerStatsCollectionThrottlePeriod(powerComponentName),
                injector.getUidResolver(), injector.getClock());
        mInjector = injector;
        mPowerComponentId = powerComponentId;
        mPowerComponentName = powerComponentName;
        mEnergyConsumerIds = new int[]{energyConsumerId};
        mEnergyConsumerType = EnergyConsumerType.OTHER;
        mEnergyConsumerName = null;
        mLayout = statsLayout;
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        mConsumedEnergyRetriever = mInjector.getConsumedEnergyRetriever();
        mVoltageSupplier = mInjector.getVoltageSupplier();
        if (mEnergyConsumerIds == null) {
            mEnergyConsumerIds = mConsumedEnergyRetriever.getEnergyConsumerIds(mEnergyConsumerType,
                    mEnergyConsumerName);
        }
        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);
        PowerStats.Descriptor powerStatsDescriptor = new PowerStats.Descriptor(
                mPowerComponentId, mPowerComponentName, mLayout.getDeviceStatsArrayLength(),
                null, 0, mLayout.getUidStatsArrayLength(),
                extras);
        mPowerStats = new PowerStats(powerStatsDescriptor);

        mIsInitialized = true;
        return true;
    }

    @Override
    protected PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        if (mEnergyConsumerIds.length == 0) {
            return null;
        }

        EnergyConsumerResult[] energy =
                    mConsumedEnergyRetriever.getConsumedEnergy(mEnergyConsumerIds);
        long consumedEnergy = 0;
        if (energy != null) {
            for (int i = energy.length - 1; i >= 0; i--) {
                if (energy[i].energyUWs != ENERGY_UNSPECIFIED) {
                    consumedEnergy += energy[i].energyUWs;
                }
            }
        }

        long energyDelta = mLastConsumedEnergyUws != ENERGY_UNSPECIFIED
                ? consumedEnergy - mLastConsumedEnergyUws : 0;
        mLastConsumedEnergyUws = consumedEnergy;
        if (energyDelta < 0) {
            // Likely, restart of powerstats HAL
            energyDelta = 0;
        }

        if (energyDelta == 0 && !mFirstCollection) {
            return null;
        }

        int voltageMv = mVoltageSupplier.getAsInt();
        if (voltageMv <= 0) {
            Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMv
                    + " mV) when querying energy consumers");
            voltageMv = 0;
        }

        int averageVoltage = mLastVoltageMv != 0 ? (mLastVoltageMv + voltageMv) / 2 : voltageMv;
        mLastVoltageMv = voltageMv;
        mLayout.setConsumedEnergy(mPowerStats.stats, 0, uJtoUc(energyDelta, averageVoltage));

        for (int i = mPowerStats.uidStats.size() - 1; i >= 0; i--) {
            mLayout.setUidConsumedEnergy(mPowerStats.uidStats.valueAt(i), 0, 0);
        }

        if (energy != null) {
            for (int i = energy.length - 1; i >= 0; i--) {
                EnergyConsumerAttribution[] perUid = energy[i].attribution;
                if (perUid == null) {
                    continue;
                }

                for (EnergyConsumerAttribution attribution : perUid) {
                    int uid = mUidResolver.mapUid(attribution.uid);
                    long lastEnergy = mLastConsumerEnergyPerUid.get(uid);
                    long deltaEnergy = attribution.energyUWs - lastEnergy;
                    mLastConsumerEnergyPerUid.put(uid, attribution.energyUWs);
                    if (deltaEnergy <= 0) {
                        continue;
                    }
                    long[] uidStats = mPowerStats.uidStats.get(uid);
                    if (uidStats == null) {
                        uidStats = new long[mLayout.getUidStatsArrayLength()];
                        mPowerStats.uidStats.put(uid, uidStats);
                    }

                    mLayout.setUidConsumedEnergy(uidStats, 0,
                            mLayout.getUidConsumedEnergy(uidStats, 0) + deltaEnergy);
                }
            }
        }
        long timestamp = mClock.elapsedRealtime();
        mPowerStats.durationMs = timestamp - mLastUpdateTimestamp;
        mLastUpdateTimestamp = timestamp;
        mFirstCollection = false;
        return mPowerStats;
    }

    @Override
    protected void onUidRemoved(int uid) {
        mLastConsumerEnergyPerUid.delete(uid);
    }
}
