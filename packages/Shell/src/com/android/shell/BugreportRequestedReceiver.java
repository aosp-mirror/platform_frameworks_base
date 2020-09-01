/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.shell;

import static com.android.shell.BugreportProgressService.EXTRA_ORIGINAL_INTENT;
import static com.android.shell.BugreportProgressService.dumpIntent;

import android.annotation.RequiresPermission;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receiver that listens to {@link Intent#INTENT_BUGREPORT_REQUESTED}
 * and starts up BugreportProgressService to start a new bugreport
 */
public class BugreportRequestedReceiver extends BroadcastReceiver {
    private static final String TAG = "BugreportRequestedReceiver";

    @Override
    @RequiresPermission(android.Manifest.permission.TRIGGER_SHELL_BUGREPORT)
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive(): " + dumpIntent(intent));

        // Delegate intent handling to service.
        Intent serviceIntent = new Intent(context, BugreportProgressService.class);
        Log.d(TAG, "onReceive() ACTION: " + serviceIntent.getAction());
        serviceIntent.setAction(intent.getAction());
        serviceIntent.putExtra(EXTRA_ORIGINAL_INTENT, intent);
        context.startService(serviceIntent);
    }
}
