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

package com.android.servicestests.apps.conntestapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.INetworkPolicyManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.INetworkManagementService;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.servicestests.aidl.INetworkStateObserver;

public class ConnTestActivity extends Activity {
    private static final String TAG = ConnTestActivity.class.getSimpleName();

    private static final String TEST_PKG = ConnTestActivity.class.getPackage().getName();
    private static final String ACTION_FINISH_ACTIVITY = TEST_PKG + ".FINISH";
    private static final String EXTRA_NETWORK_STATE_OBSERVER = TEST_PKG + ".observer";

    private static final Object INSTANCE_LOCK = new Object();
    @GuardedBy("instanceLock")
    private static Activity sInstance;

    private BroadcastReceiver finishCommandReceiver = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        synchronized (INSTANCE_LOCK) {
            sInstance = this;
        }
        Log.i(TAG, "onCreate called");

        notifyNetworkStateObserver();

        finishCommandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "finish command received");
                ConnTestActivity.this.finish();
            }
        };
        registerReceiver(finishCommandReceiver, new IntentFilter(ACTION_FINISH_ACTIVITY));
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called");
    }

    @Override
    public void onStop() {
        Log.i(TAG, "onStop called");
        if (finishCommandReceiver != null) {
            unregisterReceiver(finishCommandReceiver);
            finishCommandReceiver = null;
        }
        super.onStop();
    }

    @Override
    public void finish() {
        synchronized (INSTANCE_LOCK) {
            sInstance = null;
        }
        Log.i(TAG, "finish called");
        super.finish();
    }

    public static void finishSelf() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance != null) {
                sInstance.finish();
            }
        }
    }

    private void notifyNetworkStateObserver() {
        if (getIntent() == null) {
            return;
        }

        final Bundle extras = getIntent().getExtras();
        if (extras == null) {
            return;
        }
        final INetworkStateObserver observer = INetworkStateObserver.Stub.asInterface(
                extras.getBinder(EXTRA_NETWORK_STATE_OBSERVER));
        if (observer != null) {
            AsyncTask.execute(() -> {
                try {
                    observer.onNetworkStateChecked(checkNetworkStatus());
                } catch (RemoteException e) {
                    Log.e(TAG, "Error occured while notifying the observer: " + e);
                }
            });
        }
    }

    /**
     * Checks whether the network is restricted.
     *
     * @return null if network is not restricted, otherwise an error message.
     */
    private String checkNetworkStatus() {
        final INetworkManagementService nms = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        final INetworkPolicyManager npms = INetworkPolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
        try {
            final boolean restrictedByFwRules = nms.isNetworkRestricted(Process.myUid());
            final boolean restrictedByUidRules = npms.isUidNetworkingBlocked(Process.myUid(), true);
            if (restrictedByFwRules || restrictedByUidRules) {
                return "Network is restricted by fwRules: " + restrictedByFwRules
                        + " and uidRules: " + restrictedByUidRules;
            }
            return null;
        } catch (RemoteException e) {
            return "Error talking to system server: " + e;
        }
    }
}