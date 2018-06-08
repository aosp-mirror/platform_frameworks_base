/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.am.util;

import android.content.Intent;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import java.io.IOException;

public class Utils {
    private static final String TAG = "AmPerfTestsUtils";

    public static void drainBroadcastQueue() {
        runShellCommand("am wait-for-broadcast-idle");
    }

    /**
     * Runs the command and returns the stdout.
     */
    public static String runShellCommand(String cmd) {
        try {
            return UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sends the current time in a message with the given type so TimeReceiver can receive it.
     */
    public static void sendTime(Intent intent, String type) {
        final long time = System.nanoTime();
        final ITimeReceiverCallback sendTimeBinder = ITimeReceiverCallback.Stub.asInterface(
                intent.getExtras().getBinder(Constants.EXTRA_RECEIVER_CALLBACK));
        try {
            sendTimeBinder.sendTime(type, time);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Notify the listener that the main Looper queue is idle.
     */
    public static void sendLooperIdle(Intent intent) {
        ResultReceiver resultReceiver = intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER);
        resultReceiver.send(0, null);
    }
}
