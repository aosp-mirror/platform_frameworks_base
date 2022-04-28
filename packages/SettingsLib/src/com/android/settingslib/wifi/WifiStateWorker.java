/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.wifi;

import static android.net.wifi.WifiManager.EXTRA_WIFI_STATE;
import static android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is a singleton class for Wi-Fi state worker.
 */
public class WifiStateWorker {

    private static final String TAG = "WifiStateWorker";
    private static final Object sLock = new Object();

    /**
     * A singleton {@link WifiStateWorker} object is used to share with all sub-settings.
     */
    @GuardedBy("sLock")
    private static WifiStateWorker sInstance;
    @TestApi
    @GuardedBy("sLock")
    private static Map<Context, WifiStateWorker> sTestInstances;

    @VisibleForTesting
    static WifiManager sWifiManager;
    private static int sWifiState;
    private static HandlerThread sWorkerThread;

    /**
     * Static method to create a singleton class for WifiStateWorker.
     *
     * @param context The Context this is associated with.
     * @return an instance of {@link WifiStateWorker} object.
     */
    @NonNull
    @AnyThread
    public static WifiStateWorker getInstance(@NonNull Context context) {
        synchronized (sLock) {
            if (sTestInstances != null && sTestInstances.containsKey(context)) {
                WifiStateWorker testInstance = sTestInstances.get(context);
                Log.w(TAG, "The context owner try to use a test instance:" + testInstance);
                return testInstance;
            }

            if (sInstance != null) return sInstance;

            sInstance = new WifiStateWorker();
            sWorkerThread = new HandlerThread(
                    TAG + ":{" + context.getApplicationInfo().className + "}",
                    Process.THREAD_PRIORITY_DISPLAY);
            sWorkerThread.start();
            sWorkerThread.getThreadHandler().post(() -> init(context));
            return sInstance;
        }
    }

    /**
     * A convenience method to set pre-prepared instance or mock(WifiStateWorker.class) for testing.
     *
     * @param context  The Context this is associated with.
     * @param instance of {@link WifiStateWorker} object.
     * @hide
     */
    @TestApi
    @VisibleForTesting
    public static void setTestInstance(@NonNull Context context, WifiStateWorker instance) {
        synchronized (sLock) {
            if (sTestInstances == null) sTestInstances = new ConcurrentHashMap<>();

            Log.w(TAG, "Try to set a test instance by context:" + context);
            sTestInstances.put(context, instance);
        }
    }

    @WorkerThread
    private static void init(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        final IntentReceiver receiver = new IntentReceiver();
        appContext.registerReceiver(receiver, new IntentFilter(WIFI_STATE_CHANGED_ACTION), null,
                sWorkerThread.getThreadHandler());
        sWifiManager = appContext.getSystemService(WifiManager.class);
        refresh();
    }

    /**
     * Refresh Wi-Fi state with WifiManager#getWifiState()
     */
    @AnyThread
    public static void refresh() {
        if (sWifiManager == null) return;
        Log.d(TAG, "Start calling WifiManager#getWifiState.");
        sWifiState = sWifiManager.getWifiState();
        Log.d(TAG, "WifiManager#getWifiState return state:" + sWifiState);
    }

    /**
     * Gets the Wi-Fi enabled state.
     *
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     * {@link WifiManager#WIFI_STATE_DISABLING}, {@link WifiManager#WIFI_STATE_ENABLED},
     * {@link WifiManager#WIFI_STATE_ENABLING}, {@link WifiManager#WIFI_STATE_UNKNOWN}
     * @see #isWifiEnabled()
     */
    @AnyThread
    public int getWifiState() {
        return sWifiState;
    }

    /**
     * Return whether Wi-Fi is enabled or disabled.
     *
     * @return {@code true} if Wi-Fi is enabled
     * @see #getWifiState()
     */
    @AnyThread
    public boolean isWifiEnabled() {
        return sWifiState == WIFI_STATE_ENABLED;
    }

    @WorkerThread
    private static class IntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                sWifiState = intent.getIntExtra(EXTRA_WIFI_STATE, WIFI_STATE_DISABLED);
            }
        }
    }
}
