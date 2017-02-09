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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
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
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;

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
public class WifiTracker {
    // TODO(sghuman): Document remaining methods with @UiThread and @WorkerThread where possible.
    // TODO(sghuman): Refactor to avoid calling certain methods on the UiThread.

    private static final String TAG = "WifiTracker";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    /** verbose logging flag. this flag is set thru developer debugging options
     * and used so as to assist with in-the-field WiFi connectivity debugging  */
    public static int sVerboseLogging = 0;

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
    private final boolean mIncludeSaved;
    private final boolean mIncludeScans;
    private final boolean mIncludePasspoints;
    private final MainHandler mMainHandler;
    private final WorkHandler mWorkHandler;

    private WifiTrackerNetworkCallback mNetworkCallback;

    private boolean mSavedNetworksExist;
    private boolean mRegistered;

    /** Updated using main handler. Clone of this collection is returned
     * from {@link #getAccessPoints()}
     */
    private final List<AccessPoint> mAccessPoints = new ArrayList<>();

    /**
     * Protects APs contained in {@link #mInternalAccessPoints} from being modified concurrently
     * while its being read. Usage contract:
     *
     * 1. MainHandler opens the condition after copying the states thereby
     *    allowing WorkerHandler to mutate the contents.
     * 2. WorkerHandler after mutating the contents, sends a message to MainHandler to copy the
     *    states and closes the condition.
     *
     * This is better than synchronizing on a variable because it prevents MainHandler from
     * unnecessarily blocking/waiting to acquire lock for copying states. When MainHandler is about
     * to access {@link #mInternalAccessPoints}, it is assumed that it has exclusively lock on the
     * contents.
     */
    private final ConditionVariable mInternalAccessPointsWriteLock = new ConditionVariable(true);

    /** Guarded by mInternalAccessPointsWriteLock, updated using worker handler.
     * Never exposed outside this class.
     */
    private final List<AccessPoint> mInternalAccessPoints = new ArrayList<>();

    //visible to both worker and main thread. Guarded by #mInternalAccessPoints
    private final AccessPointListenerAdapter mAccessPointListenerAdapter
            = new AccessPointListenerAdapter();

    private final HashMap<String, Integer> mSeenBssids = new HashMap<>();
    private final HashMap<String, ScanResult> mScanResultCache = new HashMap<>();
    private Integer mScanId = 0;

    private NetworkInfo mLastNetworkInfo;
    private WifiInfo mLastInfo;

    private final NetworkScoreManager mNetworkScoreManager;
    private final WifiNetworkScoreCache mScoreCache;
    private final Set<NetworkKey> mRequestedScores = new ArraySet<>();
    private boolean mNetworkScoringUiEnabled;
    private final ContentObserver mObserver;

    @VisibleForTesting
    Scanner mScanner;

    public WifiTracker(Context context, WifiListener wifiListener,
            boolean includeSaved, boolean includeScans) {
        this(context, wifiListener, null, includeSaved, includeScans);
    }

    public WifiTracker(Context context, WifiListener wifiListener, Looper workerLooper,
            boolean includeSaved, boolean includeScans) {
        this(context, wifiListener, workerLooper, includeSaved, includeScans, false);
    }

    public WifiTracker(Context context, WifiListener wifiListener,
            boolean includeSaved, boolean includeScans, boolean includePasspoints) {
        this(context, wifiListener, null, includeSaved, includeScans, includePasspoints);
    }

    public WifiTracker(Context context, WifiListener wifiListener, Looper workerLooper,
            boolean includeSaved, boolean includeScans, boolean includePasspoints) {
        this(context, wifiListener, workerLooper, includeSaved, includeScans, includePasspoints,
                context.getSystemService(WifiManager.class),
                context.getSystemService(ConnectivityManager.class),
                context.getSystemService(NetworkScoreManager.class), Looper.myLooper()
        );
    }

