/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.server.power.stats.format.EnergyConsumerPowerStatsLayout;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class CustomEnergyConsumerPowerStatsCollector extends PowerStatsCollector {
    private static final EnergyConsumerPowerStatsLayout sLayout =
            new EnergyConsumerPowerStatsLayout();
    private final EnergyConsumerPowerStatsCollector.Injector mInjector;
    private List<EnergyConsumerPowerStatsCollector> mCollectors;

    public CustomEnergyConsumerPowerStatsCollector(
            EnergyConsumerPowerStatsCollector.Injector injector) {
        super(injector.getHandler(), 0, injector.getUidResolver(), injector.getClock());
        mInjector = injector;
    }

    protected void ensureInitialized() {
        if (mCollectors != null) {
            return;
        }

        ConsumedEnergyRetriever retriever = mInjector.getConsumedEnergyRetriever();
        int[] energyConsumerIds = retriever.getEnergyConsumerIds(EnergyConsumerType.OTHER);
        int powerComponentId = BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;
        mCollectors = new ArrayList<>(energyConsumerIds.length);
        for (int i = 0; i < energyConsumerIds.length; i++) {
            String name = retriever.getEnergyConsumerName(energyConsumerIds[i]);
            EnergyConsumerPowerStatsCollector collector = new EnergyConsumerPowerStatsCollector(
                    mInjector, powerComponentId++, name, EnergyConsumerType.OTHER,
                    energyConsumerIds[i], sLayout);
            collector.setEnabled(true);
            collector.addConsumer(this::deliverStats);
            mCollectors.add(collector);
        }
    }

    @Override
    public boolean schedule() {
        if (!isEnabled()) {
            return false;
        }

        ensureInitialized();
        boolean success = false;
        for (int i = 0; i < mCollectors.size(); i++) {
            success |= mCollectors.get(i).schedule();
        }
        return success;
    }

    @Override
    public boolean forceSchedule() {
        ensureInitialized();
        boolean success = false;
        for (int i = 0; i < mCollectors.size(); i++) {
            success |= mCollectors.get(i).forceSchedule();
        }
        return success;
    }

    @Override
    public void collectAndDump(PrintWriter pw) {
        ensureInitialized();
        for (int i = 0; i < mCollectors.size(); i++) {
            mCollectors.get(i).collectAndDump(pw);
        }
    }
}
