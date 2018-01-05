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
import android.os.SystemClock;
import android.util.Log;
import com.android.internal.os.StatsdConfigProto.TimeUnit;

public class MemoryDataRecorder extends PerfDataRecorder {
    private static final String TAG = "loadtest.MemoryDataDataRecorder";
    private static final String DUMP_FILENAME = TAG + "_dump.tmp";

    private long mStartTimeMillis;
    private StringBuilder mSb;

    public MemoryDataRecorder(boolean placebo, int replication, TimeUnit bucket, long periodSecs,
        int burst,  boolean includeCountMetric, boolean includeDurationMetric,
        boolean includeEventMetric,  boolean includeValueMetric, boolean includeGaugeMetric) {
      super(placebo, replication, bucket, periodSecs, burst, includeCountMetric,
          includeDurationMetric, includeEventMetric, includeValueMetric, includeGaugeMetric);
    }

    @Override
    public void startRecording(Context context) {
        mStartTimeMillis = SystemClock.elapsedRealtime();
        mSb = new StringBuilder();
    }

    @Override
    public void onAlarm(Context context) {
        runDumpsysStats(context, DUMP_FILENAME, "meminfo");
        readDumpData(context, DUMP_FILENAME, new MemInfoParser(mStartTimeMillis), mSb);
    }

    @Override
    public void stopRecording(Context context) {
        writeData(context, "meminfo_", "time,pss", mSb);
    }
}
