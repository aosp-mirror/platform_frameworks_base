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
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.servicestests.aidl.INetworkStateObserver;

import java.net.HttpURLConnection;
import java.net.URL;

public class ConnTestActivity extends Activity {
    private static final String TAG = ConnTestActivity.class.getSimpleName();

    private static final String TEST_PKG = ConnTestActivity.class.getPackage().getName();
    private static final String ACTION_FINISH_ACTIVITY = TEST_PKG + ".FINISH";
    private static final String EXTRA_NETWORK_STATE_OBSERVER = TEST_PKG + ".observer";

    private static final int NETWORK_TIMEOUT_MS = 5 * 1000;

    private static final String NETWORK_STATUS_TEMPLATE = "%s|%s|%s|%s|%s";

    private BroadcastReceiver finishCommandReceiver = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notifyNetworkStateObserver();

        finishCommandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ConnTestActivity.this.finish();
            }
        };
        registerReceiver(finishCommandReceiver, new IntentFilter(ACTION_FINISH_ACTIVITY));
    }

    @Override
    public void onStop() {
        if (finishCommandReceiver != null) {
            unregisterReceiver(finishCommandReceiver);
        }
        super.onStop();
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
                    observer.onNetworkStateChecked(checkNetworkStatus(ConnTestActivity.this));
                } catch (RemoteException e) {
                    Log.e(TAG, "Error occured while notifying the observer: " + e);
                }
            });
        }
    }

    /**
     * Checks whether the network is available and return a string which can then be send as a
     * result data for the ordered broadcast.
     *
     * <p>
     * The string has the following format:
     *
     * <p><pre><code>
     * NetinfoState|NetinfoDetailedState|RealConnectionCheck|RealConnectionCheckDetails|Netinfo
     * </code></pre>
     *
     * <p>Where:
     *
     * <ul>
     * <li>{@code NetinfoState}: enum value of {@link NetworkInfo.State}.
     * <li>{@code NetinfoDetailedState}: enum value of {@link NetworkInfo.DetailedState}.
     * <li>{@code RealConnectionCheck}: boolean value of a real connection check (i.e., an attempt
     *     to access an external website.
     * <li>{@code RealConnectionCheckDetails}: if HTTP output core or exception string of the real
     *     connection attempt
     * <li>{@code Netinfo}: string representation of the {@link NetworkInfo}.
     * </ul>
     *
     * For example, if the connection was established fine, the result would be something like:
     * <p><pre><code>
     * CONNECTED|CONNECTED|true|200|[type: WIFI[], state: CONNECTED/CONNECTED, reason: ...]
     * </code></pre>
     */
    private String checkNetworkStatus(Context context) {
        final ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final String address = "http://example.com";
        final NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        Log.d(TAG, "Running checkNetworkStatus() on thread "
                + Thread.currentThread().getName() + " for UID " + getUid(context)
                + "\n\tactiveNetworkInfo: " + networkInfo + "\n\tURL: " + address);
        boolean checkStatus = false;
        String checkDetails = "N/A";
        try {
            final URL url = new URL(address);
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(NETWORK_TIMEOUT_MS);
            conn.setConnectTimeout(NETWORK_TIMEOUT_MS / 2);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            conn.connect();
            final int response = conn.getResponseCode();
            checkStatus = true;
            checkDetails = "HTTP response for " + address + ": " + response;
        } catch (Exception e) {
            checkStatus = false;
            checkDetails = "Exception getting " + address + ": " + e;
        }
        Log.d(TAG, checkDetails);
        final String state, detailedState;
        if (networkInfo != null) {
            state = networkInfo.getState().name();
            detailedState = networkInfo.getDetailedState().name();
        } else {
            state = detailedState = "null";
        }
        final String status = String.format(NETWORK_STATUS_TEMPLATE, state, detailedState,
                Boolean.valueOf(checkStatus), checkDetails, networkInfo);
        Log.d(TAG, "Offering " + status);
        return status;
    }

    private int getUid(Context context) {
        final String packageName = context.getPackageName();
        try {
            return context.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Could not get UID for " + packageName, e);
        }
    }
}