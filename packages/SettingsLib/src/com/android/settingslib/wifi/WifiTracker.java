/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks saved or available wifi networks and their state.
 */
public class WifiTracker {
    private static final String TAG = "WifiTracker";

    /** verbose logging flag. this flag is set thru developer debugging options
     * and used so as to assist with in-the-field WiFi connectivity debugging  */
    public static int sVerboseLogging = 0;

    // TODO: Allow control of this?
    // Combo scans can take 5-6s to complete - set to 10s.
    private static final int WIFI_RESCAN_INTERVAL_MS = 10 * 1000;

    private final Context mContext;
    private final WifiManager mWifiManager;
    private final IntentFilter mFilter;

    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final WifiListener mListener;
    private final boolean mIncludeSaved;
    private final boolean mIncludeScans;

    private boolean mSavedNetworksExist;
    private boolean mRegistered;
    private ArrayList<AccessPoint> mAccessPoints = new ArrayList<>();
    private ArrayList<AccessPoint> mCachedAccessPoints = new ArrayList<>();

    private NetworkInfo mLastNetworkInfo;
    private WifiInfo mLastInfo;

    @VisibleForTesting
    Scanner mScanner;

    public WifiTracker(Context context, WifiListener wifiListener, boolean includeSaved,
            boolean includeScans) {
        this(context, wifiListener, includeSaved, includeScans,
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE));
    }

    @VisibleForTesting
    WifiTracker(Context context, WifiListener wifiListener, boolean includeSaved,
            boolean includeScans, WifiManager wifiManager) {
        if (!includeSaved && !includeScans) {
            throw new IllegalArgumentException("Must include either saved or scans");
        }
        mContext = context;
        mWifiManager = wifiManager;
        mIncludeSaved = includeSaved;
        mIncludeScans = includeScans;
        mListener = wifiListener;

        // check if verbose logging has been turned on or off
        sVerboseLogging = mWifiManager.getVerboseLoggingLevel();

        mFilter = new IntentFilter();
        mFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mFilter.addAction(WifiManager.NETWORK_IDS_CHANGED_ACTION);
        mFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mFilter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        mFilter.addAction(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        mFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
    }

    /**
     * Forces an update of the wifi networks when not scanning.
     */
    public void forceUpdate() {
        updateAccessPoints();
    }

    /**
     * Force a scan for wifi networks to happen now.
     */
    public void forceScan() {
        if (mWifiManager.isWifiEnabled() && mScanner != null) {
            mScanner.forceScan();
        }
    }

    /**
     * Temporarily stop scanning for wifi networks.
     */
    public void pauseScanning() {
        if (mScanner != null) {
            mScanner.pause();
        }
    }

    /**
     * Resume scanning for wifi networks after it has been paused.
     */
    public void resumeScanning() {
        if (mWifiManager.isWifiEnabled()) {
            if (mScanner == null) {
                mScanner = new Scanner();
            }
            mScanner.resume();
        }
        updateAccessPoints();
    }

    /**
     * Start tracking wifi networks.
     * Registers listeners and starts scanning for wifi networks. If this is not called
     * then forceUpdate() must be called to populate getAccessPoints().
     */
    public void startTracking() {
        resumeScanning();
        if (!mRegistered) {
            mContext.registerReceiver(mReceiver, mFilter);
            mRegistered = true;
        }
    }

    /**
     * Stop tracking wifi networks.
     * Unregisters all listeners and stops scanning for wifi networks. This should always
     * be called when done with a WifiTracker (if startTracking was called) to ensure
     * proper cleanup.
     */
    public void stopTracking() {
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mRegistered = false;
        }
        pauseScanning();
    }

    /**
     * Gets the current list of access points.
     */
    public List<AccessPoint> getAccessPoints() {
        return mAccessPoints;
    }

    public WifiManager getManager() {
        return mWifiManager;
    }

    public boolean isWifiEnabled() {
        return mWifiManager.isWifiEnabled();
    }

    /**
     * @return true when there are saved networks on the device, regardless
     * of whether the WifiTracker is tracking saved networks.
     */
    public boolean doSavedNetworksExist() {
        return mSavedNetworksExist;
    }

    public boolean isConnected() {
        return mConnected.get();
    }

    public void dump(PrintWriter pw) {
        pw.println("  - wifi tracker ------");
        for (AccessPoint accessPoint : mAccessPoints) {
            pw.println("  " + accessPoint);
        }
    }

    private void updateAccessPoints() {
        // Swap the current access points into a cached list.
        ArrayList<AccessPoint> tmpSwp = mAccessPoints;
        mAccessPoints = mCachedAccessPoints;
        mCachedAccessPoints = tmpSwp;
        // Clear out the configs so we don't think something is saved when it isn't.
        for (AccessPoint accessPoint : mCachedAccessPoints) {
            accessPoint.clearConfig();
        }

        mAccessPoints.clear();

        /** Lookup table to more quickly update AccessPoints by only considering objects with the
         * correct SSID.  Maps SSID -> List of AccessPoints with the given SSID.  */
        Multimap<String, AccessPoint> apMap = new Multimap<String, AccessPoint>();

        final List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
        if (configs != null) {
            mSavedNetworksExist = configs.size() != 0;
            for (WifiConfiguration config : configs) {
                if (config.selfAdded && config.numAssociation == 0) {
                    continue;
                }
                AccessPoint accessPoint = getCachedOrCreate(config);
                if (mLastInfo != null && mLastNetworkInfo != null) {
                    accessPoint.update(mLastInfo, mLastNetworkInfo);
                }
                if (mIncludeSaved) {
                    mAccessPoints.add(accessPoint);
                    apMap.put(accessPoint.getSsid(), accessPoint);
                } else {
                    // If we aren't using saved networks, drop them into the cache so that
                    // we have access to their saved info.
                    mCachedAccessPoints.add(accessPoint);
                }
            }
        }

        final List<ScanResult> results = mWifiManager.getScanResults();
        if (results != null) {
            for (ScanResult result : results) {
                // Ignore hidden and ad-hoc networks.
                if (result.SSID == null || result.SSID.length() == 0 ||
                        result.capabilities.contains("[IBSS]")) {
                    continue;
                }

                boolean found = false;
                for (AccessPoint accessPoint : apMap.getAll(result.SSID)) {
                    if (accessPoint.update(result)) {
                        found = true;
                        break;
                    }
                }
                if (!found && mIncludeScans) {
                    AccessPoint accessPoint = getCachedOrCreate(result);
                    if (mLastInfo != null && mLastNetworkInfo != null) {
                        accessPoint.update(mLastInfo, mLastNetworkInfo);
                    }
                    mAccessPoints.add(accessPoint);
                    apMap.put(accessPoint.getSsid(), accessPoint);
                }
            }
        }

        // Pre-sort accessPoints to speed preference insertion
        Collections.sort(mAccessPoints);
        if (mListener != null) {
            mListener.onAccessPointsChanged();
        }
    }

    private AccessPoint getCachedOrCreate(ScanResult result) {
        final int N = mCachedAccessPoints.size();
        for (int i = 0; i < N; i++) {
            if (mCachedAccessPoints.get(i).matches(result)) {
                AccessPoint ret = mCachedAccessPoints.remove(i);
                ret.update(result);
                return ret;
            }
        }
        return new AccessPoint(mContext, result);
    }

    private AccessPoint getCachedOrCreate(WifiConfiguration config) {
        final int N = mCachedAccessPoints.size();
        for (int i = 0; i < N; i++) {
            if (mCachedAccessPoints.get(i).matches(config)) {
                AccessPoint ret = mCachedAccessPoints.remove(i);
                ret.loadConfig(config);
                return ret;
            }
        }
        return new AccessPoint(mContext, config);
    }

    private void updateNetworkInfo(NetworkInfo networkInfo) {
        /* sticky broadcasts can call this when wifi is disabled */
        if (!mWifiManager.isWifiEnabled()) {
            mScanner.pause();
            return;
        }

        if (networkInfo != null &&
                networkInfo.getDetailedState() == DetailedState.OBTAINING_IPADDR) {
            mScanner.pause();
        } else {
            mScanner.resume();
        }

        mLastInfo = mWifiManager.getConnectionInfo();
        if (networkInfo != null) {
            mLastNetworkInfo = networkInfo;
        }

        boolean reorder = false;
        for (int i = mAccessPoints.size() - 1; i >= 0; --i) {
            if (mAccessPoints.get(i).update(mLastInfo, mLastNetworkInfo)) {
                reorder = true;
            }
        }
        if (reorder) {
            Collections.sort(mAccessPoints);
            if (mListener != null) {
                mListener.onAccessPointsChanged();
            }
        }
    }

    private void updateWifiState(int state) {
        if (state == WifiManager.WIFI_STATE_ENABLED) {
            mScanner.resume();
        } else {
            mLastInfo = null;
            mLastNetworkInfo = null;
            mScanner.pause();
        }
        if (mListener != null) {
            mListener.onWifiStateChanged(state);
        }
    }

    public static List<AccessPoint> getCurrentAccessPoints(Context context, boolean includeSaved,
            boolean includeScans) {
        WifiTracker tracker = new WifiTracker(context, null, includeSaved, includeScans);
        tracker.forceUpdate();
        return tracker.getAccessPoints();
    }

    @VisibleForTesting
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                updateWifiState(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN));
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action) ||
                    WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION.equals(action) ||
                    WifiManager.LINK_CONFIGURATION_CHANGED_ACTION.equals(action)) {
                updateAccessPoints();
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);
                mConnected.set(info.isConnected());
                if (mListener != null) {
                    mListener.onConnectedChanged();
                }
                updateAccessPoints();
                updateNetworkInfo(info);
            } else if (WifiManager.RSSI_CHANGED_ACTION.equals(action)) {
                updateNetworkInfo(null);
            }
        }
    };

    @VisibleForTesting
    class Scanner extends Handler {
        private int mRetry = 0;

        void resume() {
            if (!hasMessages(0)) {
                sendEmptyMessage(0);
            }
        }

        void forceScan() {
            removeMessages(0);
            sendEmptyMessage(0);
        }

        void pause() {
            mRetry = 0;
            removeMessages(0);
        }

        @Override
        public void handleMessage(Message message) {
            if (mWifiManager.startScan()) {
                mRetry = 0;
            } else if (++mRetry >= 3) {
                mRetry = 0;
                if (mContext != null) {
                    Toast.makeText(mContext, R.string.wifi_fail_to_scan, Toast.LENGTH_LONG).show();
                }
                return;
            }
            sendEmptyMessageDelayed(0, WIFI_RESCAN_INTERVAL_MS);
        }
    }

    /** A restricted multimap for use in constructAccessPoints */
    private static class Multimap<K,V> {
        private final HashMap<K,List<V>> store = new HashMap<K,List<V>>();
        /** retrieve a non-null list of values with key K */
        List<V> getAll(K key) {
            List<V> values = store.get(key);
            return values != null ? values : Collections.<V>emptyList();
        }

        void put(K key, V val) {
            List<V> curVals = store.get(key);
            if (curVals == null) {
                curVals = new ArrayList<V>(3);
                store.put(key, curVals);
            }
            curVals.add(val);
        }
    }

    public interface WifiListener {
        /**
         * Called when the state of Wifi has changed, the state will be one of
         * the following.
         *
         * <li>{@link WifiManager#WIFI_STATE_DISABLED}</li>
         * <li>{@link WifiManager#WIFI_STATE_ENABLED}</li>
         * <li>{@link WifiManager#WIFI_STATE_DISABLING}</li>
         * <li>{@link WifiManager#WIFI_STATE_ENABLING}</li>
         * <li>{@link WifiManager#WIFI_STATE_UNKNOWN}</li>
         * <p>
         *
         * @param state The new state of wifi.
         */
        void onWifiStateChanged(int state);

        /**
         * Called when the connection state of wifi has changed and isConnected
         * should be called to get the updated state.
         */
        void onConnectedChanged();

        /**
         * Called to indicate the list of AccessPoints has been updated and
         * getAccessPoints should be called to get the latest information.
         */
        void onAccessPointsChanged();
    }
}
