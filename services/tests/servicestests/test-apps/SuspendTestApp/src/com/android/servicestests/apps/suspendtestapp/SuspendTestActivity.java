/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.servicestests.apps.suspendtestapp;

import static com.android.server.pm.SuspendPackagesTest.ACTION_FINISH_TEST_ACTIVITY;
import static com.android.server.pm.SuspendPackagesTest.ACTION_REPORT_TEST_ACTIVITY_STARTED;
import static com.android.server.pm.SuspendPackagesTest.ACTION_REPORT_TEST_ACTIVITY_STOPPED;
import static com.android.server.pm.SuspendPackagesTest.INSTRUMENTATION_PACKAGE;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

public class SuspendTestActivity extends Activity {
    private static final String TAG = SuspendTestActivity.class.getSimpleName();

    private BroadcastReceiver mFinishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Finishing test activity from receiver");
            SuspendTestActivity.this.finish();
        }
    };

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        final Intent reportStart = new Intent(ACTION_REPORT_TEST_ACTIVITY_STARTED)
                .setPackage(INSTRUMENTATION_PACKAGE);
        sendBroadcast(reportStart);
        registerReceiver(mFinishReceiver, new IntentFilter(ACTION_FINISH_TEST_ACTIVITY));
        super.onStart();
    }
    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        final Intent reportStop = new Intent(ACTION_REPORT_TEST_ACTIVITY_STOPPED)
                .setPackage(INSTRUMENTATION_PACKAGE);
        sendBroadcast(reportStop);
        unregisterReceiver(mFinishReceiver);
        super.onStop();
    }
}
