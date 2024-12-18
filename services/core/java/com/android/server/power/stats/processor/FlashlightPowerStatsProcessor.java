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

package com.android.server.power.stats.processor;

import android.os.BatteryConsumer;
import android.os.BatteryStats;

import com.android.internal.os.PowerProfile;
import com.android.server.power.stats.PowerStatsUidResolver;

class FlashlightPowerStatsProcessor extends BinaryStatePowerStatsProcessor {
    FlashlightPowerStatsProcessor(PowerProfile powerProfile,
            PowerStatsUidResolver uidResolver) {
        super(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, uidResolver,
                powerProfile.getAveragePower(PowerProfile.POWER_FLASHLIGHT));
    }

    @Override
    protected @BinaryState int getBinaryState(BatteryStats.HistoryItem item) {
        return (item.states2 & BatteryStats.HistoryItem.STATE2_FLASHLIGHT_FLAG) != 0
                ? STATE_ON
                : STATE_OFF;
    }
}
