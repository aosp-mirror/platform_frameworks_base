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

package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.UserManager;
import android.util.Log;

import com.android.systemui.Dependency;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class HotspotControllerImpl implements HotspotController, WifiManager.SoftApCallback {

    private static final String TAG = "HotspotController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final ConnectivityManager mConnectivityManager;
    private final WifiManager mWifiManager;
    private final Context mContext;

    private int mHotspotState;
    private int mNumConnectedDevices;
    private boolean mWaitingForTerminalState;

    public HotspotControllerImpl(Context context) {
        mContext = context;
        mConnectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isHotspotSupported() {
        return mConnectivityManager.isTetheringSupported()
                && mConnectivityManager.getTetherableWifiRegexs().length != 0
                && UserManager.get(mContext).isUserAdmin(ActivityManager.getCurrentUser());
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HotspotController state:");
        pw.print("  mHotspotState="); pw.println(stateToString(mHotspotState));
        pw.print("  mNumConnectedDevices="); pw.println(mNumConnectedDevices);
        pw.print("  mWaitingForTerminalState="); pw.println(mWaitingForTerminalState);
    }

    private static String stateToString(int hotspotState) {
        switch (hotspotState) {
            case WifiManager.WIFI_AP_STATE_DISABLED:
                return "DISABLED";
            case WifiManager.WIFI_AP_STATE_DISABLING:
                return "DISABLING";
            case WifiManager.WIFI_AP_STATE_ENABLED:
                return "ENABLED";
            case WifiManager.WIFI_AP_STATE_ENABLING:
                return "ENABLING";
            case WifiManager.WIFI_AP_STATE_FAILED:
                return "FAILED";
        }
        return null;
    }

    @Override
    public void addCallback(Callback callback) {
        synchronized (mCallbacks) {
            if (callback == null || mCallbacks.contains(callback)) return;
            if (DEBUG) Log.d(TAG, "addCallback " + callback);
            mCallbacks.add(callback);

            updateWifiStateListeners(!mCallbacks.isEmpty());
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
            updateWifiStateListeners(!mCallbacks.isEmpty());
        }
    }

    /**
     * Updates the wifi state receiver to either start or stop listening to get updates to the
     * hotspot status. Additionally starts listening to wifi manager state to track the number of
     * connected devices.
     *
     * @param shouldListen whether we should start listening to various wifi statuses
     */
    private void updateWifiStateListeners(boolean shouldListen) {
        if (shouldListen) {
            mWifiManager.registerSoftApCallback(
                    this,
                    Dependency.get(Dependency.MAIN_HANDLER));
        } else {
            mWifiManager.unregisterSoftApCallback(this);
        }
    }

    @Override
    public boolean isHotspotEnabled() {
        return mHotspotState == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    @Override
    public boolean isHotspotTransient() {
        return mWaitingForTerminalState || (mHotspotState == WifiManager.WIFI_AP_STATE_ENABLING);
    }

    @Override
    public void setHotspotEnabled(boolean enabled) {
        if (mWaitingForTerminalState) {
            if (DEBUG) Log.d(TAG, "Ignoring setHotspotEnabled; waiting for terminal state.");
            return;
        }
        if (enabled) {
            mWaitingForTerminalState = true;
            if (DEBUG) Log.d(TAG, "Starting tethering");
            mConnectivityManager.startTethering(ConnectivityManager.TETHERING_WIFI, false,
                    new ConnectivityManager.OnStartTetheringCallback() {
                        @Override
                        public void onTetheringFailed() {
                            if (DEBUG) Log.d(TAG, "onTetheringFailed");
                            maybeResetSoftApState();
                            fireHotspotChangedCallback();
                        }
                    });
        } else {
            mConnectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
        }
    }

    @Override
    public int getNumConnectedDevices() {
        return mNumConnectedDevices;
    }

    /**
     * Sends a hotspot changed callback.
     * Be careful when calling over multiple threads, especially if one of them is the main thread
     * (as it can be blocked).
     */
    private void fireHotspotChangedCallback() {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onHotspotChanged(isHotspotEnabled(), mNumConnectedDevices);
            }
        }
    }

    @Override
    public void onStateChanged(int state, int failureReason) {
        // Update internal hotspot state for tracking before using any enabled/callback methods.
        mHotspotState = state;

        maybeResetSoftApState();
        if (!isHotspotEnabled()) {
            // Reset num devices if the hotspot is no longer enabled so we don't get ghost
            // counters.
            mNumConnectedDevices = 0;
        }

        fireHotspotChangedCallback();
    }

    private void maybeResetSoftApState() {
        if (!mWaitingForTerminalState) {
            return; // Only reset soft AP state if enabled from this controller.
        }

        switch (mHotspotState) {
            case WifiManager.WIFI_AP_STATE_FAILED:
                // TODO(b/110697252): must be called to reset soft ap state after failure
                mConnectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
                // Fall through
            case WifiManager.WIFI_AP_STATE_ENABLED:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                mWaitingForTerminalState = false;
                break;
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_DISABLING:
            default:
                break;
        }
    }

    @Override
    public void onNumClientsChanged(int numConnectedDevices) {
        mNumConnectedDevices = numConnectedDevices;
        fireHotspotChangedCallback();
    }
}