    @VisibleForTesting
    WifiTracker(Context context, WifiListener wifiListener, Looper workerLooper,
            boolean includeSaved, boolean includeScans, boolean includePasspoints,
            WifiManager wifiManager, ConnectivityManager connectivityManager,
            NetworkScoreManager networkScoreManager, Looper currentLooper) {
        if (!includeSaved && !includeScans) {
            throw new IllegalArgumentException("Must include either saved or scans");
        }
        mContext = context;
        if (currentLooper == null) {
            // When we aren't on a looper thread, default to the main.
            currentLooper = Looper.getMainLooper();
        }
        mMainHandler = new MainHandler(currentLooper);
        mWorkHandler = new WorkHandler(
                workerLooper != null ? workerLooper : currentLooper);
        mWifiManager = wifiManager;
        mIncludeSaved = includeSaved;
        mIncludeScans = includeScans;
        mIncludePasspoints = includePasspoints;
        mListener = wifiListener;
        mConnectivityManager = connectivityManager;

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

        mNetworkRequest = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        mNetworkScoreManager = networkScoreManager;

        mScoreCache = new WifiNetworkScoreCache(context, new CacheListener(mMainHandler) {
            @Override
            public void networkCacheUpdated(List<ScoredNetwork> networks) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Score cache was updated with networks: " + networks);
                }
                Message.obtain(mWorkHandler, WorkHandler.MSG_UPDATE_NETWORK_SCORES).sendToTarget();
            }
        });

        mObserver = new ContentObserver(mWorkHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mNetworkScoringUiEnabled =
                        Settings.Global.getInt(
                                mContext.getContentResolver(),
                                Settings.Global.NETWORK_SCORING_UI_ENABLED, 0) == 1;
            }
        };
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
            mScanner = null;
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
        mWorkHandler.sendEmptyMessage(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
    }

    /**
     * Start tracking wifi networks and scores.
     *
     * <p>Registers listeners and starts scanning for wifi networks. If this is not called
     * then forceUpdate() must be called to populate getAccessPoints().
     */
    public void startTracking() {
        registerScoreCache();

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.NETWORK_SCORING_UI_ENABLED),
                false /* notifyForDescendants */,
                mObserver);
        mObserver.onChange(false /* selfChange */); // Set the initial value for mScoringUiEnabled

        resumeScanning();
        if (!mRegistered) {
            mContext.registerReceiver(mReceiver, mFilter);
            // NetworkCallback objects cannot be reused. http://b/20701525 .
            mNetworkCallback = new WifiTrackerNetworkCallback();
            mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);
            mRegistered = true;
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

        if (DBG) {
            Log.d(TAG, "Requesting scores for Network Keys: " + keys);
        }
        mNetworkScoreManager.requestScores(keys.toArray(new NetworkKey[keys.size()]));
        mRequestedScores.addAll(keys);
    }

    /**
     * Stop tracking wifi networks and scores.
     *
     * <p>Unregisters all listeners and stops scanning for wifi networks. This should always
     * be called when done with a WifiTracker (if startTracking was called) to ensure
     * proper cleanup.
     */
    public void stopTracking() {
        if (mRegistered) {
            mWorkHandler.removeMessages(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
            mWorkHandler.removeMessages(WorkHandler.MSG_UPDATE_NETWORK_INFO);
            mWorkHandler.removeMessages(WorkHandler.MSG_UPDATE_NETWORK_SCORES);
            mContext.unregisterReceiver(mReceiver);
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mRegistered = false;
        }
        pauseScanning();

        unregisterAndClearScoreCache();

        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    private void unregisterAndClearScoreCache() {
        mNetworkScoreManager.unregisterNetworkScoreCache(NetworkKey.TYPE_WIFI, mScoreCache);
        mScoreCache.clearScores();

        // Clear the scores on the work handler to avoid concurrent modification exceptions
        mWorkHandler.post(() -> mRequestedScores.clear());
    }

    /**
     * Gets the current list of access points. Should be called from main thread, otherwise
     * expect inconsistencies
     */
    public List<AccessPoint> getAccessPoints() {
        return new ArrayList<>(mAccessPoints);
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
        for (AccessPoint accessPoint : getAccessPoints()) {
            pw.println("  " + accessPoint);
        }
    }

    private void handleResume() {
        mScanResultCache.clear();
        mSeenBssids.clear();
        mScanId = 0;
    }

    private Collection<ScanResult> fetchScanResults() {
        mScanId++;
        final List<ScanResult> newResults = mWifiManager.getScanResults();
        for (ScanResult newResult : newResults) {
            if (newResult.SSID == null || newResult.SSID.isEmpty()) {
                continue;
            }
            mScanResultCache.put(newResult.BSSID, newResult);
            mSeenBssids.put(newResult.BSSID, mScanId);
        }

        if (mScanId > NUM_SCANS_TO_CONFIRM_AP_LOSS) {
            if (DBG) Log.d(TAG, "------ Dumping SSIDs that were expired on this scan ------");
            Integer threshold = mScanId - NUM_SCANS_TO_CONFIRM_AP_LOSS;
            for (Iterator<Map.Entry<String, Integer>> it = mSeenBssids.entrySet().iterator();
                    it.hasNext(); /* nothing */) {
                Map.Entry<String, Integer> e = it.next();
                if (e.getValue() < threshold) {
                    ScanResult result = mScanResultCache.get(e.getKey());
                    if (DBG) Log.d(TAG, "Removing " + e.getKey() + ":(" + result.SSID + ")");
                    mScanResultCache.remove(e.getKey());
                    it.remove();
                }
            }
            if (DBG) Log.d(TAG, "---- Done Dumping SSIDs that were expired on this scan ----");
        }

        return mScanResultCache.values();
    }

    private WifiConfiguration getWifiConfigurationForNetworkId(int networkId) {
        final List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
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

    private void updateAccessPoints() {
        // Wait until main worker is done copying the states. This is done to prevent propagation
        // of accesspoint states while the update is in progress.
        long before = System.currentTimeMillis();
        mInternalAccessPointsWriteLock.block();
        if (DBG) {
            Log.d(TAG, "Acquired AP lock on WorkerHandler. Time to wait = "
                    + (System.currentTimeMillis() - before) + " ms.");
        }

        // Swap the current access points into a cached list.
        List<AccessPoint> cachedAccessPoints = new ArrayList<>(mInternalAccessPoints);
        ArrayList<AccessPoint> accessPoints = new ArrayList<>();

        // Clear out the configs so we don't think something is saved when it isn't.
        for (AccessPoint accessPoint : cachedAccessPoints) {
            accessPoint.clearConfig();
        }

        /* Lookup table to more quickly update AccessPoints by only considering objects with the
         * correct SSID.  Maps SSID -> List of AccessPoints with the given SSID.  */
        Multimap<String, AccessPoint> apMap = new Multimap<String, AccessPoint>();
        WifiConfiguration connectionConfig = null;
        if (mLastInfo != null) {
            connectionConfig = getWifiConfigurationForNetworkId(mLastInfo.getNetworkId());
        }

        final Collection<ScanResult> results = fetchScanResults();

        final List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
        if (configs != null) {
            mSavedNetworksExist = configs.size() != 0;
            for (WifiConfiguration config : configs) {
                if (config.selfAdded && config.numAssociation == 0) {
                    continue;
                }
                AccessPoint accessPoint = getCachedOrCreate(config, cachedAccessPoints);
                if (mLastInfo != null && mLastNetworkInfo != null) {
                    if (config.isPasspoint() == false) {
                        accessPoint.update(connectionConfig, mLastInfo, mLastNetworkInfo);
                    }
                }
                if (mIncludeSaved) {
                    if (!config.isPasspoint() || mIncludePasspoints) {
                        // If saved network not present in scan result then set its Rssi to MAX_VALUE
                        boolean apFound = false;
                        for (ScanResult result : results) {
                            if (result.SSID.equals(accessPoint.getSsidStr())) {
                                apFound = true;
                                break;
                            }
                        }
                        if (!apFound) {
                            accessPoint.setRssi(Integer.MAX_VALUE);
                        }
                        accessPoints.add(accessPoint);
                    }

                    if (config.isPasspoint() == false) {
                        apMap.put(accessPoint.getSsidStr(), accessPoint);
                    }
                } else {
                    // If we aren't using saved networks, drop them into the cache so that
                    // we have access to their saved info.
                    cachedAccessPoints.add(accessPoint);
                }
            }
        }

        final List<NetworkKey> scoresToRequest = new ArrayList<>();
        if (results != null) {
            for (ScanResult result : results) {
                // Ignore hidden and ad-hoc networks.
                if (result.SSID == null || result.SSID.length() == 0 ||
                        result.capabilities.contains("[IBSS]")) {
                    continue;
                }

                NetworkKey key = NetworkKey.createFromScanResult(result);
                if (!mRequestedScores.contains(key)) {
                    scoresToRequest.add(key);
                }

                boolean found = false;
                for (AccessPoint accessPoint : apMap.getAll(result.SSID)) {
                    if (accessPoint.update(result)) {
                        found = true;
                        break;
                    }
                }
                if (!found && mIncludeScans) {
                    AccessPoint accessPoint = getCachedOrCreate(result, cachedAccessPoints);
                    if (mLastInfo != null && mLastNetworkInfo != null) {
                        accessPoint.update(connectionConfig, mLastInfo, mLastNetworkInfo);
                    }

                    if (result.isPasspointNetwork()) {
                        WifiConfiguration config = mWifiManager.getMatchingWifiConfig(result);
                        if (config != null) {
                            accessPoint.update(config);
                        }
                    }

                    if (mLastInfo != null && mLastInfo.getBSSID() != null
                            && mLastInfo.getBSSID().equals(result.BSSID)
                            && connectionConfig != null && connectionConfig.isPasspoint()) {
                    /* This network is connected via this passpoint config */
                    /* SSID match is not going to work for it; so update explicitly */
                        accessPoint.update(connectionConfig);
                    }

                    accessPoints.add(accessPoint);
                    apMap.put(accessPoint.getSsidStr(), accessPoint);
                }
            }
        }

        if (mNetworkScoringUiEnabled) {
            requestScoresForNetworkKeys(scoresToRequest);
            for (AccessPoint ap : accessPoints) {
                ap.updateScores(mScoreCache);
            }
        }

        // Pre-sort accessPoints to speed preference insertion
        Collections.sort(accessPoints);

        // Log accesspoints that were deleted
        if (DBG) {
            Log.d(TAG, "------ Dumping SSIDs that were not seen on this scan ------");
            for (AccessPoint prevAccessPoint : mInternalAccessPoints) {
                if (prevAccessPoint.getSsid() == null)
                    continue;
                String prevSsid = prevAccessPoint.getSsidStr();
                boolean found = false;
                for (AccessPoint newAccessPoint : accessPoints) {
                    if (newAccessPoint.getSsid() != null && newAccessPoint.getSsid()
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
        mMainHandler.scheduleAPCopyingAndCloseWriteLock();
    }

    @VisibleForTesting
    AccessPoint getCachedOrCreate(ScanResult result, List<AccessPoint> cache) {
        final int N = cache.size();
        for (int i = 0; i < N; i++) {
            if (cache.get(i).matches(result)) {
                AccessPoint ret = cache.remove(i);
                ret.update(result);
                return ret;
            }
        }
        final AccessPoint accessPoint = new AccessPoint(mContext, result);
        accessPoint.setListener(mAccessPointListenerAdapter);
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
        accessPoint.setListener(mAccessPointListenerAdapter);
        return accessPoint;
    }

    private void updateNetworkInfo(NetworkInfo networkInfo) {
        /* sticky broadcasts can call this when wifi is disabled */
        if (!mWifiManager.isWifiEnabled()) {
            mMainHandler.sendEmptyMessage(MainHandler.MSG_PAUSE_SCANNING);
            return;
        }

        if (networkInfo != null &&
                networkInfo.getDetailedState() == DetailedState.OBTAINING_IPADDR) {
            mMainHandler.sendEmptyMessage(MainHandler.MSG_PAUSE_SCANNING);
        } else {
            mMainHandler.sendEmptyMessage(MainHandler.MSG_RESUME_SCANNING);
        }

        if (networkInfo != null) {
            mLastNetworkInfo = networkInfo;
        }

        WifiConfiguration connectionConfig = null;
        mLastInfo = mWifiManager.getConnectionInfo();
        if (mLastInfo != null) {
            connectionConfig = getWifiConfigurationForNetworkId(mLastInfo.getNetworkId());
        }

        // Lock required to prevent accidental copying of AccessPoint states while the modification
        // is in progress. see #copyAndNotifyListeners
        long before = System.currentTimeMillis();
        mInternalAccessPointsWriteLock.block();
        if (DBG) {
            Log.d(TAG, "Acquired AP lock on WorkerHandler for updating NetworkInfo. Wait time = " +
                    (System.currentTimeMillis() - before) + "ms.");
        }

        boolean reorder = false;
        for (int i = mInternalAccessPoints.size() - 1; i >= 0; --i) {
            AccessPoint ap = mInternalAccessPoints.get(i);
            if (ap.update(connectionConfig, mLastInfo, mLastNetworkInfo)) {
                reorder = true;
            }
            if (mNetworkScoringUiEnabled && ap.updateScores(mScoreCache)) {
                reorder = true;
            }
        }
        if (reorder) {
            Collections.sort(mInternalAccessPoints);
            mMainHandler.scheduleAPCopyingAndCloseWriteLock();
        }
    }

    /**
     * Update all the internal access points rankingScores and badge.
     *
     * <p>Will trigger a resort and notify listeners of changes if applicable.
     */
    private void updateNetworkScores() {
        if (!mNetworkScoringUiEnabled) {
            return;
        }

        // Lock required to prevent accidental copying of AccessPoint states while the modification
        // is in progress. see #copyAndNotifyListeners
        long before = System.currentTimeMillis();
        mInternalAccessPointsWriteLock.block();
        if (DBG) {
            Log.d(TAG, "Acquired AP lock on WorkerHandler for inserting NetworkScores. Wait time = " +
                    (System.currentTimeMillis() - before) + "ms.");
        }

        boolean reorder = false;
        for (int i = 0; i < mInternalAccessPoints.size(); i++) {
            if (mInternalAccessPoints.get(i).updateScores(mScoreCache)) {
                reorder = true;
            }
        }
        if (reorder) {
            Collections.sort(mInternalAccessPoints);
            mMainHandler.scheduleAPCopyingAndCloseWriteLock();
        }
    }

    private void updateWifiState(int state) {
        mWorkHandler.obtainMessage(WorkHandler.MSG_UPDATE_WIFI_STATE, state, 0).sendToTarget();
    }

    public static List<AccessPoint> getCurrentAccessPoints(Context context, boolean includeSaved,
            boolean includeScans, boolean includePasspoints) {
        WifiTracker tracker = new WifiTracker(context,
                null, null, includeSaved, includeScans, includePasspoints);
        tracker.forceUpdate();
        tracker.copyAndNotifyListeners(false /*notifyListeners*/);
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
                mWorkHandler.sendEmptyMessage(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);
                mConnected.set(info.isConnected());

                mMainHandler.sendEmptyMessage(MainHandler.MSG_CONNECTED_CHANGED);

                mWorkHandler.sendEmptyMessage(WorkHandler.MSG_UPDATE_ACCESS_POINTS);
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

    private final class MainHandler extends Handler {
        private static final int MSG_CONNECTED_CHANGED = 0;
        private static final int MSG_WIFI_STATE_CHANGED = 1;
        private static final int MSG_ACCESS_POINT_CHANGED = 2;
        private static final int MSG_RESUME_SCANNING = 3;
        private static final int MSG_PAUSE_SCANNING = 4;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (mListener == null) {
                return;
            }
            switch (msg.what) {
                case MSG_CONNECTED_CHANGED:
                    mListener.onConnectedChanged();
                    break;
                case MSG_WIFI_STATE_CHANGED:
                    mListener.onWifiStateChanged(msg.arg1);
                    break;
                case MSG_ACCESS_POINT_CHANGED:
                    copyAndNotifyListeners(true /*notifyListeners*/);
                    mListener.onAccessPointsChanged();
                    break;
                case MSG_RESUME_SCANNING:
                    if (mScanner != null) {
                        mScanner.resume();
                    }
                    break;
                case MSG_PAUSE_SCANNING:
                    if (mScanner != null) {
                        mScanner.pause();
                    }
                    break;
            }
        }

        void scheduleAPCopyingAndCloseWriteLock() {
            //prevent worker thread from modifying mInternalAccessPoints
            mInternalAccessPointsWriteLock.close();
            sendEmptyMessage(MSG_ACCESS_POINT_CHANGED);
        }
    }

    private final class WorkHandler extends Handler {
        private static final int MSG_UPDATE_ACCESS_POINTS = 0;
        private static final int MSG_UPDATE_NETWORK_INFO = 1;
        private static final int MSG_RESUME = 2;
        private static final int MSG_UPDATE_WIFI_STATE = 3;
        private static final int MSG_UPDATE_NETWORK_SCORES = 4;

        public WorkHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_ACCESS_POINTS:
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
                    }
                    mMainHandler.obtainMessage(MainHandler.MSG_WIFI_STATE_CHANGED, msg.arg1, 0)
                            .sendToTarget();
                    break;
                case MSG_UPDATE_NETWORK_SCORES:
                    updateNetworkScores();
                    break;
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

        void forceScan() {
            removeMessages(MSG_SCAN);
            sendEmptyMessage(MSG_SCAN);
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

    /**
     * Helps capture notifications that were generated during AccessPoint modification. Used later
     * on by {@link #copyAndNotifyListeners(boolean)} to send notifications.
     */
    private static class AccessPointListenerAdapter implements AccessPoint.AccessPointListener {
        static final int AP_CHANGED = 1;
        static final int LEVEL_CHANGED = 2;

        final SparseIntArray mPendingNotifications = new SparseIntArray();

        @Override
        public void onAccessPointChanged(AccessPoint accessPoint) {
            int type = mPendingNotifications.get(accessPoint.mId);
            mPendingNotifications.put(accessPoint.mId, type | AP_CHANGED);
        }

        @Override
        public void onLevelChanged(AccessPoint accessPoint) {
            int type = mPendingNotifications.get(accessPoint.mId);
            mPendingNotifications.put(accessPoint.mId, type | LEVEL_CHANGED);
        }
    }

    /**
     * Responsible for copying access points from {@link #mInternalAccessPoints} and notifying
     * accesspoint listeners. Mutation of the accesspoints returned is done on the main thread.
     *
     * @param notifyListeners if true, accesspoint listeners are notified, otherwise notifications
     *                        dropped.
     */
    private void copyAndNotifyListeners(boolean notifyListeners) {
        // Need to watch out for memory allocations on main thread.
        SparseArray<AccessPoint> oldAccessPoints = new SparseArray<>();
        SparseIntArray notificationMap = null;
        List<AccessPoint> updatedAccessPoints = new ArrayList<>();

        for (AccessPoint accessPoint : mAccessPoints) {
            oldAccessPoints.put(accessPoint.mId, accessPoint);
        }

        //synchronize to prevent modification of "mInternalAccessPoints" by worker thread.
        long before = System.currentTimeMillis();
        try {
            if (DBG) {
                Log.d(TAG, "Starting to copy AP items on the MainHandler");
            }
            if (notifyListeners) {
                notificationMap = mAccessPointListenerAdapter.mPendingNotifications.clone();
            }

            mAccessPointListenerAdapter.mPendingNotifications.clear();
            for (AccessPoint internalAccessPoint : mInternalAccessPoints) {
                AccessPoint accessPoint = oldAccessPoints.get(internalAccessPoint.mId);
                if (accessPoint == null) {
                    accessPoint = new AccessPoint(mContext, internalAccessPoint);
                } else {
                    accessPoint.copyFrom(internalAccessPoint);
                }
                updatedAccessPoints.add(accessPoint);
            }
        } finally {
            mInternalAccessPointsWriteLock.open();
            if (DBG) {
                Log.d(TAG, "Opened AP Write lock on the MainHandler. Time to copy = " +
                        (System.currentTimeMillis() - before) + " ms.");
            }
        }

        mAccessPoints.clear();
        mAccessPoints.addAll(updatedAccessPoints);

        if (notificationMap != null && notificationMap.size() > 0) {
            for (AccessPoint accessPoint : updatedAccessPoints) {
                int notificationType = notificationMap.get(accessPoint.mId);
                AccessPoint.AccessPointListener listener = accessPoint.mAccessPointListener;
                if (notificationType == 0 || listener == null) {
                    continue;
                }

                if ((notificationType & AccessPointListenerAdapter.AP_CHANGED) != 0) {
                    listener.onAccessPointChanged(accessPoint);
                }

                if ((notificationType & AccessPointListenerAdapter.LEVEL_CHANGED) != 0) {
                    listener.onLevelChanged(accessPoint);
                }
            }
        }
    }
}
