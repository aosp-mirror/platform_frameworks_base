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
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActionListener;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController.AccessPoint;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class WifiAccessPointController {
    private static final String TAG = "WifiAccessPointController";
    private static final boolean DEBUG = false;

    private static final int[] ICONS = {
        R.drawable.ic_qs_wifi_0,
        R.drawable.ic_qs_wifi_full_1,
        R.drawable.ic_qs_wifi_full_2,
        R.drawable.ic_qs_wifi_full_3,
        R.drawable.ic_qs_wifi_full_4,
    };

    private final Context mContext;
    private final ArrayList<AccessPointCallback> mCallbacks = new ArrayList<AccessPointCallback>();
    private final WifiManager mWifiManager;
    private final Receiver mReceiver = new Receiver();

    private boolean mScanning;

    public WifiAccessPointController(Context context) {
        mContext = context;
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    public void addCallback(AccessPointCallback callback) {
        if (callback == null || mCallbacks.contains(callback)) return;
        if (DEBUG) Log.d(TAG, "addCallback " + callback);
        mCallbacks.add(callback);
        mReceiver.setListening(!mCallbacks.isEmpty());
    }

    public void removeCallback(AccessPointCallback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        mCallbacks.remove(callback);
        mReceiver.setListening(!mCallbacks.isEmpty());
    }

    public void scan() {
        if (mScanning) return;
        if (DEBUG) Log.d(TAG, "scan!");
        mScanning = mWifiManager.startScan();
    }

    public void connect(AccessPoint ap) {
        if (ap == null || ap.networkId < 0) return;
        if (DEBUG) Log.d(TAG, "connect networkId=" + ap.networkId);
        mWifiManager.connect(ap.networkId, new ActionListener() {
            @Override
            public void onSuccess() {
                if (DEBUG) Log.d(TAG, "connect success");
            }

            @Override
            public void onFailure(int reason) {
                if (DEBUG) Log.d(TAG, "connect failure reason=" + reason);
            }
        });
    }

    private void fireCallback(AccessPoint[] aps) {
        for (AccessPointCallback callback : mCallbacks) {
            callback.onAccessPointsChanged(aps);
        }
    }

    private static String trimDoubleQuotes(String v) {
        return v != null && v.length() >= 2 && v.charAt(0) == '\"'
                && v.charAt(v.length() - 1) == '\"' ? v.substring(1, v.length() - 1) : v;
    }

    private int getConnectedNetworkId() {
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        return wifiInfo != null ? wifiInfo.getNetworkId() : AccessPoint.NO_NETWORK;
    }

    private ArrayMap<String, WifiConfiguration> getConfiguredNetworksBySsid() {
        final List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
        if (configs == null || configs.size() == 0) return ArrayMap.EMPTY;
        final ArrayMap<String, WifiConfiguration> rt = new ArrayMap<String, WifiConfiguration>();
        for (WifiConfiguration config : configs) {
            rt.put(trimDoubleQuotes(config.SSID), config);
        }
        return rt;
    }

    private void updateAccessPoints() {
        final int connectedNetworkId = getConnectedNetworkId();
        if (DEBUG) Log.d(TAG, "connectedNetworkId: " + connectedNetworkId);
        final List<ScanResult> scanResults = mWifiManager.getScanResults();
        final ArrayMap<String, WifiConfiguration> configured = getConfiguredNetworksBySsid();
        if (DEBUG) Log.d(TAG, "scanResults: " + scanResults);
        final List<AccessPoint> aps = new ArrayList<AccessPoint>(scanResults.size());
        final ArraySet<String> ssids = new ArraySet<String>();
        for (ScanResult scanResult : scanResults) {
            if (scanResult == null) {
                continue;
            }
            final String ssid = scanResult.SSID;
            if (TextUtils.isEmpty(ssid) || ssids.contains(ssid)) continue;
            if (!configured.containsKey(ssid)) continue;
            ssids.add(ssid);
            final WifiConfiguration config = configured.get(ssid);
            final int level = WifiManager.calculateSignalLevel(scanResult.level, ICONS.length);
            final AccessPoint ap = new AccessPoint();
            ap.networkId = config != null ? config.networkId : AccessPoint.NO_NETWORK;
            ap.ssid = ssid;
            ap.iconId = ICONS[level];
            ap.isConnected = ap.networkId != AccessPoint.NO_NETWORK
                    && ap.networkId == connectedNetworkId;
            ap.level = level;
            aps.add(ap);
        }
        Collections.sort(aps, mByStrength);
        fireCallback(aps.toArray(new AccessPoint[aps.size()]));
    }

    private final Comparator<AccessPoint> mByStrength = new Comparator<AccessPoint> () {
        @Override
        public int compare(AccessPoint lhs, AccessPoint rhs) {
            return -Integer.compare(score(lhs), score(rhs));
        }

        private int score(AccessPoint ap) {
            return ap.level + (ap.isConnected ? 10 : 0);
        }
    };

    private final class Receiver extends BroadcastReceiver {
        private boolean mRegistered;

        public void setListening(boolean listening) {
            if (listening && !mRegistered) {
                if (DEBUG) Log.d(TAG, "Registering receiver");
                final IntentFilter filter = new IntentFilter();
                filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
                filter.addAction(WifiManager.NETWORK_IDS_CHANGED_ACTION);
                filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
                filter.addAction(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
                filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
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
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                updateAccessPoints();
                mScanning = false;
            }
        }
    }
}
