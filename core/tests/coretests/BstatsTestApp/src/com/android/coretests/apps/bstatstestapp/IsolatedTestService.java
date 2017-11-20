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

import com.android.frameworks.coretests.aidl.ICmdReceiver;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

public class IsolatedTestService extends Service {
    private static final String TAG = IsolatedTestService.class.getName();

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate called. myUid=" + Process.myUid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mReceiver.asBinder();
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
            stopSelf();
        }
    };
}
