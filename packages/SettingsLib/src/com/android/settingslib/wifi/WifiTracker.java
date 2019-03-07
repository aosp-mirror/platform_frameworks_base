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
import android.net.wifi.hotspot2.OsuProvider;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

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
import java.util.ListIterator;
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
    private static final long MAX_SCAN_RESULT_AGE_MILLIS = 15000;

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

    private final Context mContext;
    private final WifiManager mWifiManager;
    private final IntentFilter mFilter;
    private final ConnectivityManager mConnectivityManager;
    private final NetworkRequest mNetworkRequest;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final WifiListenerExecutor mListener;
    @VisibleForTesting Handler mWorkHandler;
    private HandlerThread mWorkThread;

    private WifiTrackerNetworkCallback mNetworkCallback;

    /**
     * Synchronization lock for managing concurrency between main and worker threads.
     *
     * <p>This lock should be held for all modifications to {@link #mInternalAccessPoints}.
     */
    private final Object mLock = new Object();

    /** The list of AccessPoints, aggregated visible ScanResults with metadata. */
    @GuardedBy("mLock")
    private final List<AccessPoint> mInternalAccessPoints = new ArrayList<>();

    @GuardedBy("mLock")
    private final Set<NetworkKey> mRequestedScores = new ArraySet<>();

    /**
     * Tracks whether fresh scan results have been received since scanning start.
     *
     * <p>If this variable is false, we will not invoke callbacks so that we do not
     * update the UI with stale data / clear out existing UI elements prematurely.
     */
    private boolean mStaleScanResults = true;

    // Does not need to be locked as it only updated on the worker thread, with the exception of
    // during onStart, which occurs before the receiver is registered on the work handler.
    private final HashMap<String, ScanResult> mScanResultCache = new HashMap<>();
    private boolean mRegistered;

    private NetworkInfo mLastNetworkInfo;
    private WifiInfo mLastInfo;

    private final NetworkScoreManager mNetworkScoreManager;
    private WifiNetworkScoreCache mScoreCache;
    private boolean mNetworkScoringUiEnabled;
    private long mMaxSpeedLabelScoreCacheAge;

    private static final String WIFI_SECURITY_PSK = "PSK";
    private static final String WIFI_SECURITY_EAP = "EAP";
    private static final String WIFI_SECURITY_SAE = "SAE";
    private static final String WIFI_SECURITY_OWE = "OWE";
    private static final String WIFI_SECURITY_SUITE_B_192 = "SUITE_B_192";

    @VisibleForTesting
    Scanner mScanner;

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

    // TODO(sghuman): Clean up includeSaved and includeScans from all constructors and linked
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
        mListener = new WifiListenerExecutor(wifiListener);
        mConnectivityManager = connectivityManager;

        // check if verbose logging developer option has been turned on or off
        sVerboseLogging = mWifiManager != null && (mWifiManager.getVerboseLoggingLevel() > 0);

        mFilter = filter;

        mNetworkRequest = new NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        mNetworkScoreManager = networkScoreManager;

        // TODO(sghuman): Remove this and create less hacky solution for testing
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
    // TODO(sghuman): Remove this method, this needs to happen in a factory method and be passed in
    // during construction
    void setWorkThread(HandlerThread workThread) {
        mWorkThread = workThread;
        mWorkHandler = new Handler(workThread.getLooper());
        mScoreCache = new WifiNetworkScoreCache(mContext, new CacheListener(mWorkHandler) {
            @Override
            public void networkCacheUpdated(List<ScoredNetwork> networks) {
                if (!mRegistered) return;

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
        mStaleScanResults = true;
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

        if (isWifiEnabled()) {
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
        // fetch current ScanResults instead of waiting for broadcast of fresh results
        forceUpdate();

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
            mContext.registerReceiver(mReceiver, mFilter, null /* permission */, mWorkHandler);
            // NetworkCallback objects cannot be reused. http://b/20701525 .
            mNetworkCallback = new WifiTrackerNetworkCallback();
            mConnectivityManager.registerNetworkCallback(
                    mNetworkRequest, mNetworkCallback, mWorkHandler);
            mRegistered = true;
        }
    }


    /**
     * Synchronously update the list of access points with the latest information.
     *
     * <p>Intended to only be invoked within {@link #onStart()}.
     */
    @MainThread
    @VisibleForTesting
    void forceUpdate() {
        mLastInfo = mWifiManager.getConnectionInfo();
        mLastNetworkInfo = mConnectivityManager.getNetworkInfo(mWifiManager.getCurrentNetwork());

        fetchScansAndConfigsAndUpdateAccessPoints();
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
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mRegistered = false;
        }
        unregisterScoreCache();
        pauseScanning(); // and set mStaleScanResults

        mWorkHandler.removeCallbacksAndMessages(null /* remove all */);
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
        synchronized (mLock) {
            return new ArrayList<>(mInternalAccessPoints);
        }
    }

    public WifiManager getManager() {
        return mWifiManager;
    }

    public boolean isWifiEnabled() {
        return mWifiManager != null && mWifiManager.isWifiEnabled();
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

    private ArrayMap<String, List<ScanResult>> updateScanResultCache(
            final List<ScanResult> newResults) {
        // TODO(sghuman): Delete this and replace it with the Map of Ap Keys to ScanResults for
        // memory efficiency
        for (ScanResult newResult : newResults) {
            if (newResult.SSID == null || newResult.SSID.isEmpty()) {
                continue;
            }
            mScanResultCache.put(newResult.BSSID, newResult);
        }

        // Evict old results in all conditions
        evictOldScans();

        ArrayMap<String, List<ScanResult>> scanResultsByApKey = new ArrayMap<>();
        for (ScanResult result : mScanResultCache.values()) {
            // Ignore hidden and ad-hoc networks.
            if (result.SSID == null || result.SSID.length() == 0 ||
                    result.capabilities.contains("[IBSS]")) {
                continue;
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

        return scanResultsByApKey;
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
     * Retrieves latest scan results and wifi configs, then calls
     * {@link #updateAccessPoints(List, List)}.
     */
    private void fetchScansAndConfigsAndUpdateAccessPoints() {
        List<ScanResult> newScanResults = mWifiManager.getScanResults();

        // Filter all unsupported networks from the scan result list
        final List<ScanResult> filteredScanResults =
                filterScanResultsByCapabilities(newScanResults);

        if (isVerboseLoggingEnabled()) {
            Log.i(TAG, "Fetched scan results: " + filteredScanResults);
        }

        List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
        updateAccessPoints(filteredScanResults, configs);
    }

    /** Update the internal list of access points. */
    private void updateAccessPoints(final List<ScanResult> newScanResults,
            List<WifiConfiguration> configs) {

        // Map configs and scan results necessary to make AccessPoints
        final Map<String, WifiConfiguration> configsByKey = new ArrayMap(configs.size());
        if (configs != null) {
            for (WifiConfiguration config : configs) {
                configsByKey.put(AccessPoint.getKey(config), config);
            }
        }
        ArrayMap<String, List<ScanResult>> scanResultsByApKey =
                updateScanResultCache(newScanResults);

        WifiConfiguration connectionConfig = null;
        if (mLastInfo != null) {
            connectionConfig = getWifiConfigurationForNetworkId(mLastInfo.getNetworkId(), configs);
        }

        // Rather than dropping and reacquiring the lock multiple times in this method, we lock
        // once for efficiency of lock acquisition time and readability
        synchronized (mLock) {
            // Swap the current access points into a cached list for maintaining AP listeners
            List<AccessPoint> cachedAccessPoints;
            cachedAccessPoints = new ArrayList<>(mInternalAccessPoints);

            ArrayList<AccessPoint> accessPoints = new ArrayList<>();

            final List<NetworkKey> scoresToRequest = new ArrayList<>();

            for (Map.Entry<String, List<ScanResult>> entry : scanResultsByApKey.entrySet()) {
                for (ScanResult result : entry.getValue()) {
                    NetworkKey key = NetworkKey.createFromScanResult(result);
                    if (key != null && !mRequestedScores.contains(key)) {
                        scoresToRequest.add(key);
                    }
                }

                AccessPoint accessPoint =
                        getCachedOrCreate(entry.getValue(), cachedAccessPoints);

                // Update the matching config if there is one, to populate saved network info
                accessPoint.update(configsByKey.get(entry.getKey()));

                accessPoints.add(accessPoint);
            }

            List<ScanResult> cachedScanResults = new ArrayList<>(mScanResultCache.values());

            // Add a unique Passpoint AccessPoint for each Passpoint profile's FQDN.
            accessPoints.addAll(updatePasspointAccessPoints(
                    mWifiManager.getAllMatchingWifiConfigs(cachedScanResults), cachedAccessPoints));

            // Add OSU Provider AccessPoints
            accessPoints.addAll(updateOsuAccessPoints(
                    mWifiManager.getMatchingOsuProviders(cachedScanResults), cachedAccessPoints));

            if (mLastInfo != null && mLastNetworkInfo != null) {
                for (AccessPoint ap : accessPoints) {
                    ap.update(connectionConfig, mLastInfo, mLastNetworkInfo);
                }
            }

            // If there were no scan results, create an AP for the currently connected network (if
            // it exists).
            if (accessPoints.isEmpty() && connectionConfig != null) {
                AccessPoint activeAp = new AccessPoint(mContext, connectionConfig);
                activeAp.update(connectionConfig, mLastInfo, mLastNetworkInfo);
                accessPoints.add(activeAp);
                scoresToRequest.add(NetworkKey.createFromWifiInfo(mLastInfo));
            }

            requestScoresForNetworkKeys(scoresToRequest);
            for (AccessPoint ap : accessPoints) {
                ap.update(mScoreCache, mNetworkScoringUiEnabled, mMaxSpeedLabelScoreCacheAge);
            }

            // Pre-sort accessPoints to speed preference insertion
            Collections.sort(accessPoints);

            // Log accesspoints that are being removed
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
        }

        conditionallyNotifyListeners();
    }

    @VisibleForTesting
    List<AccessPoint> updatePasspointAccessPoints(
            List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> passpointConfigsAndScans,
            List<AccessPoint> accessPointCache) {
        List<AccessPoint> accessPoints = new ArrayList<>();

        Set<String> seenFQDNs = new ArraySet<>();
        for (Pair<WifiConfiguration,
                Map<Integer, List<ScanResult>>> pairing : passpointConfigsAndScans) {
            WifiConfiguration config = pairing.first;
            if (seenFQDNs.add(config.FQDN)) {
                List<ScanResult> homeScans =
                        pairing.second.get(WifiManager.PASSPOINT_HOME_NETWORK);
                List<ScanResult> roamingScans =
                        pairing.second.get(WifiManager.PASSPOINT_ROAMING_NETWORK);

                AccessPoint accessPoint =
                        getCachedOrCreatePasspoint(config, homeScans, roamingScans,
                                accessPointCache);
                accessPoints.add(accessPoint);
            }
        }
        return accessPoints;
    }

    @VisibleForTesting
    List<AccessPoint> updateOsuAccessPoints(
            Map<OsuProvider, List<ScanResult>> providersAndScans,
            List<AccessPoint> accessPointCache) {
        List<AccessPoint> accessPoints = new ArrayList<>();

        Set<OsuProvider> alreadyProvisioned = mWifiManager
                .getMatchingPasspointConfigsForOsuProviders(
                        providersAndScans.keySet()).keySet();
        for (OsuProvider provider : providersAndScans.keySet()) {
            if (!alreadyProvisioned.contains(provider)) {
                AccessPoint accessPointOsu =
                        getCachedOrCreateOsu(provider, providersAndScans.get(provider),
                                accessPointCache);
                accessPoints.add(accessPointOsu);
            }
        }
        return accessPoints;
    }

    private AccessPoint getCachedOrCreate(
            List<ScanResult> scanResults,
            List<AccessPoint> cache) {
        AccessPoint accessPoint = getCachedByKey(cache, AccessPoint.getKey(scanResults.get(0)));
        if (accessPoint == null) {
            accessPoint = new AccessPoint(mContext, scanResults);
        } else {
            accessPoint.setScanResults(scanResults);
        }
        return accessPoint;
    }

    private AccessPoint getCachedOrCreatePasspoint(
            WifiConfiguration config,
            List<ScanResult> homeScans,
            List<ScanResult> roamingScans,
            List<AccessPoint> cache) {
        AccessPoint accessPoint = getCachedByKey(cache, AccessPoint.getKey(config));
        if (accessPoint == null) {
            accessPoint = new AccessPoint(mContext, config, homeScans, roamingScans);
        } else {
            accessPoint.setScanResultsPasspoint(homeScans, roamingScans);
        }
        return accessPoint;
    }

    private AccessPoint getCachedOrCreateOsu(
            OsuProvider provider,
            List<ScanResult> scanResults,
            List<AccessPoint> cache) {
        AccessPoint accessPoint = getCachedByKey(cache, AccessPoint.getKey(provider));
        if (accessPoint == null) {
            accessPoint = new AccessPoint(mContext, provider, scanResults);
        } else {
            accessPoint.setScanResults(scanResults);
        }
        return accessPoint;
    }

    private AccessPoint getCachedByKey(List<AccessPoint> cache, String key) {
        ListIterator<AccessPoint> lit = cache.listIterator();
        while (lit.hasNext()) {
            AccessPoint currentAccessPoint = lit.next();
            if (currentAccessPoint.getKey().equals(key)) {
                lit.remove();
                return currentAccessPoint;
            }
        }
        return null;
    }

    private void updateNetworkInfo(NetworkInfo networkInfo) {

        /* Sticky broadcasts can call this when wifi is disabled */
        if (!isWifiEnabled()) {
            clearAccessPointsAndConditionallyUpdate();
            return;
        }

        if (networkInfo != null) {
            mLastNetworkInfo = networkInfo;
            if (DBG()) {
                Log.d(TAG, "mLastNetworkInfo set: " + mLastNetworkInfo);
            }

            if(networkInfo.isConnected() != mConnected.getAndSet(networkInfo.isConnected())) {
                mListener.onConnectedChanged();
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
                conditionallyNotifyListeners();
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

    /**
     *  Receiver for handling broadcasts.
     *
     *  This receiver is registered on the WorkHandler.
     */
    @VisibleForTesting
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                updateWifiState(
                        intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                WifiManager.WIFI_STATE_UNKNOWN));
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                mStaleScanResults = false;

                fetchScansAndConfigsAndUpdateAccessPoints();
            } else if (WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION.equals(action)
                    || WifiManager.LINK_CONFIGURATION_CHANGED_ACTION.equals(action)) {
                fetchScansAndConfigsAndUpdateAccessPoints();
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                // TODO(sghuman): Refactor these methods so they cannot result in duplicate
                // onAccessPointsChanged updates being called from this intent.
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                updateNetworkInfo(info);
                fetchScansAndConfigsAndUpdateAccessPoints();
            } else if (WifiManager.RSSI_CHANGED_ACTION.equals(action)) {
                NetworkInfo info =
                        mConnectivityManager.getNetworkInfo(mWifiManager.getCurrentNetwork());
                updateNetworkInfo(info);
            }
        }
    };

    /**
     * Handles updates to WifiState.
     *
     * <p>If Wifi is not enabled in the enabled state, {@link #mStaleScanResults} will be set to
     * true.
     */
    private void updateWifiState(int state) {
        if (state == WifiManager.WIFI_STATE_ENABLED) {
            if (mScanner != null) {
                // We only need to resume if mScanner isn't null because
                // that means we want to be scanning.
                mScanner.resume();
            }
        } else {
            clearAccessPointsAndConditionallyUpdate();
            mLastInfo = null;
            mLastNetworkInfo = null;
            if (mScanner != null) {
                mScanner.pause();
            }
            mStaleScanResults = true;
        }
        mListener.onWifiStateChanged(state);
    }

    private final class WifiTrackerNetworkCallback extends ConnectivityManager.NetworkCallback {
        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            if (network.equals(mWifiManager.getCurrentNetwork())) {
                // TODO(sghuman): Investigate whether this comment still holds true and if it makes
                // more sense fetch the latest network info here:

                // We don't send a NetworkInfo object along with this message, because even if we
                // fetch one from ConnectivityManager, it might be older than the most recent
                // NetworkInfo message we got via a WIFI_STATE_CHANGED broadcast.
                updateNetworkInfo(null);
            }
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
     * Wraps the given {@link WifiListener} instance and executes its methods on the Main Thread.
     *
     * <p>Also logs all callbacks invocations when verbose logging is enabled.
     */
    @VisibleForTesting class WifiListenerExecutor implements WifiListener {

        private final WifiListener mDelegatee;

        public WifiListenerExecutor(WifiListener listener) {
            mDelegatee = listener;
        }

        @Override
        public void onWifiStateChanged(int state) {
            runAndLog(() -> mDelegatee.onWifiStateChanged(state),
                    String.format("Invoking onWifiStateChanged callback with state %d", state));
        }

        @Override
        public void onConnectedChanged() {
            runAndLog(mDelegatee::onConnectedChanged, "Invoking onConnectedChanged callback");
        }

        @Override
        public void onAccessPointsChanged() {
            runAndLog(mDelegatee::onAccessPointsChanged, "Invoking onAccessPointsChanged callback");
        }

        private void runAndLog(Runnable r, String verboseLog) {
            ThreadUtils.postOnMainThread(() -> {
                if (mRegistered) {
                    if (isVerboseLoggingEnabled()) {
                        Log.i(TAG, verboseLog);
                    }
                    r.run();
                }
            });
        }
    }

    /**
     * WifiListener interface that defines callbacks indicating state changes in WifiTracker.
     *
     * <p>All callbacks are invoked on the MainThread.
     */
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
     * Invokes {@link WifiListenerExecutor#onAccessPointsChanged()} iif {@link #mStaleScanResults}
     * is false.
     */
    private void conditionallyNotifyListeners() {
        if (mStaleScanResults) {
            return;
        }

        mListener.onAccessPointsChanged();
    }

    /**
     * Filters unsupported networks from scan results. New WPA3 networks and OWE networks
     * may not be compatible with the device HW/SW.
     * @param scanResults List of scan results
     * @return List of filtered scan results based on local device capabilities
     */
    private List<ScanResult> filterScanResultsByCapabilities(List<ScanResult> scanResults) {
        if (scanResults == null) {
            return null;
        }

        // Get and cache advanced capabilities
        final boolean isOweSupported = mWifiManager.isEnhancedOpenSupported();
        final boolean isSaeSupported = mWifiManager.isWpa3SaeSupported();
        final boolean isSuiteBSupported = mWifiManager.isWpa3SuiteBSupported();

        List<ScanResult> filteredScanResultList = new ArrayList<>();

        // Iterate through the list of scan results and filter out APs which are not
        // compatible with our device.
        for (ScanResult scanResult : scanResults) {
            if (scanResult.capabilities.contains(WIFI_SECURITY_PSK)) {
                // All devices (today) support RSN-PSK or WPA-PSK
                // Add this here because some APs may support both PSK and SAE and the check
                // below will filter it out.
                filteredScanResultList.add(scanResult);
                continue;
            }

            if ((scanResult.capabilities.contains(WIFI_SECURITY_SUITE_B_192) && !isSuiteBSupported)
                    || (scanResult.capabilities.contains(WIFI_SECURITY_SAE) && !isSaeSupported)
                    || (scanResult.capabilities.contains(WIFI_SECURITY_OWE) && !isOweSupported)) {
                if (isVerboseLoggingEnabled()) {
                    Log.v(TAG, "filterScanResultsByCapabilities: Filtering SSID "
                            + scanResult.SSID + " with capabilities: " + scanResult.capabilities);
                }
            } else {
                // Safe to add
                filteredScanResultList.add(scanResult);
            }
        }

        return filteredScanResultList;
    }
}
