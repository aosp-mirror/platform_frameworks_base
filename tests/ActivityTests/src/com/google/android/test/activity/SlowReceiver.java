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

package com.google.android.test.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

public class SlowReceiver extends BroadcastReceiver {
    private static final String TAG = "SlowReceiver";
    private static final long RECEIVER_DELAY = 6_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final int extra = intent.getIntExtra(ActivityTestMain.SLOW_RECEIVER_EXTRA, -1);
        if (extra == 1) {
            Log.i(TAG, "Received broadcast 1; delaying return by " + RECEIVER_DELAY + " ms");
            long now = SystemClock.elapsedRealtime();
            final long end = now + RECEIVER_DELAY;
            while (now < end) {
                try {
                    Thread.sleep(end - now);
                } catch (InterruptedException e) { }
                now = SystemClock.elapsedRealtime();
            }
        } else {
            Log.i(TAG, "Extra parameter not 1, returning immediately");
        }
        Log.i(TAG, "Returning from onReceive()");
    }
}
