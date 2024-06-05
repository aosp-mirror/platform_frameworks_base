/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.BatteryUsageStats;
import android.util.IndentingPrintWriter;

import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;

class BatteryUsageStatsSection extends PowerStatsSpan.Section {
    public static final String TYPE = "battery-usage-stats";

    private final BatteryUsageStats mBatteryUsageStats;

    BatteryUsageStatsSection(BatteryUsageStats batteryUsageStats) {
        super(TYPE);
        mBatteryUsageStats = batteryUsageStats;
    }

    public BatteryUsageStats getBatteryUsageStats() {
        return mBatteryUsageStats;
    }

    @Override
    void write(TypedXmlSerializer serializer) throws IOException {
        mBatteryUsageStats.writeXml(serializer);
    }

    @Override
    public void dump(IndentingPrintWriter ipw) {
        mBatteryUsageStats.dump(ipw, "");
    }
}
