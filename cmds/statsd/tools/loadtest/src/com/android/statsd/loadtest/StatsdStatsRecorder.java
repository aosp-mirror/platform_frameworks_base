/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.statsd.loadtest;

import android.content.Context;
import com.android.os.StatsLog.StatsdStatsReport;
import com.android.internal.os.StatsdConfigProto.TimeUnit;

public class StatsdStatsRecorder extends PerfDataRecorder {
    private static final String TAG = "loadtest.StatsdStatsRecorder";

    private final LoadtestActivity mLoadtestActivity;

    public StatsdStatsRecorder(LoadtestActivity loadtestActivity, boolean placebo, int replication,
        TimeUnit bucket, long periodSecs, int burst, boolean includeCountMetric,
        boolean includeDurationMetric, boolean includeEventMetric,  boolean includeValueMetric,
        boolean includeGaugeMetric) {
      super(placebo, replication, bucket, periodSecs, burst, includeCountMetric,
          includeDurationMetric, includeEventMetric, includeValueMetric, includeGaugeMetric);
        mLoadtestActivity = loadtestActivity;
    }

    @Override
    public void startRecording(Context context) {
        // Nothing to do.
    }

    @Override
    public void onAlarm(Context context) {
        // Nothing to do.
    }

    @Override
    public void stopRecording(Context context) {
        StatsdStatsReport metadata = mLoadtestActivity.getMetadata();
        if (metadata != null) {
            int numConfigs = metadata.getConfigStatsCount();
            StringBuilder sb = new StringBuilder();
            StatsdStatsReport.ConfigStats configStats = metadata.getConfigStats(numConfigs - 1);
            sb.append("metric_count,")
                .append(configStats.getMetricCount() + "\n")
                .append("condition_count,")
                .append(configStats.getConditionCount() + "\n")
                .append("matcher_count,")
                .append(configStats.getMatcherCount() + "\n");
            writeData(context, "statsdstats_", "stat,value", sb);
        }
    }
}
