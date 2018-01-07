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

import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import java.text.ParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatteryDataRecorder extends PerfDataRecorder {
    private static final String TAG = "loadtest.BatteryDataRecorder";
    private static final String DUMP_FILENAME = TAG + "_dump.tmp";

    public BatteryDataRecorder(boolean placebo, int replication, TimeUnit bucket, long periodSecs,
        int burst, boolean includeCountMetric, boolean includeDurationMetric,
        boolean includeEventMetric,  boolean includeValueMetric, boolean includeGaugeMetric) {
      super(placebo, replication, bucket, periodSecs, burst, includeCountMetric,
          includeDurationMetric, includeEventMetric, includeValueMetric, includeGaugeMetric);
    }

    @Override
    public void startRecording(Context context) {
        // Reset batterystats.
        runDumpsysStats(context, DUMP_FILENAME, "batterystats", "--reset");
    }

    @Override
    public void onAlarm(Context context) {
        // Nothing to do as for battery, the whole data is in the final dumpsys call.
    }

    @Override
    public void stopRecording(Context context) {
        StringBuilder sb = new StringBuilder();
        // Don't use --checkin.
        runDumpsysStats(context, DUMP_FILENAME, "batterystats");
        readDumpData(context, DUMP_FILENAME, new BatteryStatsParser(), sb);
        writeData(context, "battery_", "time,battery_level", sb);
    }
}
