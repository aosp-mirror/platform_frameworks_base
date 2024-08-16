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

class EnergyConsumerPowerStatsLayout extends PowerStatsLayout {
    EnergyConsumerPowerStatsLayout() {
        // Add a section for consumed energy, even if the specific device does not
        // have support EnergyConsumers.  This is done to guarantee format compatibility between
        // PowerStats created by a PowerStatsCollector and those produced by a PowerStatsProcessor.
        addDeviceSectionEnergyConsumers(1);
        addDeviceSectionPowerEstimate();

        // Allocate a cell for per-UID consumed energy attribution. We won't know whether the
        // corresponding energy consumer does per-UID attribution until we get data from
        // PowerStatsService.
        addUidSectionEnergyConsumers(1);
        addUidSectionPowerEstimate();
    }
}
