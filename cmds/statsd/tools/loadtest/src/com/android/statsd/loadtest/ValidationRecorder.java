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
import android.util.Log;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.EventMetricData;
import com.android.os.StatsLog.StatsLogReport;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the correctness of the stats.
 */
public class ValidationRecorder extends PerfDataRecorder {
    private static final String TAG = "loadtest.ValidationRecorder";

    private final LoadtestActivity mLoadtestActivity;

    public ValidationRecorder(LoadtestActivity loadtestActivity, boolean placebo, int replication,
        TimeUnit bucket, long periodSecs, int burst,  boolean includeCountMetric,
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
        validateData();
    }

    @Override
    public void stopRecording(Context context) {
        validateData();
    }

    private void validateData() {
        // The code below is commented out because it calls getData, which has the side-effect
        // of clearing statsd's data buffer.
        /*
        List<ConfigMetricsReport> reports = mLoadtestActivity.getData();
        if (reports != null) {
            Log.d(TAG, "GOT DATA");
            for (ConfigMetricsReport report : reports) {
                for (StatsLogReport logReport : report.getMetricsList()) {
                    if (!logReport.hasMetricId()) {
                        Log.e(TAG, "Metric missing name.");
                    }
                }
            }
        }
        */
    }

    private void validateEventBatteryLevelChanges(StatsLogReport logReport) {
        Log.d(TAG, "Validating " + logReport.getMetricId());
        if (logReport.hasEventMetrics()) {
            Log.d(TAG, "Num events captured: " + logReport.getEventMetrics().getDataCount());
            for (EventMetricData data : logReport.getEventMetrics().getDataList()) {
                Log.d(TAG, "  Event : " + data.getAtom());
            }
        } else {
            Log.d(TAG, "Metric is invalid");
        }
    }

    private void validateEventBatteryLevelChangesWhileScreenIsOn(StatsLogReport logReport) {
        Log.d(TAG, "Validating " + logReport.getMetricId());
    }
}
