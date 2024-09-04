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
import android.os.Handler;
import android.os.PersistableBundle;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.format.EnergyConsumerPowerStatsLayout;

public class EnergyConsumerPowerStatsCollector extends PowerStatsCollector {

    public interface Injector {
        Handler getHandler();
        Clock getClock();
        PowerStatsUidResolver getUidResolver();
        long getPowerStatsCollectionThrottlePeriod(String powerComponentName);
        ConsumedEnergyRetriever getConsumedEnergyRetriever();
    }

    private static final int UNSPECIFIED = -1;

    private final Injector mInjector;
    private final int mPowerComponentId;
    private final String mPowerComponentName;
    private final int mEnergyConsumerId;
    private final int mEnergyConsumerType;

    private final EnergyConsumerPowerStatsLayout mLayout;
    private boolean mIsInitialized;

    private PowerStats mPowerStats;
    private ConsumedEnergyHelper mConsumedEnergyHelper;
    private long mLastUpdateTimestamp;

    EnergyConsumerPowerStatsCollector(Injector injector, int powerComponentId,
            String powerComponentName, @EnergyConsumerType int energyConsumerType,
            EnergyConsumerPowerStatsLayout statsLayout) {
        this(injector, powerComponentId, powerComponentName, energyConsumerType, UNSPECIFIED,
                statsLayout);
    }

    EnergyConsumerPowerStatsCollector(Injector injector, int powerComponentId,
            String powerComponentName, @EnergyConsumerType int energyConsumerType,
            int energyConsumerId, EnergyConsumerPowerStatsLayout statsLayout) {
        super(injector.getHandler(),
                injector.getPowerStatsCollectionThrottlePeriod(powerComponentName),
                injector.getUidResolver(), injector.getClock());
        mInjector = injector;
        mPowerComponentId = powerComponentId;
        mPowerComponentName = powerComponentName;
        mEnergyConsumerId = energyConsumerId;
        mEnergyConsumerType = energyConsumerType;
        mLayout = statsLayout;
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        if (mEnergyConsumerId != UNSPECIFIED) {
            mConsumedEnergyHelper = new ConsumedEnergyHelper(mInjector.getConsumedEnergyRetriever(),
                    mEnergyConsumerId, true /* perUidAttribution */);
        } else {
            mConsumedEnergyHelper = new ConsumedEnergyHelper(mInjector.getConsumedEnergyRetriever(),
                    mEnergyConsumerType);
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

        mPowerStats.uidStats.clear();

        if (!mConsumedEnergyHelper.collectConsumedEnergy(mPowerStats, mLayout)) {
            return null;
        }

        long timestamp = mClock.elapsedRealtime();
        mPowerStats.durationMs = timestamp - mLastUpdateTimestamp;
        mLastUpdateTimestamp = timestamp;
        return mPowerStats;
    }
}
