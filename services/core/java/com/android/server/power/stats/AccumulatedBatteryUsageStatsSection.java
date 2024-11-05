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

import android.os.BatteryUsageStats;
import android.util.IndentingPrintWriter;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class AccumulatedBatteryUsageStatsSection extends PowerStatsSpan.Section {
    public static final String TYPE = "accumulated-battery-usage-stats";
    public static final long ID = Long.MAX_VALUE;

    private final BatteryUsageStats.Builder mBatteryUsageStats;

    AccumulatedBatteryUsageStatsSection(BatteryUsageStats.Builder batteryUsageStats) {
        super(TYPE);
        mBatteryUsageStats = batteryUsageStats;
    }

    public BatteryUsageStats.Builder getBatteryUsageStatsBuilder() {
        return mBatteryUsageStats;
    }

    @Override
    public void write(TypedXmlSerializer serializer) throws IOException {
        mBatteryUsageStats.build().writeXml(serializer);
    }

    @Override
    public void dump(IndentingPrintWriter ipw) {
        mBatteryUsageStats.build().dump(ipw, "");
    }

    @Override
    public void close() {
        mBatteryUsageStats.discard();
    }

    static class Reader implements PowerStatsSpan.SectionReader {
        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public PowerStatsSpan.Section read(String sectionType, TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            return new AccumulatedBatteryUsageStatsSection(
                    BatteryUsageStats.createBuilderFromXml(parser));
        }
    }
}
