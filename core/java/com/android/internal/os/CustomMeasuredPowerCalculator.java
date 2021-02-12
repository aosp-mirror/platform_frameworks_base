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
package com.android.internal.os;

import android.os.BatteryStats;

/**
 * Calculates the amount of power consumed by custom energy consumers (i.e. consumers of type
 * {@link android.hardware.power.stats.EnergyConsumerType#OTHER}).
 */
public class CustomMeasuredPowerCalculator extends PowerCalculator {
    public CustomMeasuredPowerCalculator(PowerProfile powerProfile) {
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        updateCustomMeasuredPowerMah(app, u.getCustomMeasuredEnergiesMicroJoules());
    }

    private void updateCustomMeasuredPowerMah(BatterySipper sipper, long[] measuredEnergiesUJ) {
        sipper.customMeasuredPowerMah = calculateMeasuredEnergiesMah(measuredEnergiesUJ);
    }

    private double[] calculateMeasuredEnergiesMah(long[] measuredEnergiesUJ) {
        if (measuredEnergiesUJ == null) {
            return null;
        }
        final double[] measuredEnergiesMah = new double[measuredEnergiesUJ.length];
        for (int i = 0; i < measuredEnergiesUJ.length; i++) {
            measuredEnergiesMah[i] = uJtoMah(measuredEnergiesUJ[i]);
        }
        return measuredEnergiesMah;
    }
}
