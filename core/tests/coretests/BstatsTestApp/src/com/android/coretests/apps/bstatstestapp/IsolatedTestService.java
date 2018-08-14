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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

public class IsolatedTestService extends Service {
    private static final String TAG = IsolatedTestService.class.getSimpleName();

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate called. myUid=" + Process.myUid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called. myUid=" + Process.myUid());
        return mReceiver.asBinder();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called. myUid=" + Process.myUid());
    }

    private BaseCmdReceiver mReceiver = new BaseCmdReceiver() {
        @Override
        public void doSomeWork(int durationMs) {
            Common.doSomeWork(durationMs);
        }

        @Override
        public void finishHost() {
            stopSelf();
        }
    };
}
