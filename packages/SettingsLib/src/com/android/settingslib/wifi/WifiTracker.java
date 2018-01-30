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

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.NetworkRequest;
import android.net.NetworkScoreManager;
import android.net.ScoredNetwork;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiNetworkScoreCache.CacheListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseIntArray;
import android.widget.Toast;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnDestroy;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;
import com.android.settingslib.utils.ThreadUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks saved or available wifi networks and their state.
 */
public class WifiTracker implements LifecycleObserver, OnStart, OnStop, OnDestroy {
    /**
     * Default maximum age in millis of cached scored networks in
     * {@link AccessPoint#mScoredNetworkCache} to be used for speed label generation.
     */
    private static final long DEFAULT_MAX_CACHED_SCORE_AGE_MILLIS = 20 * DateUtils.MINUTE_IN_MILLIS;

    /** Maximum age of scan results to hold onto while actively scanning. **/
    private static final long MAX_SCAN_RESULT_AGE_MILLIS = 25000;

    private static final String TAG = "WifiTracker";
    private static final boolean DBG() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    private static boolean isVerboseLoggingEnabled() {
        return WifiTracker.sVerboseLogging || Log.isLoggable(TAG, Log.VERBOSE);
    }

    /**
     * Verbose logging flag set thru developer debugging options and used so as to assist with
     * in-the-field WiFi connectivity debugging.
     *
     * <p>{@link #isVerboseLoggingEnabled()} should be read rather than referencing this value
     * directly, to ensure adb TAG level verbose settings are respected.
     */
    public static boolean sVerboseLogging;

    // TODO: Allow control of this?
    // Combo scans can take 5-6s to complete - set to 10s.
    private static final int WIFI_RESCAN_INTERVAL_MS = 10 * 1000;
    private static final int NUM_SCANS_TO_CONFIRM_AP_LOSS = 3;

    private final Context mContext;
    private final WifiManager mWifiManager;
    private final IntentFilter mFilter;
    private final ConnectivityManager mConnectivityManager;
    private final NetworkRequest mNetworkRequest;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final WifiListener mListener;
    @VisibleForTesting WorkHandler mWorkHandler;
    private HandlerThread mWorkThread;

    private WifiTrackerNetworkCallback mNetworkCallback;

    @GuardedBy("mLock")
    private boolean mRegistered;

    /** The list of AccessPoints, aggregated visible ScanResults with metadata. */
    @GuardedBy("mLock")
    private final List<AccessPoint> mInternalAccessPoints = new ArrayList<>();

    /**
    * Synchronization lock for managing concurrency between main and worker threads.
    *
    * <p>This lock should be held for all modifications to {@link #mInternalAccessPoints}.
    */
    private final Object mLock = new Object();

    private final HashMap<String, Integer> mSeenBssids = new HashMap<>();

    // TODO(sghuman): Change this to be keyed on AccessPoint.getKey
    private final HashMap<String, ScanResult> mScanResultCache = new HashMap<>();

    private NetworkInfo mLastNetworkInfo;
    private WifiInfo mLastInfo;

    private final NetworkScoreManager mNetworkScoreManager;
    private WifiNetworkScoreCache mScoreCache;
    private boolean mNetworkScoringUiEnabled;
    private long mMaxSpeedLabelScoreCacheAge;

    @GuardedBy("mLock")
    private final Set<NetworkKey> mRequestedScores = new ArraySet<>();

    @VisibleForTesting
    Scanner mScanner;

    /**
     * Tracks whether fresh scan results have been received since scanning start.
     *
     * <p>If this variable is false, we will not evict the scan result cache or invoke callbacks
     * so that we do not update the UI with stale data / clear out existing UI elements prematurely.
     */
    @GuardedBy("mLock")
    private boolean mStaleScanResults = true;

    private static IntentFilter newIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_IDS_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        filter.addAction(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);

