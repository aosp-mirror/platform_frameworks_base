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
 * limitations under the License
 */

package com.android.server;

import android.Manifest.permission;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.net.INetworkRecommendationProvider;
import android.net.INetworkScoreCache;
import android.net.INetworkScoreService;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.ScoredNetwork;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.DumpUtils;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Backing service for {@link android.net.NetworkScoreManager}.
 * @hide
 */
public class NetworkScoreService extends INetworkScoreService.Stub {
    private static final String TAG = "NetworkScoreService";
    private static final boolean DBG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.VERBOSE);

    private final Context mContext;
    private final NetworkScorerAppManager mNetworkScorerAppManager;
    @GuardedBy("mScoreCaches")
    private final Map<Integer, RemoteCallbackList<INetworkScoreCache>> mScoreCaches;
    /** Lock used to update mPackageMonitor when scorer package changes occur. */
    private final Object mPackageMonitorLock = new Object();
    private final Object mServiceConnectionLock = new Object();
    private final Handler mHandler;
    private final DispatchingContentObserver mRecommendationSettingsObserver;
    private final ContentObserver mUseOpenWifiPackageObserver;
    private final Function<NetworkScorerAppData, ScoringServiceConnection> mServiceConnProducer;

    @GuardedBy("mPackageMonitorLock")
    private NetworkScorerPackageMonitor mPackageMonitor;
    @GuardedBy("mServiceConnectionLock")
    private ScoringServiceConnection mServiceConnection;

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (DBG) Log.d(TAG, "Received " + action + " for userId " + userId);
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                onUserUnlocked(userId);
            }
        }
    };

    private BroadcastReceiver mLocationModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (LocationManager.MODE_CHANGED_ACTION.equals(action)) {
                refreshBinding();
            }
        }
    };

    public static final class Lifecycle extends SystemService {
        private final NetworkScoreService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new NetworkScoreService(context);
        }

        @Override
        public void onStart() {
            Log.i(TAG, "Registering " + Context.NETWORK_SCORE_SERVICE);
            publishBinderService(Context.NETWORK_SCORE_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mService.systemReady();
            } else if (phase == PHASE_BOOT_COMPLETED) {
                mService.systemRunning();
            }
        }
    }

    /**
     * Clears scores when the active scorer package is no longer valid and
     * manages the service connection.
     */
    private class NetworkScorerPackageMonitor extends PackageMonitor {
        final String mPackageToWatch;

        private NetworkScorerPackageMonitor(String packageToWatch) {
            mPackageToWatch = packageToWatch;
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            evaluateBinding(packageName, true /* forceUnbind */);
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            evaluateBinding(packageName, true /* forceUnbind */);
        }

        @Override
        public void onPackageModified(String packageName) {
            evaluateBinding(packageName, false /* forceUnbind */);
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            if (doit) { // "doit" means the force stop happened instead of just being queried for.
                for (String packageName : packages) {
                    evaluateBinding(packageName, true /* forceUnbind */);
                }
            }
            return super.onHandleForceStop(intent, packages, uid, doit);
        }

        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            evaluateBinding(packageName, true /* forceUnbind */);
        }

        private void evaluateBinding(String changedPackageName, boolean forceUnbind) {
            if (!mPackageToWatch.equals(changedPackageName)) {
                // Early exit when we don't care about the package that has changed.
                return;
            }

            if (DBG) {
                Log.d(TAG, "Evaluating binding for: " + changedPackageName
                        + ", forceUnbind=" + forceUnbind);
            }

            final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
            if (activeScorer == null) {
                // Package change has invalidated a scorer, this will also unbind any service
                // connection.
                if (DBG) Log.d(TAG, "No active scorers available.");
                refreshBinding();
            } else { // The scoring service changed in some way.
                if (forceUnbind) {
                    unbindFromScoringServiceIfNeeded();
                }
                if (DBG) {
                    Log.d(TAG, "Binding to " + activeScorer.getRecommendationServiceComponent()
                            + " if needed.");
                }
                bindToScoringServiceIfNeeded(activeScorer);
            }
        }
    }

    /**
     * Dispatches observed content changes to a handler for further processing.
     */
    @VisibleForTesting
    public static class DispatchingContentObserver extends ContentObserver {
        final private Map<Uri, Integer> mUriEventMap;
        final private Context mContext;
        final private Handler mHandler;

        public DispatchingContentObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
            mHandler = handler;
            mUriEventMap = new ArrayMap<>();
        }

        void observe(Uri uri, int what) {
            mUriEventMap.put(uri, what);
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(uri, false /*notifyForDescendants*/, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (DBG) Log.d(TAG, String.format("onChange(%s, %s)", selfChange, uri));
            final Integer what = mUriEventMap.get(uri);
            if (what != null) {
                mHandler.obtainMessage(what).sendToTarget();
            } else {
                Log.w(TAG, "No matching event to send for URI = " + uri);
            }
        }
    }

    public NetworkScoreService(Context context) {
      this(context, new NetworkScorerAppManager(context),
              ScoringServiceConnection::new, Looper.myLooper());
    }

    @VisibleForTesting
    NetworkScoreService(Context context, NetworkScorerAppManager networkScoreAppManager,
            Function<NetworkScorerAppData, ScoringServiceConnection> serviceConnProducer,
            Looper looper) {
        mContext = context;
        mNetworkScorerAppManager = networkScoreAppManager;
        mScoreCaches = new ArrayMap<>();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        // TODO: Need to update when we support per-user scorers. http://b/23422763
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.SYSTEM, filter, null /* broadcastPermission*/,
                null /* scheduler */);
        mHandler = new ServiceHandler(looper);
        IntentFilter locationModeFilter = new IntentFilter(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiverAsUser(
                mLocationModeReceiver, UserHandle.SYSTEM, locationModeFilter,
                null /* broadcastPermission*/, mHandler);
        mRecommendationSettingsObserver = new DispatchingContentObserver(context, mHandler);
        mServiceConnProducer = serviceConnProducer;
        mUseOpenWifiPackageObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                Uri useOpenWifiPkgUri = Global.getUriFor(Global.USE_OPEN_WIFI_PACKAGE);
                if (useOpenWifiPkgUri.equals(uri)) {
                    String useOpenWifiPackage = Global.getString(mContext.getContentResolver(),
                            Global.USE_OPEN_WIFI_PACKAGE);
                    if (!TextUtils.isEmpty(useOpenWifiPackage)) {
                        LocalServices.getService(PermissionManagerServiceInternal.class)
                                .grantDefaultPermissionsToDefaultUseOpenWifiApp(useOpenWifiPackage,
                                        userId);
                    }
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Global.getUriFor(Global.USE_OPEN_WIFI_PACKAGE),
                false /*notifyForDescendants*/,
                mUseOpenWifiPackageObserver);
        // Set a callback for the package manager to query the use open wifi app.
        LocalServices.getService(PermissionManagerServiceInternal.class)
                .setUseOpenWifiAppPackagesProvider((userId) -> {
                    String useOpenWifiPackage = Global.getString(mContext.getContentResolver(),
                            Global.USE_OPEN_WIFI_PACKAGE);
                    if (!TextUtils.isEmpty(useOpenWifiPackage)) {
                        return new String[]{useOpenWifiPackage};
                    }
                    return null;
                });
    }

    /** Called when the system is ready to run third-party code but before it actually does so. */
    void systemReady() {
        if (DBG) Log.d(TAG, "systemReady");
        registerRecommendationSettingsObserver();
    }

    /** Called when the system is ready for us to start third-party code. */
    void systemRunning() {
        if (DBG) Log.d(TAG, "systemRunning");
    }

    @VisibleForTesting
    void onUserUnlocked(int userId) {
        if (DBG) Log.d(TAG, "onUserUnlocked(" + userId + ")");
        refreshBinding();
    }

    private void refreshBinding() {
        if (DBG) Log.d(TAG, "refreshBinding()");
        // Make sure the scorer is up-to-date
        mNetworkScorerAppManager.updateState();
        mNetworkScorerAppManager.migrateNetworkScorerAppSettingIfNeeded();
        registerPackageMonitorIfNeeded();
        bindToScoringServiceIfNeeded();
    }

    private void registerRecommendationSettingsObserver() {
        final Uri packageNameUri = Global.getUriFor(Global.NETWORK_RECOMMENDATIONS_PACKAGE);
        mRecommendationSettingsObserver.observe(packageNameUri,
                ServiceHandler.MSG_RECOMMENDATIONS_PACKAGE_CHANGED);

        final Uri settingUri = Global.getUriFor(Global.NETWORK_RECOMMENDATIONS_ENABLED);
        mRecommendationSettingsObserver.observe(settingUri,
                ServiceHandler.MSG_RECOMMENDATION_ENABLED_SETTING_CHANGED);
    }

    /**
     * Ensures the package manager is registered to monitor the current active scorer.
     * If a discrepancy is found any previous monitor will be cleaned up
     * and a new monitor will be created.
     *
     * This method is idempotent.
     */
    private void registerPackageMonitorIfNeeded() {
        if (DBG) Log.d(TAG, "registerPackageMonitorIfNeeded()");
        final NetworkScorerAppData appData = mNetworkScorerAppManager.getActiveScorer();
        synchronized (mPackageMonitorLock) {
            // Unregister the current monitor if needed.
            if (mPackageMonitor != null && (appData == null
                    || !appData.getRecommendationServicePackageName().equals(
                            mPackageMonitor.mPackageToWatch))) {
                if (DBG) {
                    Log.d(TAG, "Unregistering package monitor for "
                            + mPackageMonitor.mPackageToWatch);
                }
                mPackageMonitor.unregister();
                mPackageMonitor = null;
            }

            // Create and register the monitor if a scorer is active.
            if (appData != null && mPackageMonitor == null) {
                mPackageMonitor = new NetworkScorerPackageMonitor(
                        appData.getRecommendationServicePackageName());
                // TODO: Need to update when we support per-user scorers. http://b/23422763
                mPackageMonitor.register(mContext, null /* thread */, UserHandle.SYSTEM,
                        false /* externalStorage */);
                if (DBG) {
                    Log.d(TAG, "Registered package monitor for "
                            + mPackageMonitor.mPackageToWatch);
                }
            }
        }
    }

    private void bindToScoringServiceIfNeeded() {
        if (DBG) Log.d(TAG, "bindToScoringServiceIfNeeded");
        NetworkScorerAppData scorerData = mNetworkScorerAppManager.getActiveScorer();
        bindToScoringServiceIfNeeded(scorerData);
    }

    /**
     * Ensures the service connection is bound to the current active scorer.
     * If a discrepancy is found any previous connection will be cleaned up
     * and a new connection will be created.
     *
     * This method is idempotent.
     */
    private void bindToScoringServiceIfNeeded(NetworkScorerAppData appData) {
        if (DBG) Log.d(TAG, "bindToScoringServiceIfNeeded(" + appData + ")");
        if (appData != null) {
            synchronized (mServiceConnectionLock) {
                // If we're connected to a different component then drop it.
                if (mServiceConnection != null
                        && !mServiceConnection.getAppData().equals(appData)) {
                    unbindFromScoringServiceIfNeeded();
                }

                // If we're not connected at all then create a new connection.
                if (mServiceConnection == null) {
                    mServiceConnection = mServiceConnProducer.apply(appData);
                }

                // Make sure the connection is connected (idempotent)
                mServiceConnection.bind(mContext);
            }
        } else { // otherwise make sure it isn't bound.
            unbindFromScoringServiceIfNeeded();
        }
    }

    private void unbindFromScoringServiceIfNeeded() {
        if (DBG) Log.d(TAG, "unbindFromScoringServiceIfNeeded");
        synchronized (mServiceConnectionLock) {
            if (mServiceConnection != null) {
                mServiceConnection.unbind(mContext);
                if (DBG) Log.d(TAG, "Disconnected from: "
                        + mServiceConnection.getAppData().getRecommendationServiceComponent());
            }
            mServiceConnection = null;
        }
        clearInternal();
    }

    @Override
    public boolean updateScores(ScoredNetwork[] networks) {
        if (!isCallerActiveScorer(getCallingUid())) {
            throw new SecurityException("Caller with UID " + getCallingUid() +
                    " is not the active scorer.");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // Separate networks by type.
            Map<Integer, List<ScoredNetwork>> networksByType = new ArrayMap<>();
            for (ScoredNetwork network : networks) {
                List<ScoredNetwork> networkList = networksByType.get(network.networkKey.type);
                if (networkList == null) {
                    networkList = new ArrayList<>();
                    networksByType.put(network.networkKey.type, networkList);
                }
                networkList.add(network);
            }

            // Pass the scores of each type down to the appropriate network scorer.
            for (final Map.Entry<Integer, List<ScoredNetwork>> entry : networksByType.entrySet()) {
                final RemoteCallbackList<INetworkScoreCache> callbackList;
                final boolean isEmpty;
                synchronized (mScoreCaches) {
                    callbackList = mScoreCaches.get(entry.getKey());
                    isEmpty = callbackList == null
                            || callbackList.getRegisteredCallbackCount() == 0;
                }

                if (isEmpty) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "No scorer registered for type " + entry.getKey()
                                + ", discarding");
                    }
                    continue;
                }

                final BiConsumer<INetworkScoreCache, Object> consumer =
                        FilteringCacheUpdatingConsumer.create(mContext, entry.getValue(),
                                entry.getKey());
                sendCacheUpdateCallback(consumer, Collections.singleton(callbackList));
            }

            return true;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * A {@link BiConsumer} implementation that filters the given {@link ScoredNetwork}
     * list (if needed) before invoking {@link INetworkScoreCache#updateScores(List)} on the
     * accepted {@link INetworkScoreCache} implementation.
     */
    @VisibleForTesting
    static class FilteringCacheUpdatingConsumer
            implements BiConsumer<INetworkScoreCache, Object> {
        private final Context mContext;
        private final List<ScoredNetwork> mScoredNetworkList;
        private final int mNetworkType;
        // TODO: 1/23/17 - Consider a Map if we implement more filters.
        // These are created on-demand to defer the construction cost until
        // an instance is actually needed.
        private UnaryOperator<List<ScoredNetwork>> mCurrentNetworkFilter;
        private UnaryOperator<List<ScoredNetwork>> mScanResultsFilter;

        static FilteringCacheUpdatingConsumer create(Context context,
                List<ScoredNetwork> scoredNetworkList, int networkType) {
            return new FilteringCacheUpdatingConsumer(context, scoredNetworkList, networkType,
                    null, null);
        }

        @VisibleForTesting
        FilteringCacheUpdatingConsumer(Context context,
                List<ScoredNetwork> scoredNetworkList, int networkType,
                UnaryOperator<List<ScoredNetwork>> currentNetworkFilter,
                UnaryOperator<List<ScoredNetwork>> scanResultsFilter) {
            mContext = context;
            mScoredNetworkList = scoredNetworkList;
            mNetworkType = networkType;
            mCurrentNetworkFilter = currentNetworkFilter;
            mScanResultsFilter = scanResultsFilter;
        }

        @Override
        public void accept(INetworkScoreCache networkScoreCache, Object cookie) {
            int filterType = NetworkScoreManager.SCORE_FILTER_NONE;
            if (cookie instanceof Integer) {
                filterType = (Integer) cookie;
            }

            try {
                final List<ScoredNetwork> filteredNetworkList =
                        filterScores(mScoredNetworkList, filterType);
                if (!filteredNetworkList.isEmpty()) {
                    networkScoreCache.updateScores(filteredNetworkList);
                }
            } catch (RemoteException e) {
                if (VERBOSE) {
                    Log.v(TAG, "Unable to update scores of type " + mNetworkType, e);
                }
            }
        }

        /**
         * Applies the appropriate filter and returns the filtered results.
         */
        private List<ScoredNetwork> filterScores(List<ScoredNetwork> scoredNetworkList,
                int filterType) {
            switch (filterType) {
                case NetworkScoreManager.SCORE_FILTER_NONE:
                    return scoredNetworkList;

                case NetworkScoreManager.SCORE_FILTER_CURRENT_NETWORK:
                    if (mCurrentNetworkFilter == null) {
                        mCurrentNetworkFilter =
                                new CurrentNetworkScoreCacheFilter(new WifiInfoSupplier(mContext));
                    }
                    return mCurrentNetworkFilter.apply(scoredNetworkList);

                case NetworkScoreManager.SCORE_FILTER_SCAN_RESULTS:
                    if (mScanResultsFilter == null) {
                        mScanResultsFilter = new ScanResultsScoreCacheFilter(
                                new ScanResultsSupplier(mContext));
                    }
                    return mScanResultsFilter.apply(scoredNetworkList);

                default:
                    Log.w(TAG, "Unknown filter type: " + filterType);
                    return scoredNetworkList;
            }
        }
    }

    /**
     * Helper class that improves the testability of the cache filter Functions.
     */
    private static class WifiInfoSupplier implements Supplier<WifiInfo> {
        private final Context mContext;

        WifiInfoSupplier(Context context) {
            mContext = context;
        }

        @Override
        public WifiInfo get() {
            WifiManager wifiManager = mContext.getSystemService(WifiManager.class);
            if (wifiManager != null) {
                return wifiManager.getConnectionInfo();
            }
            Log.w(TAG, "WifiManager is null, failed to return the WifiInfo.");
            return null;
        }
    }

    /**
     * Helper class that improves the testability of the cache filter Functions.
     */
    private static class ScanResultsSupplier implements Supplier<List<ScanResult>> {
        private final Context mContext;

        ScanResultsSupplier(Context context) {
            mContext = context;
        }

        @Override
        public List<ScanResult> get() {
            WifiScanner wifiScanner = mContext.getSystemService(WifiScanner.class);
            if (wifiScanner != null) {
                return wifiScanner.getSingleScanResults();
            }
            Log.w(TAG, "WifiScanner is null, failed to return scan results.");
            return Collections.emptyList();
        }
    }

    /**
     * Filters the given set of {@link ScoredNetwork}s and returns a new List containing only the
     * {@link ScoredNetwork} associated with the current network. If no network is connected the
     * returned list will be empty.
     * <p>
     * Note: this filter performs some internal caching for consistency and performance. The
     *       current network is determined at construction time and never changed. Also, the
     *       last filtered list is saved so if the same input is provided multiple times in a row
     *       the computation is only done once.
     */
    @VisibleForTesting
    static class CurrentNetworkScoreCacheFilter implements UnaryOperator<List<ScoredNetwork>> {
        private final NetworkKey mCurrentNetwork;

        CurrentNetworkScoreCacheFilter(Supplier<WifiInfo> wifiInfoSupplier) {
            mCurrentNetwork = NetworkKey.createFromWifiInfo(wifiInfoSupplier.get());
        }

        @Override
        public List<ScoredNetwork> apply(List<ScoredNetwork> scoredNetworks) {
            if (mCurrentNetwork == null || scoredNetworks.isEmpty()) {
                return Collections.emptyList();
            }

            for (int i = 0; i < scoredNetworks.size(); i++) {
                final ScoredNetwork scoredNetwork = scoredNetworks.get(i);
                if (scoredNetwork.networkKey.equals(mCurrentNetwork)) {
                    return Collections.singletonList(scoredNetwork);
                }
            }

            return Collections.emptyList();
        }
    }

    /**
     * Filters the given set of {@link ScoredNetwork}s and returns a new List containing only the
     * {@link ScoredNetwork} associated with the current set of {@link ScanResult}s.
     * If there are no {@link ScanResult}s the returned list will be empty.
     * <p>
     * Note: this filter performs some internal caching for consistency and performance. The
     *       current set of ScanResults is determined at construction time and never changed.
     *       Also, the last filtered list is saved so if the same input is provided multiple
     *       times in a row the computation is only done once.
     */
    @VisibleForTesting
    static class ScanResultsScoreCacheFilter implements UnaryOperator<List<ScoredNetwork>> {
        private final Set<NetworkKey> mScanResultKeys;

        ScanResultsScoreCacheFilter(Supplier<List<ScanResult>> resultsSupplier) {
            List<ScanResult> scanResults = resultsSupplier.get();
            final int size = scanResults.size();
            mScanResultKeys = new ArraySet<>(size);
            for (int i = 0; i < size; i++) {
                ScanResult scanResult = scanResults.get(i);
                NetworkKey key = NetworkKey.createFromScanResult(scanResult);
                if (key != null) {
                    mScanResultKeys.add(key);
                }
            }
        }

        @Override
        public List<ScoredNetwork> apply(List<ScoredNetwork> scoredNetworks) {
            if (mScanResultKeys.isEmpty() || scoredNetworks.isEmpty()) {
                return Collections.emptyList();
            }

            List<ScoredNetwork> filteredScores = new ArrayList<>();
            for (int i = 0; i < scoredNetworks.size(); i++) {
                final ScoredNetwork scoredNetwork = scoredNetworks.get(i);
                if (mScanResultKeys.contains(scoredNetwork.networkKey)) {
                    filteredScores.add(scoredNetwork);
                }
            }

            return filteredScores;
        }
    }

    @Override
    public boolean clearScores() {
        // Only the active scorer or the system should be allowed to flush all scores.
        enforceSystemOrIsActiveScorer(getCallingUid());
        final long token = Binder.clearCallingIdentity();
        try {
            clearInternal();
            return true;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean setActiveScorer(String packageName) {
        enforceSystemOrHasScoreNetworks();
        return mNetworkScorerAppManager.setActiveScorer(packageName);
    }

    /**
     * Determine whether the application with the given UID is the enabled scorer.
     *
     * @param callingUid the UID to check
     * @return true if the provided UID is the active scorer, false otherwise.
     */
    @Override
    public boolean isCallerActiveScorer(int callingUid) {
        synchronized (mServiceConnectionLock) {
            return mServiceConnection != null
                    && mServiceConnection.getAppData().packageUid == callingUid;
        }
    }

    private void enforceSystemOnly() throws SecurityException {
        // REQUEST_NETWORK_SCORES is a signature only permission.
        mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES,
                "Caller must be granted REQUEST_NETWORK_SCORES.");
    }

    private void enforceSystemOrHasScoreNetworks() throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES)
                != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(permission.SCORE_NETWORKS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Caller is neither the system process or a network scorer.");
        }
    }

    private void enforceSystemOrIsActiveScorer(int callingUid) throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES)
                != PackageManager.PERMISSION_GRANTED
                && !isCallerActiveScorer(callingUid)) {
            throw new SecurityException(
                    "Caller is neither the system process or the active network scorer.");
        }
    }

    /**
     * Obtain the package name of the current active network scorer.
     *
     * @return the full package name of the current active scorer, or null if there is no active
     *         scorer.
     */
    @Override
    public String getActiveScorerPackage() {
        enforceSystemOrHasScoreNetworks();
        NetworkScorerAppData appData = mNetworkScorerAppManager.getActiveScorer();
        if (appData == null) {
          return null;
        }
        return appData.getRecommendationServicePackageName();
    }

    /**
     * Returns metadata about the active scorer or <code>null</code> if there is no active scorer.
     */
    @Override
    public NetworkScorerAppData getActiveScorer() {
        // Only the system can access this data.
        enforceSystemOnly();
        return mNetworkScorerAppManager.getActiveScorer();
    }

    /**
     * Returns the list of available scorer apps. The list will be empty if there are
     * no valid scorers.
     */
    @Override
    public List<NetworkScorerAppData> getAllValidScorers() {
        // Only the system can access this data.
        enforceSystemOnly();
        return mNetworkScorerAppManager.getAllValidScorers();
    }

    @Override
    public void disableScoring() {
        // Only the active scorer or the system should be allowed to disable scoring.
        enforceSystemOrIsActiveScorer(getCallingUid());
        // no-op for now but we could write to the setting if needed.
    }

    /** Clear scores. Callers are responsible for checking permissions as appropriate. */
    private void clearInternal() {
        sendCacheUpdateCallback(new BiConsumer<INetworkScoreCache, Object>() {
            @Override
            public void accept(INetworkScoreCache networkScoreCache, Object cookie) {
                try {
                    networkScoreCache.clearScores();
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Unable to clear scores", e);
                    }
                }
            }
        }, getScoreCacheLists());
    }

    @Override
    public void registerNetworkScoreCache(int networkType,
                                          INetworkScoreCache scoreCache,
                                          int filterType) {
        enforceSystemOnly();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mScoreCaches) {
                RemoteCallbackList<INetworkScoreCache> callbackList = mScoreCaches.get(networkType);
                if (callbackList == null) {
                    callbackList = new RemoteCallbackList<>();
                    mScoreCaches.put(networkType, callbackList);
                }
                if (!callbackList.register(scoreCache, filterType)) {
                    if (callbackList.getRegisteredCallbackCount() == 0) {
                        mScoreCaches.remove(networkType);
                    }
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Unable to register NetworkScoreCache for type " + networkType);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void unregisterNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        enforceSystemOnly();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mScoreCaches) {
                RemoteCallbackList<INetworkScoreCache> callbackList = mScoreCaches.get(networkType);
                if (callbackList == null || !callbackList.unregister(scoreCache)) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Unable to unregister NetworkScoreCache for type "
                                + networkType);
                    }
                } else if (callbackList.getRegisteredCallbackCount() == 0) {
                    mScoreCaches.remove(networkType);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean requestScores(NetworkKey[] networks) {
        enforceSystemOnly();
        final long token = Binder.clearCallingIdentity();
        try {
            final INetworkRecommendationProvider provider = getRecommendationProvider();
            if (provider != null) {
                try {
                    provider.requestScores(networks);
                    // TODO: 12/15/16 - Consider pushing null scores into the cache to
                    // prevent repeated requests for the same scores.
                    return true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to request scores.", e);
                    // TODO: 12/15/16 - Keep track of failures.
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter writer, final String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        final long token = Binder.clearCallingIdentity();
        try {
            NetworkScorerAppData currentScorer = mNetworkScorerAppManager.getActiveScorer();
            if (currentScorer == null) {
                writer.println("Scoring is disabled.");
                return;
            }
            writer.println("Current scorer: " + currentScorer);

            synchronized (mServiceConnectionLock) {
                if (mServiceConnection != null) {
                    mServiceConnection.dump(fd, writer, args);
                } else {
                    writer.println("ScoringServiceConnection: null");
                }
            }
            writer.flush();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns a {@link Collection} of all {@link RemoteCallbackList}s that are currently active.
     *
     * <p>May be used to perform an action on all score caches without potentially strange behavior
     * if a new scorer is registered during that action's execution.
     */
    private Collection<RemoteCallbackList<INetworkScoreCache>> getScoreCacheLists() {
        synchronized (mScoreCaches) {
            return new ArrayList<>(mScoreCaches.values());
        }
    }

    private void sendCacheUpdateCallback(BiConsumer<INetworkScoreCache, Object> consumer,
            Collection<RemoteCallbackList<INetworkScoreCache>> remoteCallbackLists) {
        for (RemoteCallbackList<INetworkScoreCache> callbackList : remoteCallbackLists) {
            synchronized (callbackList) { // Ensure only one active broadcast per RemoteCallbackList
                final int count = callbackList.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        consumer.accept(callbackList.getBroadcastItem(i),
                                callbackList.getBroadcastCookie(i));
                    }
                } finally {
                    callbackList.finishBroadcast();
                }
            }
        }
    }

    @Nullable
    private INetworkRecommendationProvider getRecommendationProvider() {
        synchronized (mServiceConnectionLock) {
            if (mServiceConnection != null) {
                return mServiceConnection.getRecommendationProvider();
            }
        }
        return null;
    }

    // The class and methods need to be public for Mockito to work.
    @VisibleForTesting
    public static class ScoringServiceConnection implements ServiceConnection {
        private final NetworkScorerAppData mAppData;
        private volatile boolean mBound = false;
        private volatile boolean mConnected = false;
        private volatile INetworkRecommendationProvider mRecommendationProvider;

        ScoringServiceConnection(NetworkScorerAppData appData) {
            mAppData = appData;
        }

        @VisibleForTesting
        public void bind(Context context) {
            if (!mBound) {
                Intent service = new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS);
                service.setComponent(mAppData.getRecommendationServiceComponent());
                mBound = context.bindServiceAsUser(service, this,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        UserHandle.SYSTEM);
                if (!mBound) {
                    Log.w(TAG, "Bind call failed for " + service);
                    context.unbindService(this);
                } else {
                    if (DBG) Log.d(TAG, "ScoringServiceConnection bound.");
                }
            }
        }

        @VisibleForTesting
        public void unbind(Context context) {
            try {
                if (mBound) {
                    mBound = false;
                    context.unbindService(this);
                    if (DBG) Log.d(TAG, "ScoringServiceConnection unbound.");
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Unbind failed.", e);
            }

            mConnected = false;
            mRecommendationProvider = null;
        }

        @VisibleForTesting
        public NetworkScorerAppData getAppData() {
            return mAppData;
        }

        @VisibleForTesting
        public INetworkRecommendationProvider getRecommendationProvider() {
            return mRecommendationProvider;
        }

        @VisibleForTesting
        public String getPackageName() {
            return mAppData.getRecommendationServiceComponent().getPackageName();
        }

        @VisibleForTesting
        public boolean isAlive() {
            return mBound && mConnected;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DBG) Log.d(TAG, "ScoringServiceConnection: " + name.flattenToString());
            mConnected = true;
            mRecommendationProvider = INetworkRecommendationProvider.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) {
                Log.d(TAG, "ScoringServiceConnection, disconnected: " + name.flattenToString());
            }
            mConnected = false;
            mRecommendationProvider = null;
        }

        public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            writer.println("ScoringServiceConnection: "
                    + mAppData.getRecommendationServiceComponent()
                    + ", bound: " + mBound
                    + ", connected: " + mConnected);
        }
    }

    @VisibleForTesting
    public final class ServiceHandler extends Handler {
        public static final int MSG_RECOMMENDATIONS_PACKAGE_CHANGED = 1;
        public static final int MSG_RECOMMENDATION_ENABLED_SETTING_CHANGED = 2;

        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int what = msg.what;
            switch (what) {
                case MSG_RECOMMENDATIONS_PACKAGE_CHANGED:
                case MSG_RECOMMENDATION_ENABLED_SETTING_CHANGED:
                    refreshBinding();
                    break;

                default:
                    Log.w(TAG,"Unknown message: " + what);
            }
        }
    }
}
