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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.settingslib.TetherUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class HotspotControllerImpl implements HotspotController {

    private static final String TAG = "HotspotController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Intent TETHER_SERVICE_INTENT = new Intent()
            .putExtra(TetherUtil.EXTRA_ADD_TETHER_TYPE, TetherUtil.TETHERING_WIFI)
            .putExtra(TetherUtil.EXTRA_SET_ALARM, true)
            .putExtra(TetherUtil.EXTRA_RUN_PROVISION, true)
            .putExtra(TetherUtil.EXTRA_ENABLE_WIFI_TETHER, true)
            .setComponent(TetherUtil.TETHER_SERVICE);

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final Receiver mReceiver = new Receiver();
    private final Context mContext;

    private int mHotspotState;

    public HotspotControllerImpl(Context context) {
        mContext = context;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HotspotController state:");
        pw.print("  mHotspotEnabled="); pw.println(stateToString(mHotspotState));
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

    public void addCallback(Callback callback) {
        if (callback == null || mCallbacks.contains(callback)) return;
        if (DEBUG) Log.d(TAG, "addCallback " + callback);
        mCallbacks.add(callback);
        mReceiver.setListening(!mCallbacks.isEmpty());
    }

    public void removeCallback(Callback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        mCallbacks.remove(callback);
        mReceiver.setListening(!mCallbacks.isEmpty());
    }

    @Override
    public boolean isHotspotEnabled() {
        return mHotspotState == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    @Override
    public boolean isHotspotSupported() {
        return TetherUtil.isTetheringSupported(mContext);
    }

    @Override
    public void setHotspotEnabled(boolean enabled) {
        // Call provisioning app which is called when enabling Tethering from Settings
        if (enabled && TetherUtil.isProvisioningNeeded(mContext)) {
            mContext.startServiceAsUser(TETHER_SERVICE_INTENT, UserHandle.CURRENT);
        } else {
            TetherUtil.setWifiTethering(enabled, mContext);
        }
    }

    private void fireCallback(boolean isEnabled) {
        for (Callback callback : mCallbacks) {
            callback.onHotspotChanged(isEnabled);
        }
    }

    private final class Receiver extends BroadcastReceiver {
        private boolean mRegistered;

        public void setListening(boolean listening) {
            if (listening && !mRegistered) {
                if (DEBUG) Log.d(TAG, "Registering receiver");
                final IntentFilter filter = new IntentFilter();
                filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
                mContext.registerReceiver(this, filter);
                mRegistered = true;
            } else if (!listening && mRegistered) {
                if (DEBUG) Log.d(TAG, "Unregistering receiver");
                mContext.unregisterReceiver(this);
                mRegistered = false;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "onReceive " + intent.getAction());
            int state = intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_AP_STATE, WifiManager.WIFI_AP_STATE_FAILED);
            mHotspotState = state;
            fireCallback(mHotspotState == WifiManager.WIFI_AP_STATE_ENABLED);
        }
    }
}
