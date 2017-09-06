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

package com.android.tests.servicecrashtest;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;

public class MainActivity extends Activity {

    private static final String TAG = "ServiceCrashTest";

    static final CountDownLatch sBindingDiedLatch = new CountDownLatch(1);

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Service disconnected");
        }

        @Override
        public void onBindingDied(ComponentName componentName) {
            Log.i(TAG, "Binding died");
            sBindingDiedLatch.countDown();
        }
    };

    @Override
    public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);

        setContentView(new TextView(this));
    }

    public void onResume() {
        Intent intent = new Intent();
        intent.setClass(this, CrashingService.class);
        bindService(intent, mServiceConnection, Service.BIND_AUTO_CREATE);

        super.onResume();
    }
}
