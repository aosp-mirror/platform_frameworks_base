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
package com.android.coretests.apps.bstatstestapp;

import com.android.frameworks.coretests.aidl.ICmdCallback;
import com.android.frameworks.coretests.aidl.ICmdReceiver;

import android.app.Activity;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

public class TestActivity extends Activity {
    private static final String TAG = TestActivity.class.getName();

    private static final String EXTRA_KEY_CMD_RECEIVER = "cmd_receiver";

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Log.d(TAG, "onCreate called");
        notifyActivityLaunched();
    }

    private void notifyActivityLaunched() {
        if (getIntent() == null) {
            return;
        }

        final Bundle extras = getIntent().getExtras();
        if (extras == null) {
            return;
        }
        final ICmdCallback callback = ICmdCallback.Stub.asInterface(
                extras.getBinder(EXTRA_KEY_CMD_RECEIVER));
        try {
            callback.onActivityLaunched(mReceiver.asBinder());
        } catch (RemoteException e) {
            Log.e(TAG, "Error occured while notifying the test: " + e);
        }
    }

    @Override
    public void finish() {
        super.finish();
        Log.d(TAG, "finish called");
    }

    private ICmdReceiver mReceiver = new ICmdReceiver.Stub() {
        @Override
        public void doSomeWork(int durationMs) {
            final long endTime = SystemClock.uptimeMillis() + durationMs;
            double x;
            double y;
            double z;
            while (SystemClock.uptimeMillis() <= endTime) {
                x = 0.02;
                x *= 1000;
                y = x % 5;
                z = Math.sqrt(y / 100);
            }
        };

        @Override
        public void finishHost() {
            finish();
        }
    };
}
