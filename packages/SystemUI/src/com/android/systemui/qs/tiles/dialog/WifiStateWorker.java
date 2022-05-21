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

package com.android.systemui.qs.tiles.dialog;

import static android.net.wifi.WifiManager.EXTRA_WIFI_STATE;
import static android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.Nullable;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Worker for the Wi-Fi enabled state cache.
 */
@SysUISingleton
public class WifiStateWorker extends BroadcastReceiver {

    private static final String TAG = "WifiStateWorker";

    private DelayableExecutor mBackgroundExecutor;
    private WifiManager mWifiManager;
    private int mWifiState = WIFI_STATE_DISABLED;

    @Inject
    public WifiStateWorker(
            BroadcastDispatcher broadcastDispatcher,
            @Background DelayableExecutor backgroundExecutor,
            @Nullable WifiManager wifiManager) {
        mWifiManager = wifiManager;
        mBackgroundExecutor = backgroundExecutor;

        broadcastDispatcher.registerReceiver(this, new IntentFilter(WIFI_STATE_CHANGED_ACTION));
        mBackgroundExecutor.execute(() -> {
            if (mWifiManager == null) return;

            mWifiState = mWifiManager.getWifiState();
            Log.i(TAG, "WifiManager.getWifiState():" + mWifiState);
        });
    }

    /**
     * Enable or disable Wi-Fi.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     */
    @AnyThread
    public void setWifiEnabled(boolean enabled) {
        mBackgroundExecutor.execute(() -> {
            if (mWifiManager == null) return;

            mWifiState = (enabled) ? WIFI_STATE_ENABLING : WIFI_STATE_DISABLING;
            if (!mWifiManager.setWifiEnabled(enabled)) {
                Log.e(TAG, "Failed to WifiManager.setWifiEnabled(" + enabled + ");");
            }
        });
    }

    /**
     * Gets the Wi-Fi enabled state.
     *
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING}, {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING}
     */
    @AnyThread
    public int getWifiState() {
        return mWifiState;
    }

    /**
     * Return whether Wi-Fi is enabled or disabled.
     *
     * @return {@code true} if Wi-Fi is enabled or enabling
     * @see WifiManager#getWifiState()
     */
    @AnyThread
    public boolean isWifiEnabled() {
        return (mWifiState == WIFI_STATE_ENABLED || mWifiState == WIFI_STATE_ENABLING);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        if (WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
            final int wifiState = intent.getIntExtra(EXTRA_WIFI_STATE, WIFI_STATE_DISABLED);
            if (wifiState == WIFI_STATE_UNKNOWN) return;

            mWifiState = wifiState;
        }
    }
}
