/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.pm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@hide}
 */
public class BackgroundDexOptService {

    static final String TAG = "BackgroundDexOptService";

    private final BroadcastReceiver mIdleMaintenanceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_IDLE_MAINTENANCE_START.equals(action)) {
                onIdleStart();
            } else if (Intent.ACTION_IDLE_MAINTENANCE_END.equals(action)) {
                onIdleStop();
            }
        }
    };

    final PackageManagerService mPackageManager;

    final AtomicBoolean mIdleTime = new AtomicBoolean(false);

    public BackgroundDexOptService(Context context) {
        mPackageManager = (PackageManagerService)ServiceManager.getService("package");

        IntentFilter idleMaintenanceFilter = new IntentFilter();
        idleMaintenanceFilter.addAction(Intent.ACTION_IDLE_MAINTENANCE_START);
        idleMaintenanceFilter.addAction(Intent.ACTION_IDLE_MAINTENANCE_END);
        context.registerReceiverAsUser(mIdleMaintenanceReceiver, UserHandle.ALL,
                                       idleMaintenanceFilter, null, null);
    }

    public boolean onIdleStart() {
        Log.i(TAG, "onIdleStart");
        if (mPackageManager.isStorageLow()) {
            return false;
        }
        final HashSet<String> pkgs = mPackageManager.getPackagesThatNeedDexOpt();
        if (pkgs == null) {
            return false;
        }
        mIdleTime.set(true);
        new Thread("BackgroundDexOptService_DexOpter") {
            @Override
            public void run() {
                for (String pkg : pkgs) {
                    if (!mIdleTime.get()) {
                        break;
                    }
                    mPackageManager.performDexOpt(pkg, false);
                }
            }
        }.start();
        return true;
    }

    public void onIdleStop() {
        Log.i(TAG, "onIdleStop");
        mIdleTime.set(false);
    }
}
