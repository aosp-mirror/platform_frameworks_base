/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.fused;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.WorkSource;

import com.android.location.provider.LocationProviderBase;
import com.android.location.provider.ProviderPropertiesUnbundled;
import com.android.location.provider.ProviderRequestUnbundled;

import java.io.FileDescriptor;
import java.io.PrintWriter;

class FusedLocationProvider extends LocationProviderBase implements FusionEngine.Callback {
    private static final String TAG = "FusedLocationProvider";

    private static ProviderPropertiesUnbundled PROPERTIES = ProviderPropertiesUnbundled.create(
            false, false, false, false, true, true, true, Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE);

    private final Context mContext;
    private final Handler mHandler;
    private final FusionEngine mEngine;

    private final BroadcastReceiver mUserSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mEngine.switchUser();
            }
        }
    };

    FusedLocationProvider(Context context) {
        super(TAG, PROPERTIES);

        mContext = context;
        mHandler = new Handler(Looper.myLooper());
        mEngine = new FusionEngine(context, Looper.myLooper(), this);
    }

    void init() {
        // listen for user change
        mContext.registerReceiverAsUser(mUserSwitchReceiver, UserHandle.ALL,
                new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);
    }

    void destroy() {
        mContext.unregisterReceiver(mUserSwitchReceiver);
        mHandler.post(() -> mEngine.setRequest(null));
    }

    @Override
    public void onSetRequest(ProviderRequestUnbundled request, WorkSource source) {
        mHandler.post(() -> mEngine.setRequest(request));
    }

    @Override
    public void onDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mEngine.dump(fd, pw, args);
    }
}
