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

package com.android.server.power.stats.processor;

import android.util.IndentingPrintWriter;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.power.stats.PowerStatsSpan;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class AggregatedPowerStatsSection extends PowerStatsSpan.Section {
    public static final String TYPE = "aggregated-power-stats";

    private final AggregatedPowerStats mAggregatedPowerStats;

    AggregatedPowerStatsSection(AggregatedPowerStats aggregatedPowerStats) {
        super(TYPE);
        mAggregatedPowerStats = aggregatedPowerStats;
    }

    public AggregatedPowerStats getAggregatedPowerStats() {
        return mAggregatedPowerStats;
    }

    @Override
    public void write(TypedXmlSerializer serializer) throws IOException {
        mAggregatedPowerStats.writeXml(serializer);
    }

    @Override
    public void dump(IndentingPrintWriter ipw) {
        mAggregatedPowerStats.dump(ipw);
    }

    static class Reader implements PowerStatsSpan.SectionReader {
        private final AggregatedPowerStatsConfig mAggregatedPowerStatsConfig;

        Reader(AggregatedPowerStatsConfig config) {
            mAggregatedPowerStatsConfig = config;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public PowerStatsSpan.Section read(String sectionType, TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            return new AggregatedPowerStatsSection(
                    AggregatedPowerStats.createFromXml(parser, mAggregatedPowerStatsConfig));
        }
    }
}
