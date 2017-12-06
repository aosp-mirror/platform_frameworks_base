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
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/** Prints some information about the device via Dumpsys in order to evaluate health metrics. */
public class PerfData extends PerfDataRecorder {

    private static final String TAG = "loadtest.PerfData";

    /** Polling period for performance snapshots like memory. */
    private static final long POLLING_PERIOD_MILLIS = 1 * 60 * 1000;

    public final static class PerfAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent activityIntent = new Intent(context, LoadtestActivity.class);
            activityIntent.putExtra(LoadtestActivity.TYPE, LoadtestActivity.PERF_ALARM);
            context.startActivity(activityIntent);
         }
    }

    private AlarmManager mAlarmMgr;

    /** Used to periodically poll some dumpsys data. */
    private PendingIntent mPendingIntent;

    private final Set<PerfDataRecorder> mRecorders;

    public PerfData(Context context, boolean placebo, int replication, long bucketMins,
        long periodSecs,  int burst) {
        super(placebo, replication, bucketMins, periodSecs, burst);
        mRecorders = new HashSet();
        mRecorders.add(new BatteryDataRecorder(placebo, replication, bucketMins, periodSecs, burst));
        mRecorders.add(new MemoryDataRecorder(placebo, replication, bucketMins, periodSecs, burst));
        mAlarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    public void onDestroy() {
        if (mPendingIntent != null) {
            mAlarmMgr.cancel(mPendingIntent);
            mPendingIntent = null;
        }
    }

    @Override
    public void startRecording(Context context) {
        Intent intent = new Intent(context, PerfAlarmReceiver.class);
        intent.putExtra(LoadtestActivity.TYPE, LoadtestActivity.PERF_ALARM);
        mPendingIntent = PendingIntent.getBroadcast(context, 0, intent, 0);
        mAlarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, -1 /* now */,
            POLLING_PERIOD_MILLIS, mPendingIntent);

        for (PerfDataRecorder recorder : mRecorders) {
            recorder.startRecording(context);
        }
    }

    @Override
    public void onAlarm(Context context) {
        for (PerfDataRecorder recorder : mRecorders) {
            recorder.onAlarm(context);
        }
    }

    @Override
    public void stopRecording(Context context) {
        if (mPendingIntent != null) {
            mAlarmMgr.cancel(mPendingIntent);
            mPendingIntent = null;
        }

        for (PerfDataRecorder recorder : mRecorders) {
            recorder.stopRecording(context);
        }
    }
}
