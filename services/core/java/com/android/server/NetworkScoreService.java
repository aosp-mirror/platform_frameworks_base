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

import static android.net.NetworkRecommendationProvider.EXTRA_RECOMMENDATION_RESULT;
import static android.net.NetworkRecommendationProvider.EXTRA_SEQUENCE;

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
import android.net.INetworkRecommendationProvider;
import android.net.INetworkScoreCache;
import android.net.INetworkScoreService;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppManager;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.ScoredNetwork;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.TimedRemoteCaller;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.TransferPipe;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
    private final AtomicReference<RequestRecommendationCaller> mReqRecommendationCallerRef;
    @GuardedBy("mScoreCaches")
    private final Map<Integer, RemoteCallbackList<INetworkScoreCache>> mScoreCaches;
    /** Lock used to update mPackageMonitor when scorer package changes occur. */
    private final Object mPackageMonitorLock = new Object();
    private final Object mServiceConnectionLock = new Object();
    private final Handler mHandler;
    private final DispatchingContentObserver mContentObserver;

    @GuardedBy("mPackageMonitorLock")
    private NetworkScorerPackageMonitor mPackageMonitor;
    @GuardedBy("mServiceConnectionLock")
    private ScoringServiceConnection mServiceConnection;
    private volatile long mRecommendationRequestTimeoutMs;

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

    /**
     * Clears scores when the active scorer package is no longer valid and
     * manages the service connection.
     */
    private class NetworkScorerPackageMonitor extends PackageMonitor {
        final List<String> mPackagesToWatch;

        private NetworkScorerPackageMonitor(List<String> packagesToWatch) {
            mPackagesToWatch = packagesToWatch;
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

        private void evaluateBinding(String scorerPackageName, boolean forceUnbind) {
            if (!mPackagesToWatch.contains(scorerPackageName)) {
                // Early exit when we don't care about the package that has changed.
                return;
            }

            if (DBG) {
                Log.d(TAG, "Evaluating binding for: " + scorerPackageName
                        + ", forceUnbind=" + forceUnbind);
            }
            final NetworkScorerAppData activeScorer = mNetworkScorerAppManager.getActiveScorer();
            if (activeScorer == null) {
                // Package change has invalidated a scorer, this will also unbind any service
                // connection.
                if (DBG) Log.d(TAG, "No active scorers available.");
                unbindFromScoringServiceIfNeeded();
            } else if (activeScorer.getRecommendationServicePackageName().equals(scorerPackageName))
            {
                // The active scoring service changed in some way.
                if (DBG) {
                    Log.d(TAG, "Possible change to the active scorer: "
                            + activeScorer.getRecommendationServicePackageName());
                }
                if (forceUnbind) {
                    unbindFromScoringServiceIfNeeded();
                }
                bindToScoringServiceIfNeeded(activeScorer);
            } else {
                // One of the scoring apps on the device has changed and we may no longer be
                // bound to the correct scoring app. The logic in bindToScoringServiceIfNeeded()
                // will sort that out to leave us bound to the most recent active scorer.
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
      this(context, new NetworkScorerAppManager(context), Looper.myLooper());
    }

    @VisibleForTesting
    NetworkScoreService(Context context, NetworkScorerAppManager networkScoreAppManager,
            Looper looper) {
        mContext = context;
        mNetworkScorerAppManager = networkScoreAppManager;
        mScoreCaches = new ArrayMap<>();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        // TODO: Need to update when we support per-user scorers. http://b/23422763
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.SYSTEM, filter, null /* broadcastPermission*/,
                null /* scheduler */);
        mReqRecommendationCallerRef = new AtomicReference<>(
                new RequestRecommendationCaller(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS));
        mRecommendationRequestTimeoutMs = TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS;
        mHandler = new ServiceHandler(looper);
        mContentObserver = new DispatchingContentObserver(context, mHandler);
    }

    /** Called when the system is ready to run third-party code but before it actually does so. */
    void systemReady() {
        if (DBG) Log.d(TAG, "systemReady");
        registerPackageMonitorIfNeeded();
        registerRecommendationSettingsObserver();
        refreshRecommendationRequestTimeoutMs();
    }

    /** Called when the system is ready for us to start third-party code. */
    void systemRunning() {
        if (DBG) Log.d(TAG, "systemRunning");
        bindToScoringServiceIfNeeded();
    }

    private void onUserUnlocked(int userId) {
        registerPackageMonitorIfNeeded();
        bindToScoringServiceIfNeeded();
    }

    private void registerRecommendationSettingsObserver() {
        final List<String> providerPackages =
            mNetworkScorerAppManager.getPotentialRecommendationProviderPackages();
        if (!providerPackages.isEmpty()) {
            final Uri enabledUri = Global.getUriFor(Global.NETWORK_RECOMMENDATIONS_ENABLED);
            mContentObserver.observe(enabledUri,
                    ServiceHandler.MSG_RECOMMENDATIONS_ENABLED_CHANGED);
        }

        final Uri timeoutUri = Global.getUriFor(Global.NETWORK_RECOMMENDATION_REQUEST_TIMEOUT_MS);
        mContentObserver.observe(timeoutUri,
                ServiceHandler.MSG_RECOMMENDATION_REQUEST_TIMEOUT_CHANGED);
    }

    private void registerPackageMonitorIfNeeded() {
        if (DBG) Log.d(TAG, "registerPackageMonitorIfNeeded");
        final List<String> providerPackages =
            mNetworkScorerAppManager.getPotentialRecommendationProviderPackages();
        synchronized (mPackageMonitorLock) {
            // Unregister the current monitor if needed.
            if (mPackageMonitor != null) {
                if (DBG) {
                    Log.d(TAG, "Unregistering package monitor for "
                            + mPackageMonitor.mPackagesToWatch);
                }
                mPackageMonitor.unregister();
                mPackageMonitor = null;
            }

            // Create and register the monitor if there are packages that could be providers.
            if (!providerPackages.isEmpty()) {
                mPackageMonitor = new NetworkScorerPackageMonitor(providerPackages);
                // TODO: Need to update when we support per-user scorers. http://b/23422763
                mPackageMonitor.register(mContext, null /* thread */, UserHandle.SYSTEM,
                        false /* externalStorage */);
                if (DBG) {
                    Log.d(TAG, "Registered package monitor for "
                            + mPackageMonitor.mPackagesToWatch);
                }
            }
        }
    }

    private void bindToScoringServiceIfNeeded() {
        if (DBG) Log.d(TAG, "bindToScoringServiceIfNeeded");
        NetworkScorerAppData scorerData = mNetworkScorerAppManager.getActiveScorer();
        bindToScoringServiceIfNeeded(scorerData);
    }

    private void bindToScoringServiceIfNeeded(NetworkScorerAppData appData) {
        if (DBG) Log.d(TAG, "bindToScoringServiceIfNeeded(" + appData + ")");
        if (appData != null) {
            synchronized (mServiceConnectionLock) {
                // If we're connected to a different component then drop it.
                if (mServiceConnection != null
                        && !mServiceConnection.mAppData.equals(appData)) {
                    unbindFromScoringServiceIfNeeded();
                }

                // If we're not connected at all then create a new connection.
                if (mServiceConnection == null) {
                    mServiceConnection = new ScoringServiceConnection(appData);
                }

                // Make sure the connection is connected (idempotent)
                mServiceConnection.connect(mContext);
            }
        } else { // otherwise make sure it isn't bound.
            unbindFromScoringServiceIfNeeded();
        }
    }

    private void unbindFromScoringServiceIfNeeded() {
        if (DBG) Log.d(TAG, "unbindFromScoringServiceIfNeeded");
        synchronized (mServiceConnectionLock) {
            if (mServiceConnection != null) {
                mServiceConnection.disconnect(mContext);
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
            int filterType = NetworkScoreManager.CACHE_FILTER_NONE;
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
                case NetworkScoreManager.CACHE_FILTER_NONE:
                    return scoredNetworkList;

                case NetworkScoreManager.CACHE_FILTER_CURRENT_NETWORK:
                    if (mCurrentNetworkFilter == null) {
                        mCurrentNetworkFilter =
                                new CurrentNetworkScoreCacheFilter(new WifiInfoSupplier(mContext));
                    }
                    return mCurrentNetworkFilter.apply(scoredNetworkList);

                case NetworkScoreManager.CACHE_FILTER_SCAN_RESULTS:
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
                mScanResultKeys.add(NetworkKey.createFromScanResult(scanResult));
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

    private boolean callerCanRequestScores() {
        // REQUEST_NETWORK_SCORES is a signature only permission.
        return mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES) ==
                 PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean clearScores() {
        // Only the active scorer or the system should be allowed to flush all scores.
        if (isCallerActiveScorer(getCallingUid()) || callerCanRequestScores()) {
            final long token = Binder.clearCallingIdentity();
            try {
                clearInternal();
                return true;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else {
            throw new SecurityException(
                    "Caller is neither the active scorer nor the scorer manager.");
        }
    }

    @Override
    public boolean setActiveScorer(String packageName) {
        // TODO: For now, since SCORE_NETWORKS requires an app to be privileged, we allow such apps
        // to directly set the scorer app rather than having to use the consent dialog. The
        // assumption is that anyone bundling a scorer app with the system is trusted by the OEM to
        // do the right thing and not enable this feature without explaining it to the user.
        // In the future, should this API be opened to 3p apps, we will need to lock this down and
        // figure out another way to streamline the UX.

        mContext.enforceCallingOrSelfPermission(permission.SCORE_NETWORKS, TAG);

        // Scorers (recommendation providers) are selected and no longer set.
        return false;
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
                    && mServiceConnection.mAppData.packageUid == callingUid;
        }
    }

    private boolean isCallerSystemProcess(int callingUid) {
        return callingUid == Process.SYSTEM_UID;
    }

    /**
     * Obtain the package name of the current active network scorer.
     *
     * @return the full package name of the current active scorer, or null if there is no active
     *         scorer.
     */
    @Override
    public String getActiveScorerPackage() {
        synchronized (mServiceConnectionLock) {
            if (mServiceConnection != null) {
                return mServiceConnection.getPackageName();
            }
        }
        return null;
    }


    /**
     * Returns metadata about the active scorer or <code>null</code> if there is no active scorer.
     */
    @Override
    public NetworkScorerAppData getActiveScorer() {
        // Only the system can access this data.
        if (isCallerSystemProcess(getCallingUid()) || callerCanRequestScores()) {
            synchronized (mServiceConnectionLock) {
                if (mServiceConnection != null) {
                    return mServiceConnection.mAppData;
                }
            }
        } else {
            throw new SecurityException(
                    "Caller is neither the system process nor a score requester.");
        }

        return null;
    }

    @Override
    public void disableScoring() {
        // Only the active scorer or the system should be allowed to disable scoring.
        if (isCallerActiveScorer(getCallingUid()) || callerCanRequestScores()) {
            // no-op for now but we could write to the setting if needed.
        } else {
            throw new SecurityException(
                    "Caller is neither the active scorer nor the scorer manager.");
        }
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
        mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES, TAG);
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
        mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES, TAG);
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
    public RecommendationResult requestRecommendation(RecommendationRequest request) {
        mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES, TAG);
        throwIfCalledOnMainThread();
        final long token = Binder.clearCallingIdentity();
        try {
            final INetworkRecommendationProvider provider = getRecommendationProvider();
            if (provider != null) {
                try {
                    final RequestRecommendationCaller caller = mReqRecommendationCallerRef.get();
                    return caller.getRecommendationResult(provider, request);
                } catch (RemoteException | TimeoutException e) {
                    Log.w(TAG, "Failed to request a recommendation.", e);
                    // TODO: 12/15/16 - Keep track of failures.
                }
            }

            if (DBG) {
                Log.d(TAG, "Returning the default network recommendation.");
            }

            if (request != null && request.getDefaultWifiConfig() != null) {
                return RecommendationResult.createConnectRecommendation(
                        request.getDefaultWifiConfig());
            }
            return RecommendationResult.createDoNotConnectRecommendation();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Request a recommendation for the best network to connect to
     * taking into account the inputs from the {@link RecommendationRequest}.
     *
     * @param request a {@link RecommendationRequest} instance containing the details of the request
     * @param remoteCallback a {@link IRemoteCallback} instance to invoke when the recommendation
     *                       is available.
     * @throws SecurityException if the caller is not the system
     */
    @Override
    public void requestRecommendationAsync(RecommendationRequest request,
            RemoteCallback remoteCallback) {
        mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES, TAG);

        final OneTimeCallback oneTimeCallback = new OneTimeCallback(remoteCallback);
        final Pair<RecommendationRequest, OneTimeCallback> pair =
                Pair.create(request, oneTimeCallback);
        final Message timeoutMsg = mHandler.obtainMessage(
                ServiceHandler.MSG_RECOMMENDATION_REQUEST_TIMEOUT, pair);
        final INetworkRecommendationProvider provider = getRecommendationProvider();
        final long token = Binder.clearCallingIdentity();
        try {
            if (provider != null) {
                try {
                    mHandler.sendMessageDelayed(timeoutMsg, mRecommendationRequestTimeoutMs);
                    provider.requestRecommendation(request, new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            // Remove the timeout message
                            mHandler.removeMessages(timeoutMsg.what, pair);
                            oneTimeCallback.sendResult(data);
                        }
                    }, 0 /*sequence*/);
                    return;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to request a recommendation.", e);
                    // TODO: 12/15/16 - Keep track of failures.
                    // Remove the timeout message
                    mHandler.removeMessages(timeoutMsg.what, pair);
                    // Will fall through and send back the default recommendation.
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Else send back the default recommendation.
        sendDefaultRecommendationResponse(request, oneTimeCallback);
    }

    @Override
    public boolean requestScores(NetworkKey[] networks) {
        mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES, TAG);
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
        mContext.enforceCallingOrSelfPermission(permission.DUMP, TAG);
        final long token = Binder.clearCallingIdentity();
        try {
            NetworkScorerAppData currentScorer = mNetworkScorerAppManager.getActiveScorer();
            if (currentScorer == null) {
                writer.println("Scoring is disabled.");
                return;
            }
            writer.println("Current scorer: " + currentScorer);

            sendCacheUpdateCallback(new BiConsumer<INetworkScoreCache, Object>() {
                @Override
                public void accept(INetworkScoreCache networkScoreCache, Object cookie) {
                    try {
                        TransferPipe.dumpAsync(networkScoreCache.asBinder(), fd, args);
                    } catch (IOException | RemoteException e) {
                        writer.println("Failed to dump score cache: " + e);
                    }
                }
            }, getScoreCacheLists());

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

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
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

    @VisibleForTesting
    public void refreshRecommendationRequestTimeoutMs() {
        final ContentResolver cr = mContext.getContentResolver();
        long timeoutMs = Settings.Global.getLong(cr,
                Global.NETWORK_RECOMMENDATION_REQUEST_TIMEOUT_MS, -1L /*default*/);
        if (timeoutMs < 0) {
            timeoutMs = TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS;
        }
        if (DBG) Log.d(TAG, "Updating the recommendation request timeout to " + timeoutMs + " ms");
        mRecommendationRequestTimeoutMs = timeoutMs;
        mReqRecommendationCallerRef.set(new RequestRecommendationCaller(timeoutMs));
    }

    private static class ScoringServiceConnection implements ServiceConnection {
        private final NetworkScorerAppData mAppData;
        private volatile boolean mBound = false;
        private volatile boolean mConnected = false;
        private volatile INetworkRecommendationProvider mRecommendationProvider;

        ScoringServiceConnection(NetworkScorerAppData appData) {
            mAppData = appData;
        }

        void connect(Context context) {
            if (!mBound) {
                Intent service = new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS);
                service.setComponent(mAppData.getRecommendationServiceComponent());
                mBound = context.bindServiceAsUser(service, this,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        UserHandle.SYSTEM);
                if (!mBound) {
                    Log.w(TAG, "Bind call failed for " + service);
                } else {
                    if (DBG) Log.d(TAG, "ScoringServiceConnection bound.");
                }
            }
        }

        void disconnect(Context context) {
            try {
                if (mBound) {
                    mBound = false;
                    context.unbindService(this);
                    if (DBG) Log.d(TAG, "ScoringServiceConnection unbound.");
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Unbind failed.", e);
            }

            mRecommendationProvider = null;
        }

        INetworkRecommendationProvider getRecommendationProvider() {
            return mRecommendationProvider;
        }

        String getPackageName() {
            return mAppData.getRecommendationServiceComponent().getPackageName();
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

    /**
     * Executes the async requestRecommendation() call with a timeout.
     */
    private static final class RequestRecommendationCaller
            extends TimedRemoteCaller<RecommendationResult> {
        private final IRemoteCallback mCallback;

        RequestRecommendationCaller(long callTimeoutMillis) {
            super(callTimeoutMillis);
            mCallback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    final RecommendationResult result =
                            data.getParcelable(EXTRA_RECOMMENDATION_RESULT);
                    final int sequence = data.getInt(EXTRA_SEQUENCE, -1);
                    onRemoteMethodResult(result, sequence);
                }
            };
        }

        /**
         * Runs the requestRecommendation() call on the given {@link INetworkRecommendationProvider}
         * instance.
         *
         * @param target the {@link INetworkRecommendationProvider} to request a recommendation
         *               from
         * @param request the {@link RecommendationRequest} from the calling client
         * @return a {@link RecommendationResult} from the provider
         * @throws RemoteException if the call failed
         * @throws TimeoutException if the call took longer than the set timeout
         */
        RecommendationResult getRecommendationResult(INetworkRecommendationProvider target,
                RecommendationRequest request) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.requestRecommendation(request, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    /**
     * A wrapper around {@link RemoteCallback} that guarantees
     * {@link RemoteCallback#sendResult(Bundle)} will be invoked at most once.
     */
    @VisibleForTesting
    public static final class OneTimeCallback {
        private final RemoteCallback mRemoteCallback;
        private final AtomicBoolean mCallbackRun;

        public OneTimeCallback(RemoteCallback remoteCallback) {
            mRemoteCallback = remoteCallback;
            mCallbackRun = new AtomicBoolean(false);
        }

        public void sendResult(Bundle data) {
            if (mCallbackRun.compareAndSet(false, true)) {
                mRemoteCallback.sendResult(data);
            }
        }
    }

    private static void sendDefaultRecommendationResponse(RecommendationRequest request,
            OneTimeCallback remoteCallback) {
        if (DBG) {
            Log.d(TAG, "Returning the default network recommendation.");
        }

        final RecommendationResult result;
        if (request != null && request.getDefaultWifiConfig() != null) {
            result = RecommendationResult.createConnectRecommendation(
                    request.getDefaultWifiConfig());
        } else {
            result = RecommendationResult.createDoNotConnectRecommendation();
        }

        final Bundle data = new Bundle();
        data.putParcelable(EXTRA_RECOMMENDATION_RESULT, result);
        remoteCallback.sendResult(data);
    }

    @VisibleForTesting
    public final class ServiceHandler extends Handler {
        public static final int MSG_RECOMMENDATION_REQUEST_TIMEOUT = 1;
        public static final int MSG_RECOMMENDATIONS_ENABLED_CHANGED = 2;
        public static final int MSG_RECOMMENDATION_REQUEST_TIMEOUT_CHANGED = 3;

        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int what = msg.what;
            switch (what) {
                case MSG_RECOMMENDATION_REQUEST_TIMEOUT:
                    if (DBG) {
                        Log.d(TAG, "Network recommendation request timed out.");
                    }
                    final Pair<RecommendationRequest, OneTimeCallback> pair =
                            (Pair<RecommendationRequest, OneTimeCallback>) msg.obj;
                    final RecommendationRequest request = pair.first;
                    final OneTimeCallback remoteCallback = pair.second;
                    sendDefaultRecommendationResponse(request, remoteCallback);
                    break;

                case MSG_RECOMMENDATIONS_ENABLED_CHANGED:
                    bindToScoringServiceIfNeeded();
                    break;

                case MSG_RECOMMENDATION_REQUEST_TIMEOUT_CHANGED:
                    refreshRecommendationRequestTimeoutMs();
                    break;

                default:
                    Log.w(TAG,"Unknown message: " + what);
            }
        }
    }
}
