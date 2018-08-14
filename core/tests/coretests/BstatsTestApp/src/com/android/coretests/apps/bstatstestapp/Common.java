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

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

public class Common {
    private static final String EXTRA_KEY_CMD_RECEIVER = "cmd_receiver";

    public static void doSomeWork(int durationMs) {
        final long endTime = SystemClock.currentThreadTimeMillis() + durationMs;
        double x;
        double y;
        double z;
        while (SystemClock.currentThreadTimeMillis() <= endTime) {
            x = 0.02;
            x *= 1000;
            y = x % 5;
            z = Math.sqrt(y / 100);
        }
    }

    public static void notifyLaunched(Intent intent, IBinder binder, String tag) {
        if (intent == null) {
            return;
        }

        final Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        final ICmdCallback callback = ICmdCallback.Stub.asInterface(
                extras.getBinder(EXTRA_KEY_CMD_RECEIVER));
        try {
            callback.onLaunched(binder);
        } catch (RemoteException e) {
            Log.e(tag, "Error occured while notifying the test: " + e);
        }
    }
}
