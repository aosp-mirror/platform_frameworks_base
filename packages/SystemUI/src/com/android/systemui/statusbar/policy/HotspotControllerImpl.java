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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.UserManager;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class HotspotControllerImpl implements HotspotController {

    private static final String TAG = "HotspotController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final Receiver mReceiver = new Receiver();
    private final ConnectivityManager mConnectivityManager;
    private final Context mContext;

    private int mHotspotState;

    public HotspotControllerImpl(Context context) {
        mContext = context;
        mConnectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isHotspotSupported() {
        return mConnectivityManager.isTetheringSupported()
                && mConnectivityManager.getTetherableWifiRegexs().length != 0
                && UserManager.get(mContext).isUserAdmin(ActivityManager.getCurrentUser());
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

    @Override
    public void addCallback(Callback callback) {
        synchronized (mCallbacks) {
            if (callback == null || mCallbacks.contains(callback)) return;
            if (DEBUG) Log.d(TAG, "addCallback " + callback);
            mCallbacks.add(callback);
            mReceiver.setListening(!mCallbacks.isEmpty());
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
            mReceiver.setListening(!mCallbacks.isEmpty());
        }
    }

    @Override
    public boolean isHotspotEnabled() {
        return mHotspotState == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    static final class OnStartTetheringCallback extends
            ConnectivityManager.OnStartTetheringCallback {
        @Override
        public void onTetheringStarted() {}
        @Override
        public void onTetheringFailed() {
          // TODO: Show error.
        }
    }

    @Override
    public void setHotspotEnabled(boolean enabled) {
        if (enabled) {
            OnStartTetheringCallback callback = new OnStartTetheringCallback();
            mConnectivityManager.startTethering(
                    ConnectivityManager.TETHERING_WIFI, false, callback);
        } else {
            mConnectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
        }
    }

    private void fireCallback(boolean isEnabled) {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onHotspotChanged(isEnabled);
            }
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
