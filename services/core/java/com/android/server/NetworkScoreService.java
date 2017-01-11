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
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.ArrayMap;
import android.util.Log;
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
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Backing service for {@link android.net.NetworkScoreManager}.
 * @hide
 */
public class NetworkScoreService extends INetworkScoreService.Stub {
    private static final String TAG = "NetworkScoreService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final NetworkScorerAppManager mNetworkScorerAppManager;
    private final RequestRecommendationCaller mRequestRecommendationCaller;
    @GuardedBy("mScoreCaches")
    private final Map<Integer, RemoteCallbackList<INetworkScoreCache>> mScoreCaches;
    /** Lock used to update mPackageMonitor when scorer package changes occur. */
    private final Object mPackageMonitorLock = new Object[0];
    private final Object mServiceConnectionLock = new Object[0];

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
            } else if (activeScorer.packageName.equals(scorerPackageName)) {
                // The active scoring service changed in some way.
                if (DBG) {
                    Log.d(TAG, "Possible change to the active scorer: "
                            + activeScorer.packageName);
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
                    Log.d(TAG, "Binding to " + activeScorer.packageName + " if needed.");
                }
                bindToScoringServiceIfNeeded(activeScorer);
            }
        }
    }

    /**
     * Reevaluates the service binding when the Settings toggle is changed.
     */
    private class SettingsObserver extends ContentObserver {

        public SettingsObserver() {
            super(null /*handler*/);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (DBG) Log.d(TAG, String.format("onChange(%s, %s)", selfChange, uri));
            bindToScoringServiceIfNeeded();
        }
    }

    public NetworkScoreService(Context context) {
      this(context, new NetworkScorerAppManager(context));
    }

    @VisibleForTesting
    NetworkScoreService(Context context, NetworkScorerAppManager networkScoreAppManager) {
        mContext = context;
        mNetworkScorerAppManager = networkScoreAppManager;
        mScoreCaches = new ArrayMap<>();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        // TODO: Need to update when we support per-user scorers. http://b/23422763
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.SYSTEM, filter, null /* broadcastPermission*/,
                null /* scheduler */);
        // TODO(jjoslin): 12/15/16 - Make timeout configurable.
        mRequestRecommendationCaller =
            new RequestRecommendationCaller(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
    }

    /** Called when the system is ready to run third-party code but before it actually does so. */
    void systemReady() {
        if (DBG) Log.d(TAG, "systemReady");
        registerPackageMonitorIfNeeded();
        registerRecommendationSettingObserverIfNeeded();
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

    private void registerRecommendationSettingObserverIfNeeded() {
        final List<String> providerPackages =
            mNetworkScorerAppManager.getPotentialRecommendationProviderPackages();
        if (!providerPackages.isEmpty()) {
            final ContentResolver resolver = mContext.getContentResolver();
            final Uri uri = Global.getUriFor(Global.NETWORK_RECOMMENDATIONS_ENABLED);
            resolver.registerContentObserver(uri, false, new SettingsObserver());
        }
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

    private void bindToScoringServiceIfNeeded(NetworkScorerAppData scorerData) {
        if (DBG) Log.d(TAG, "bindToScoringServiceIfNeeded(" + scorerData + ")");
        if (scorerData != null && scorerData.recommendationServiceClassName != null) {
            ComponentName componentName = new ComponentName(scorerData.packageName,
                    scorerData.recommendationServiceClassName);
            synchronized (mServiceConnectionLock) {
                // If we're connected to a different component then drop it.
                if (mServiceConnection != null
                        && !mServiceConnection.mComponentName.equals(componentName)) {
                    unbindFromScoringServiceIfNeeded();
                }

                // If we're not connected at all then create a new connection.
                if (mServiceConnection == null) {
                    mServiceConnection = new ScoringServiceConnection(componentName,
                            scorerData.packageUid);
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

                sendCallback(new Consumer<INetworkScoreCache>() {
                    @Override
                    public void accept(INetworkScoreCache networkScoreCache) {
                        try {
                            networkScoreCache.updateScores(entry.getValue());
                        } catch (RemoteException e) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "Unable to update scores of type " + entry.getKey(), e);
                            }
                        }
                    }
                }, Collections.singleton(callbackList));
            }

            return true;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isCallerSystemUid() {
        // REQUEST_NETWORK_SCORES is a signature only permission.
        return mContext.checkCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES) ==
                 PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean clearScores() {
        // Only the active scorer or the system should be allowed to flush all scores.
        if (isCallerActiveScorer(getCallingUid()) || isCallerSystemUid()) {
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
            return mServiceConnection != null && mServiceConnection.mScoringAppUid == callingUid;
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
        synchronized (mServiceConnectionLock) {
            if (mServiceConnection != null) {
                return mServiceConnection.mComponentName.getPackageName();
            }
        }
        return null;
    }

    @Override
    public void disableScoring() {
        // Only the active scorer or the system should be allowed to disable scoring.
        if (isCallerActiveScorer(getCallingUid()) || isCallerSystemUid()) {
            // no-op for now but we could write to the setting if needed.
        } else {
            throw new SecurityException(
                    "Caller is neither the active scorer nor the scorer manager.");
        }
    }

    /** Clear scores. Callers are responsible for checking permissions as appropriate. */
    private void clearInternal() {
        sendCallback(new Consumer<INetworkScoreCache>() {
            @Override
            public void accept(INetworkScoreCache networkScoreCache) {
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
                    return mRequestRecommendationCaller.getRecommendationResult(provider, request);
                } catch (RemoteException | TimeoutException e) {
                    Log.w(TAG, "Failed to request a recommendation.", e);
                    // TODO(jjoslin): 12/15/16 - Keep track of failures.
                }
            }

            if (DBG) {
                Log.d(TAG, "Returning the default network recommendation.");
            }

            if (request != null && request.getCurrentSelectedConfig() != null) {
                return RecommendationResult.createConnectRecommendation(
                        request.getCurrentSelectedConfig());
            }
            return RecommendationResult.createDoNotConnectRecommendation();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
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
                    // TODO(jjoslin): 12/15/16 - Consider pushing null scores into the cache to
                    // prevent repeated requests for the same scores.
                    return true;
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to request scores.", e);
                    // TODO(jjoslin): 12/15/16 - Keep track of failures.
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
        NetworkScorerAppData currentScorer = mNetworkScorerAppManager.getActiveScorer();
        if (currentScorer == null) {
            writer.println("Scoring is disabled.");
            return;
        }
        writer.println("Current scorer: " + currentScorer.packageName);

        sendCallback(new Consumer<INetworkScoreCache>() {
            @Override
            public void accept(INetworkScoreCache networkScoreCache) {
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

    private void sendCallback(Consumer<INetworkScoreCache> consumer,
            Collection<RemoteCallbackList<INetworkScoreCache>> remoteCallbackLists) {
        for (RemoteCallbackList<INetworkScoreCache> callbackList : remoteCallbackLists) {
            synchronized (callbackList) { // Ensure only one active broadcast per RemoteCallbackList
                final int count = callbackList.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        consumer.accept(callbackList.getBroadcastItem(i));
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

    private static class ScoringServiceConnection implements ServiceConnection {
        private final ComponentName mComponentName;
        private final int mScoringAppUid;
        private volatile boolean mBound = false;
        private volatile boolean mConnected = false;
        private volatile INetworkRecommendationProvider mRecommendationProvider;

        ScoringServiceConnection(ComponentName componentName, int scoringAppUid) {
            mComponentName = componentName;
            mScoringAppUid = scoringAppUid;
        }

        void connect(Context context) {
            if (!mBound) {
                Intent service = new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS);
                service.setComponent(mComponentName);
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
            writer.println("ScoringServiceConnection: " + mComponentName + ", bound: " + mBound
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
}
