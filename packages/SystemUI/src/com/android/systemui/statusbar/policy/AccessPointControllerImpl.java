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
import android.content.Intent;
import android.net.wifi.WifiManager.ActionListener;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.android.settingslib.wifi.AccessPoint;
import com.android.settingslib.wifi.WifiTracker;
import com.android.settingslib.wifi.WifiTracker.WifiListener;
import com.android.systemui.R;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class AccessPointControllerImpl
        implements NetworkController.AccessPointController, WifiListener {
    private static final String TAG = "AccessPointController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // This string extra specifies a network to open the connect dialog on, so the user can enter
    // network credentials.  This is used by quick settings for secured networks.
    private static final String EXTRA_START_CONNECT_SSID = "wifi_start_connect_ssid";

    private static final int[] ICONS = {
        R.drawable.ic_qs_wifi_full_0,
        R.drawable.ic_qs_wifi_full_1,
        R.drawable.ic_qs_wifi_full_2,
        R.drawable.ic_qs_wifi_full_3,
        R.drawable.ic_qs_wifi_full_4,
    };

    private final Context mContext;
    private final ArrayList<AccessPointCallback> mCallbacks = new ArrayList<AccessPointCallback>();
    private final WifiTracker mWifiTracker;
    private final UserManager mUserManager;

    private int mCurrentUser;

    public AccessPointControllerImpl(Context context, Looper bgLooper) {
        mContext = context;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mWifiTracker = new WifiTracker(context, this, bgLooper, false, true);
        mCurrentUser = ActivityManager.getCurrentUser();
    }

    public boolean canConfigWifi() {
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI,
                new UserHandle(mCurrentUser));
    }

    public void onUserSwitched(int newUserId) {
        mCurrentUser = newUserId;
    }

    @Override
    public void addAccessPointCallback(AccessPointCallback callback) {
        if (callback == null || mCallbacks.contains(callback)) return;
        if (DEBUG) Log.d(TAG, "addCallback " + callback);
        mCallbacks.add(callback);
        if (mCallbacks.size() == 1) {
            mWifiTracker.startTracking();
        }
    }

    @Override
    public void removeAccessPointCallback(AccessPointCallback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        mCallbacks.remove(callback);
        if (mCallbacks.isEmpty()) {
            mWifiTracker.stopTracking();
        }
    }

    @Override
    public void scanForAccessPoints() {
        if (DEBUG) Log.d(TAG, "force update APs!");
        mWifiTracker.forceUpdate();
        fireAcccessPointsCallback(mWifiTracker.getAccessPoints());
    }

    @Override
    public int getIcon(AccessPoint ap) {
        int level = ap.getLevel();
        return ICONS[level >= 0 ? level : 0];
    }

    public boolean connect(AccessPoint ap) {
        if (ap == null) return false;
        if (DEBUG) Log.d(TAG, "connect networkId=" + ap.getConfig().networkId);
        if (ap.isSaved()) {
            mWifiTracker.getManager().connect(ap.getConfig().networkId, mConnectListener);
        } else {
            // Unknown network, need to add it.
            if (ap.getSecurity() != AccessPoint.SECURITY_NONE) {
                Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                intent.putExtra(EXTRA_START_CONNECT_SSID, ap.getSsidStr());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fireSettingsIntentCallback(intent);
                return true;
            } else {
                ap.generateOpenNetworkConfig();
                mWifiTracker.getManager().connect(ap.getConfig(), mConnectListener);
            }
        }
        return false;
    }

    private void fireSettingsIntentCallback(Intent intent) {
        for (AccessPointCallback callback : mCallbacks) {
            callback.onSettingsActivityTriggered(intent);
        }
    }

    private void fireAcccessPointsCallback(List<AccessPoint> aps) {
        for (AccessPointCallback callback : mCallbacks) {
            callback.onAccessPointsChanged(aps);
        }
    }

    public void dump(PrintWriter pw) {
        mWifiTracker.dump(pw);
    }

    @Override
    public void onWifiStateChanged(int state) {
    }

    @Override
    public void onConnectedChanged() {
        fireAcccessPointsCallback(mWifiTracker.getAccessPoints());
    }

    @Override
    public void onAccessPointsChanged() {
        fireAcccessPointsCallback(mWifiTracker.getAccessPoints());
    }

    private final ActionListener mConnectListener = new ActionListener() {
        @Override
        public void onSuccess() {
            if (DEBUG) Log.d(TAG, "connect success");
        }

        @Override
        public void onFailure(int reason) {
            if (DEBUG) Log.d(TAG, "connect failure reason=" + reason);
        }
    };
}
