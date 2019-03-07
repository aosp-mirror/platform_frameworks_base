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

package com.android.dynsystem;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.image.DynamicSystemClient;
import android.util.Log;


/**
 * A BoardcastReceiver waiting for ACTION_BOOT_COMPLETED and ask
 * the service to display a notification if we are currently running
 * in DynamicSystem.
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        Log.d(TAG, "Broadcast received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Intent startServiceIntent = new Intent(
                    context, DynamicSystemInstallationService.class);

            startServiceIntent.setAction(DynamicSystemClient.ACTION_NOTIFY_IF_IN_USE);
            context.startServiceAsUser(startServiceIntent, UserHandle.SYSTEM);
        }
    }
}