        return filter;
    }

    /**
     * Use the lifecycle constructor below whenever possible
     */
    @Deprecated
    public WifiTracker(Context context, WifiListener wifiListener,
            boolean includeSaved, boolean includeScans) {
        this(context, wifiListener,
                context.getSystemService(WifiManager.class),
                context.getSystemService(ConnectivityManager.class),
                context.getSystemService(NetworkScoreManager.class),
                newIntentFilter());
    }

    // TODO(Sghuman): Clean up includeSaved and includeScans from all constructors and linked
    // calling apps once IC window is complete
    public WifiTracker(Context context, WifiListener wifiListener,
            @NonNull Lifecycle lifecycle, boolean includeSaved, boolean includeScans) {
        this(context, wifiListener,
                context.getSystemService(WifiManager.class),
                context.getSystemService(ConnectivityManager.class),
                context.getSystemService(NetworkScoreManager.class),
                newIntentFilter());
        lifecycle.addObserver(this);
    }

    @VisibleForTesting
    WifiTracker(Context context, WifiListener wifiListener,
            WifiManager wifiManager, ConnectivityManager connectivityManager,
            NetworkScoreManager networkScoreManager,
            IntentFilter filter) {
        mContext = context;
        mWifiManager = wifiManager;
        mListener = new WifiListenerWrapper(wifiListener);
        mConnectivityManager = connectivityManager;

        // check if verbose logging developer option has been turned on or off
        sVerboseLogging = (mWifiManager.getVerboseLoggingLevel() > 0);

        mFilter = filter;

        mNetworkRequest = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        mNetworkScoreManager = networkScoreManager;

        final HandlerThread workThread = new HandlerThread(TAG
                + "{" + Integer.toHexString(System.identityHashCode(this)) + "}",
                Process.THREAD_PRIORITY_BACKGROUND);
        workThread.start();
        setWorkThread(workThread);
    }

    /**
     * Sanity warning: this wipes out mScoreCache, so use with extreme caution
     * @param workThread substitute Handler thread, for testing purposes only
     */
    @VisibleForTesting
    void setWorkThread(HandlerThread workThread) {
        mWorkThread = workThread;
        mWorkHandler = new WorkHandler(workThread.getLooper());
        mScoreCache = new WifiNetworkScoreCache(mContext, new CacheListener(mWorkHandler) {
            @Override
            public void networkCacheUpdated(List<ScoredNetwork> networks) {
                synchronized (mLock) {
                    if (!mRegistered) return;
                }

                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Score cache was updated with networks: " + networks);
                }
                updateNetworkScores();
            }
        });
    }

    @Override
    public void onDestroy() {
        mWorkThread.quit();
    }

    /** Synchronously update the list of access points with the latest information. */
    @MainThread
    public void forceUpdate() {
        synchronized (mLock) {
            mWorkHandler.removeMessages(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
            mLastInfo = mWifiManager.getConnectionInfo();
            mLastNetworkInfo = mConnectivityManager.getNetworkInfo(mWifiManager.getCurrentNetwork());

            final List<ScanResult> newScanResults = mWifiManager.getScanResults();
            if (isVerboseLoggingEnabled()) {
                Log.i(TAG, "Fetched scan results: " + newScanResults);
            }

            List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
            mInternalAccessPoints.clear();
            updateAccessPointsLocked(newScanResults, configs);
        }
    }

    /**
     * Temporarily stop scanning for wifi networks.
     *
     * <p>Sets {@link #mStaleScanResults} to true.
     */
    private void pauseScanning() {
        if (mScanner != null) {
            mScanner.pause();
            mScanner = null;
        }
        synchronized (mLock) {
            mStaleScanResults = true;
        }
    }

    /**
     * Resume scanning for wifi networks after it has been paused.
     *
     * <p>The score cache should be registered before this method is invoked.
     */
    public void resumeScanning() {
        if (mScanner == null) {
            mScanner = new Scanner();
        }

        mWorkHandler.sendEmptyMessage(WorkHandler.MSG_RESUME);
        if (mWifiManager.isWifiEnabled()) {
            mScanner.resume();
        }
    }

    /**
     * Start tracking wifi networks and scores.
     *
     * <p>Registers listeners and starts scanning for wifi networks. If this is not called
     * then forceUpdate() must be called to populate getAccessPoints().
     */
    @Override
    @MainThread
    public void onStart() {
        synchronized (mLock) {
            registerScoreCache();

            mNetworkScoringUiEnabled =
                    Settings.Global.getInt(
                            mContext.getContentResolver(),
                            Settings.Global.NETWORK_SCORING_UI_ENABLED, 0) == 1;

            mMaxSpeedLabelScoreCacheAge =
                    Settings.Global.getLong(
                            mContext.getContentResolver(),
                            Settings.Global.SPEED_LABEL_CACHE_EVICTION_AGE_MILLIS,
                            DEFAULT_MAX_CACHED_SCORE_AGE_MILLIS);

            resumeScanning();
            if (!mRegistered) {
                mContext.registerReceiver(mReceiver, mFilter);
                // NetworkCallback objects cannot be reused. http://b/20701525 .
                mNetworkCallback = new WifiTrackerNetworkCallback();
                mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);
                mRegistered = true;
            }
        }
    }

    private void registerScoreCache() {
        mNetworkScoreManager.registerNetworkScoreCache(
                NetworkKey.TYPE_WIFI,
                mScoreCache,
                NetworkScoreManager.CACHE_FILTER_SCAN_RESULTS);
    }

    private void requestScoresForNetworkKeys(Collection<NetworkKey> keys) {
        if (keys.isEmpty()) return;

        if (DBG()) {
            Log.d(TAG, "Requesting scores for Network Keys: " + keys);
        }
        mNetworkScoreManager.requestScores(keys.toArray(new NetworkKey[keys.size()]));
        synchronized (mLock) {
            mRequestedScores.addAll(keys);
        }
    }

    /**
     * Stop tracking wifi networks and scores.
     *
     * <p>This should always be called when done with a WifiTracker (if onStart was called) to
     * ensure proper cleanup and prevent any further callbacks from occurring.
     *
     * <p>Calling this method will set the {@link #mStaleScanResults} bit, which prevents
     * {@link WifiListener#onAccessPointsChanged()} callbacks from being invoked (until the bit
     * is unset on the next SCAN_RESULTS_AVAILABLE_ACTION).
     */
    @Override
    @MainThread
    public void onStop() {
        synchronized (mLock) {
            if (mRegistered) {
                mContext.unregisterReceiver(mReceiver);
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
                mRegistered = false;
            }
            unregisterScoreCache();
            pauseScanning(); // and set mStaleScanResults

            mWorkHandler.removePendingMessages();
        }
    }

    private void unregisterScoreCache() {
        mNetworkScoreManager.unregisterNetworkScoreCache(NetworkKey.TYPE_WIFI, mScoreCache);

        // We do not want to clear the existing scores in the cache, as this method is called during
        // stop tracking on activity pause. Hence, on resumption we want the ability to show the
        // last known, potentially stale, scores. However, by clearing requested scores, the scores
        // will be requested again upon resumption of tracking, and if any changes have occurred
        // the listeners (UI) will be updated accordingly.
        synchronized (mLock) {
            mRequestedScores.clear();
        }
    }

    /**
     * Gets the current list of access points.
     *
     * <p>This method is can be called on an abitrary thread by clients, but is normally called on
     * the UI Thread by the rendering App.
     */
    @AnyThread
    public List<AccessPoint> getAccessPoints() {
        // TODO(sghuman): Investigate how to eliminate or reduce the need for locking now that we
        // have transitioned to a single worker thread model.

        synchronized (mLock) {
            return new ArrayList<>(mInternalAccessPoints);
        }
    }

    public WifiManager getManager() {
        return mWifiManager;
    }

    public boolean isWifiEnabled() {
        return mWifiManager.isWifiEnabled();
    }

    /**
     * Returns the number of saved networks on the device, regardless of whether the WifiTracker
     * is tracking saved networks.
     * TODO(b/62292448): remove this function and update callsites to use WifiSavedConfigUtils
     * directly.
     */
    public int getNumSavedNetworks() {
        return WifiSavedConfigUtils.getAllConfigs(mContext, mWifiManager).size();
    }

    public boolean isConnected() {
        return mConnected.get();
    }

    public void dump(PrintWriter pw) {
        pw.println("  - wifi tracker ------");
        for (AccessPoint accessPoint : getAccessPoints()) {
            pw.println("  " + accessPoint);
        }
    }

    private void handleResume() {
        // TODO(sghuman): Investigate removing this and replacing it with a cache eviction call
        // instead.
        mScanResultCache.clear();
        mSeenBssids.clear();
    }

    private Collection<ScanResult> updateScanResultCache(final List<ScanResult> newResults) {
        // TODO(sghuman): Delete this and replace it with the Map of Ap Keys to ScanResults
        for (ScanResult newResult : newResults) {
            if (newResult.SSID == null || newResult.SSID.isEmpty()) {
                continue;
            }
            mScanResultCache.put(newResult.BSSID, newResult);
        }

        // Don't evict old results if no new scan results
        if (!mStaleScanResults) {
            evictOldScans();
        }

        // TODO(sghuman): Update a Map<ApKey, List<ScanResults>> variable to be reused later after
        // double threads have been removed.

        return mScanResultCache.values();
    }

    /**
     * Remove old scan results from the cache.
     *
     * <p>Should only ever be invoked from {@link #updateScanResultCache(List)} when
     * {@link #mStaleScanResults} is false.
     */
    private void evictOldScans() {
        long nowMs = SystemClock.elapsedRealtime();
        for (Iterator<ScanResult> iter = mScanResultCache.values().iterator(); iter.hasNext(); ) {
            ScanResult result = iter.next();
            // result timestamp is in microseconds
            if (nowMs - result.timestamp / 1000 > MAX_SCAN_RESULT_AGE_MILLIS) {
                iter.remove();
            }
        }
    }

    private WifiConfiguration getWifiConfigurationForNetworkId(
            int networkId, final List<WifiConfiguration> configs) {
        if (configs != null) {
            for (WifiConfiguration config : configs) {
                if (mLastInfo != null && networkId == config.networkId &&
                        !(config.selfAdded && config.numAssociation == 0)) {
                    return config;
                }
            }
        }
        return null;
    }

    /**
     * Safely modify {@link #mInternalAccessPoints} by acquiring {@link #mLock} first.
     *
     * <p>Will not perform the update if {@link #mStaleScanResults} is true
     */
    private void updateAccessPoints() {
        List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
        final List<ScanResult> newScanResults = mWifiManager.getScanResults();
        if (isVerboseLoggingEnabled()) {
            Log.i(TAG, "Fetched scan results: " + newScanResults);
        }

        synchronized (mLock) {
            if(!mStaleScanResults) {
                updateAccessPointsLocked(newScanResults, configs);
            }
        }
    }

    /**
     * Update the internal list of access points.
     *
     * <p>Do not call directly (except for forceUpdate), use {@link #updateAccessPoints()} which
     * acquires the lock first.
     */
    @GuardedBy("mLock")
    private void updateAccessPointsLocked(final List<ScanResult> newScanResults,
            List<WifiConfiguration> configs) {
        // TODO(sghuman): Reduce the synchronization time by only holding the lock when
        // modifying lists exposed to operations on the MainThread (getAccessPoints, stopTracking,
        // startTracking, etc).

        WifiConfiguration connectionConfig = null;
        if (mLastInfo != null) {
            connectionConfig = getWifiConfigurationForNetworkId(
                    mLastInfo.getNetworkId(), configs);
        }

        // Swap the current access points into a cached list.
        List<AccessPoint> cachedAccessPoints = new ArrayList<>(mInternalAccessPoints);
        ArrayList<AccessPoint> accessPoints = new ArrayList<>();

        // Clear out the configs so we don't think something is saved when it isn't.
        for (AccessPoint accessPoint : cachedAccessPoints) {
            accessPoint.clearConfig();
        }

        final Collection<ScanResult> results = updateScanResultCache(newScanResults);

        final Map<String, WifiConfiguration> configsByKey = new ArrayMap(configs.size());
        if (configs != null) {
            for (WifiConfiguration config : configs) {
                configsByKey.put(AccessPoint.getKey(config), config);
            }
        }

        final List<NetworkKey> scoresToRequest = new ArrayList<>();
        if (results != null) {
            // TODO(sghuman): Move this loop to updateScanResultCache and make instance variable
            // after double handlers are removed.
            ArrayMap<String, List<ScanResult>> scanResultsByApKey = new ArrayMap<>();
            for (ScanResult result : results) {
                // Ignore hidden and ad-hoc networks.
                if (result.SSID == null || result.SSID.length() == 0 ||
                        result.capabilities.contains("[IBSS]")) {
                    continue;
                }

                NetworkKey key = NetworkKey.createFromScanResult(result);
                if (key != null && !mRequestedScores.contains(key)) {
                    scoresToRequest.add(key);
                }

                String apKey = AccessPoint.getKey(result);
                List<ScanResult> resultList;
                if (scanResultsByApKey.containsKey(apKey)) {
                    resultList = scanResultsByApKey.get(apKey);
                } else {
                    resultList = new ArrayList<>();
                    scanResultsByApKey.put(apKey, resultList);
                }

                resultList.add(result);
            }

            for (Map.Entry<String, List<ScanResult>> entry : scanResultsByApKey.entrySet()) {
                // List can not be empty as it is dynamically constructed on each iteration
                ScanResult firstResult = entry.getValue().get(0);

                AccessPoint accessPoint =
                        getCachedOrCreate(entry.getValue(), cachedAccessPoints);
                if (mLastInfo != null && mLastNetworkInfo != null) {
                    accessPoint.update(connectionConfig, mLastInfo, mLastNetworkInfo);
                }

                // Update the matching config if there is one, to populate saved network info
                WifiConfiguration config = configsByKey.get(entry.getKey());
                if (config != null) {
                    accessPoint.update(config);
                }

                accessPoints.add(accessPoint);
            }
        }

        requestScoresForNetworkKeys(scoresToRequest);
        for (AccessPoint ap : accessPoints) {
            ap.update(mScoreCache, mNetworkScoringUiEnabled, mMaxSpeedLabelScoreCacheAge);
        }

        // Pre-sort accessPoints to speed preference insertion
        Collections.sort(accessPoints);

        // Log accesspoints that were deleted
        if (DBG()) {
            Log.d(TAG, "------ Dumping SSIDs that were not seen on this scan ------");
            for (AccessPoint prevAccessPoint : mInternalAccessPoints) {
                if (prevAccessPoint.getSsid() == null)
                    continue;
                String prevSsid = prevAccessPoint.getSsidStr();
                boolean found = false;
                for (AccessPoint newAccessPoint : accessPoints) {
                    if (newAccessPoint.getSsidStr() != null && newAccessPoint.getSsidStr()
                            .equals(prevSsid)) {
                        found = true;
                        break;
                    }
                }
                if (!found)
                    Log.d(TAG, "Did not find " + prevSsid + " in this scan");
            }
            Log.d(TAG, "---- Done dumping SSIDs that were not seen on this scan ----");
        }

        mInternalAccessPoints.clear();
        mInternalAccessPoints.addAll(accessPoints);

        conditionallyNotifyListeners();
    }

    @VisibleForTesting
    AccessPoint getCachedOrCreate(
            List<ScanResult> scanResults,
            List<AccessPoint> cache) {
        final int N = cache.size();
        for (int i = 0; i < N; i++) {
            if (cache.get(i).getKey().equals(AccessPoint.getKey(scanResults.get(0)))) {
                AccessPoint ret = cache.remove(i);
                ret.setScanResults(scanResults);
                return ret;
            }
        }
        final AccessPoint accessPoint = new AccessPoint(mContext, scanResults);
        return accessPoint;
    }

    @VisibleForTesting
    AccessPoint getCachedOrCreate(WifiConfiguration config, List<AccessPoint> cache) {
        final int N = cache.size();
        for (int i = 0; i < N; i++) {
            if (cache.get(i).matches(config)) {
                AccessPoint ret = cache.remove(i);
                ret.loadConfig(config);

                return ret;
            }
        }
        final AccessPoint accessPoint = new AccessPoint(mContext, config);
        return accessPoint;
    }

    private void updateNetworkInfo(NetworkInfo networkInfo) {
        /* sticky broadcasts can call this when wifi is disabled */
        if (!mWifiManager.isWifiEnabled()) {
            clearAccessPointsAndConditionallyUpdate();
            return;
        }

        if (networkInfo != null) {
            mLastNetworkInfo = networkInfo;
            if (DBG()) {
                Log.d(TAG, "mLastNetworkInfo set: " + mLastNetworkInfo);
            }
        }

        WifiConfiguration connectionConfig = null;

        mLastInfo = mWifiManager.getConnectionInfo();
        if (DBG()) {
            Log.d(TAG, "mLastInfo set as: " + mLastInfo);
        }
        if (mLastInfo != null) {
            connectionConfig = getWifiConfigurationForNetworkId(mLastInfo.getNetworkId(),
                    mWifiManager.getConfiguredNetworks());
        }

        boolean updated = false;
        boolean reorder = false; // Only reorder if connected AP was changed

        synchronized (mLock) {
            for (int i = mInternalAccessPoints.size() - 1; i >= 0; --i) {
                AccessPoint ap = mInternalAccessPoints.get(i);
                boolean previouslyConnected = ap.isActive();
                if (ap.update(connectionConfig, mLastInfo, mLastNetworkInfo)) {
                    updated = true;
                    if (previouslyConnected != ap.isActive()) reorder = true;
                }
                if (ap.update(mScoreCache, mNetworkScoringUiEnabled, mMaxSpeedLabelScoreCacheAge)) {
                    reorder = true;
                    updated = true;
                }
            }

            if (reorder) {
                Collections.sort(mInternalAccessPoints);
            }
            if (updated) {
                conditionallyNotifyListeners();
            }
        }
    }

    /**
     * Clears the access point list and conditionally invokes
     * {@link WifiListener#onAccessPointsChanged()} if required (i.e. the list was not already
     * empty).
     */
    private void clearAccessPointsAndConditionallyUpdate() {
        synchronized (mLock) {
            if (!mInternalAccessPoints.isEmpty()) {
                mInternalAccessPoints.clear();
                mListener.onAccessPointsChanged();
            }
        }
    }

    /**
     * Update all the internal access points rankingScores, badge and metering.
     *
     * <p>Will trigger a resort and notify listeners of changes if applicable.
     *
     * <p>Synchronized on {@link #mLock}.
     */
    private void updateNetworkScores() {
        synchronized (mLock) {
            boolean updated = false;
            for (int i = 0; i < mInternalAccessPoints.size(); i++) {
                if (mInternalAccessPoints.get(i).update(
                        mScoreCache, mNetworkScoringUiEnabled, mMaxSpeedLabelScoreCacheAge)) {
                    updated = true;
                }
            }
            if (updated) {
                Collections.sort(mInternalAccessPoints);
                conditionallyNotifyListeners();
            }
        }
    }

    private void updateWifiState(int state) {
        mWorkHandler.obtainMessage(WorkHandler.MSG_UPDATE_WIFI_STATE, state, 0).sendToTarget();
        if (!mWifiManager.isWifiEnabled()) {
            clearAccessPointsAndConditionallyUpdate();
        }
    }

    @VisibleForTesting
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                updateWifiState(intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN));
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                mWorkHandler
                        .obtainMessage(
                            WorkHandler.MSG_UPDATE_ACCESS_POINTS,
                            WorkHandler.CLEAR_STALE_SCAN_RESULTS,
                            0)
                        .sendToTarget();
            } else if (WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION.equals(action)
                    || WifiManager.LINK_CONFIGURATION_CHANGED_ACTION.equals(action)) {
                mWorkHandler.sendEmptyMessage(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                if(mConnected.get() != info.isConnected()) {
                    mConnected.set(info.isConnected());
                    mListener.onConnectedChanged();
                }

                mWorkHandler.obtainMessage(WorkHandler.MSG_UPDATE_NETWORK_INFO, info)
                        .sendToTarget();
                mWorkHandler.sendEmptyMessage(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
            } else if (WifiManager.RSSI_CHANGED_ACTION.equals(action)) {
                NetworkInfo info =
                        mConnectivityManager.getNetworkInfo(mWifiManager.getCurrentNetwork());
                mWorkHandler.obtainMessage(WorkHandler.MSG_UPDATE_NETWORK_INFO, info)
                        .sendToTarget();
            }
        }
    };

    private final class WifiTrackerNetworkCallback extends ConnectivityManager.NetworkCallback {
        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            if (network.equals(mWifiManager.getCurrentNetwork())) {
                // We don't send a NetworkInfo object along with this message, because even if we
                // fetch one from ConnectivityManager, it might be older than the most recent
                // NetworkInfo message we got via a WIFI_STATE_CHANGED broadcast.
                mWorkHandler.sendEmptyMessage(WorkHandler.MSG_UPDATE_NETWORK_INFO);
            }
        }
    }

    @VisibleForTesting
    final class WorkHandler extends Handler {
        @VisibleForTesting static final int MSG_UPDATE_ACCESS_POINTS = 0;
        private static final int MSG_UPDATE_NETWORK_INFO = 1;
        private static final int MSG_RESUME = 2;
        private static final int MSG_UPDATE_WIFI_STATE = 3;

        private static final int CLEAR_STALE_SCAN_RESULTS = 1;

        public WorkHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                processMessage(msg);
            }
        }

        private void processMessage(Message msg) {
            if (!mRegistered) return;

            switch (msg.what) {
                case MSG_UPDATE_ACCESS_POINTS:
                    if (msg.arg1 == CLEAR_STALE_SCAN_RESULTS) {
                        mStaleScanResults = false;
                    }
                    updateAccessPoints();
                    break;
                case MSG_UPDATE_NETWORK_INFO:
                    updateNetworkInfo((NetworkInfo) msg.obj);
                    break;
                case MSG_RESUME:
                    handleResume();
                    break;
                case MSG_UPDATE_WIFI_STATE:
                    if (msg.arg1 == WifiManager.WIFI_STATE_ENABLED) {
                        if (mScanner != null) {
                            // We only need to resume if mScanner isn't null because
                            // that means we want to be scanning.
                            mScanner.resume();
                        }
                    } else {
                        mLastInfo = null;
                        mLastNetworkInfo = null;
                        if (mScanner != null) {
                            mScanner.pause();
                        }
                        synchronized (mLock) {
                            mStaleScanResults = true;
                        }
                    }
                    mListener.onWifiStateChanged(msg.arg1);
                    break;
            }
        }

        private void removePendingMessages() {
            removeMessages(MSG_UPDATE_ACCESS_POINTS);
            removeMessages(MSG_UPDATE_NETWORK_INFO);
            removeMessages(MSG_RESUME);
            removeMessages(MSG_UPDATE_WIFI_STATE);
        }
    }

    @VisibleForTesting
    class Scanner extends Handler {
        static final int MSG_SCAN = 0;

        private int mRetry = 0;

        void resume() {
            if (!hasMessages(MSG_SCAN)) {
                sendEmptyMessage(MSG_SCAN);
            }
        }

        void pause() {
            mRetry = 0;
            removeMessages(MSG_SCAN);
        }

        @VisibleForTesting
        boolean isScanning() {
            return hasMessages(MSG_SCAN);
        }

        @Override
        public void handleMessage(Message message) {
            if (message.what != MSG_SCAN) return;
            if (mWifiManager.startScan()) {
                mRetry = 0;
            } else if (++mRetry >= 3) {
                mRetry = 0;
                if (mContext != null) {
                    Toast.makeText(mContext, R.string.wifi_fail_to_scan, Toast.LENGTH_LONG).show();
                }
                return;
            }
            sendEmptyMessageDelayed(MSG_SCAN, WIFI_RESCAN_INTERVAL_MS);
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

    /**
     * Wraps the given {@link WifiListener} instance and executes it's methods on the Main Thread.
     *
     * <p>This mechanism allows us to no longer need a separate MainHandler and WorkHandler, which
     * were previously both performing work, while avoiding errors which occur from executing
     * callbacks which manipulate UI elements from a different thread than the MainThread.
     */
    private static class WifiListenerWrapper implements WifiListener {

        private final Handler mHandler;
        private final WifiListener mDelegatee;

        public WifiListenerWrapper(WifiListener listener) {
            mHandler = new Handler(Looper.getMainLooper());
            mDelegatee = listener;
        }

        @Override
        public void onWifiStateChanged(int state) {
            if (isVerboseLoggingEnabled()) {
                Log.i(TAG,
                        String.format("Invoking onWifiStateChanged callback with state %d", state));
            }
            mHandler.post(() -> mDelegatee.onWifiStateChanged(state));
        }

        @Override
        public void onConnectedChanged() {
            if (isVerboseLoggingEnabled()) {
                Log.i(TAG, "Invoking onConnectedChanged callback");
            }
            mHandler.post(() -> mDelegatee.onConnectedChanged());
        }

        @Override
        public void onAccessPointsChanged() {
            if (isVerboseLoggingEnabled()) {
                Log.i(TAG, "Invoking onAccessPointsChanged callback");
            }
            mHandler.post(() -> mDelegatee.onAccessPointsChanged());
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
         * Called when the connection state of wifi has changed and
         * {@link WifiTracker#isConnected()} should be called to get the updated state.
         */
        void onConnectedChanged();

        /**
         * Called to indicate the list of AccessPoints has been updated and
         * {@link WifiTracker#getAccessPoints()} should be called to get the updated list.
         */
        void onAccessPointsChanged();
    }

    /**
     * Invokes {@link WifiListenerWrapper#onAccessPointsChanged()} if {@link #mStaleScanResults}
     * is false.
     */
    private void conditionallyNotifyListeners() {
        if (mStaleScanResults) {
            return;
        }

        ThreadUtils.postOnMainThread(() -> mListener.onAccessPointsChanged());
    }
}
