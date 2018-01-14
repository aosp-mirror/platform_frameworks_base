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

package com.android.frameworks.perftests.amteststestapp;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.android.frameworks.perftests.am.util.Constants;
import com.android.frameworks.perftests.am.util.Utils;

public class TestApplication extends Application {
    private static final String TAG = TestApplication.class.getSimpleName();

    @Override
    public void onCreate() {
        createRegisteredReceiver();

        super.onCreate();
    }

    // Create registered BroadcastReceiver
    private void createRegisteredReceiver() {
        BroadcastReceiver registered = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "RegisteredReceiver.onReceive");
                Utils.sendTime(intent, Constants.TYPE_BROADCAST_RECEIVE);
            }
        };
        IntentFilter intentFilter = new IntentFilter(Constants.ACTION_BROADCAST_REGISTERED_RECEIVE);
        registerReceiver(registered, intentFilter);
    }
}
