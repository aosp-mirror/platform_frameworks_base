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

package android.net;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Class that manages communication between network subsystems and a network scorer.
 *
 * <p>A network scorer is any application which:
 * <ul>
 * <li>Is granted the {@link permission#SCORE_NETWORKS} permission.
 * <li>Is granted the {@link permission#ACCESS_COARSE_LOCATION} permission.
 * <li>Include a Service for the {@link #ACTION_RECOMMEND_NETWORKS} action
 *     protected by the {@link permission#BIND_NETWORK_RECOMMENDATION_SERVICE}
 *     permission.
 * </ul>
 *
 * @hide
 */
@SystemApi
@SystemService(Context.NETWORK_SCORE_SERVICE)
public class NetworkScoreManager {
    private static final String TAG = "NetworkScoreManager";

    /**
     * Activity action: ask the user to change the active network scorer. This will show a dialog
     * that asks the user whether they want to replace the current active scorer with the one
     * specified in {@link #EXTRA_PACKAGE_NAME}. The activity will finish with RESULT_OK if the
     * active scorer was changed or RESULT_CANCELED if it failed for any reason.
     * @deprecated No longer sent.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHANGE_ACTIVE = "android.net.scoring.CHANGE_ACTIVE";

    /**
     * Extra used with {@link #ACTION_CHANGE_ACTIVE} to specify the new scorer package. Set with
     * {@link android.content.Intent#putExtra(String, String)}.
     * @deprecated No longer sent.
     */
    @Deprecated
    public static final String EXTRA_PACKAGE_NAME = "packageName";

    /**
     * Broadcast action: new network scores are being requested. This intent will only be delivered
     * to the current active scorer app. That app is responsible for scoring the networks and
     * calling {@link #updateScores} when complete. The networks to score are specified in
     * {@link #EXTRA_NETWORKS_TO_SCORE}, and will generally consist of all networks which have been
     * configured by the user as well as any open networks.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * @deprecated Use {@link #ACTION_RECOMMEND_NETWORKS} to bind scorer app instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCORE_NETWORKS = "android.net.scoring.SCORE_NETWORKS";

    /**
     * Extra used with {@link #ACTION_SCORE_NETWORKS} to specify the networks to be scored, as an
     * array of {@link NetworkKey}s. Can be obtained with
     * {@link android.content.Intent#getParcelableArrayExtra(String)}}.
     * @deprecated Use {@link #ACTION_RECOMMEND_NETWORKS} to bind scorer app instead.
     */
    @Deprecated
    public static final String EXTRA_NETWORKS_TO_SCORE = "networksToScore";

    /**
     * Activity action: launch an activity for configuring a provider for the feature that connects
     * and secures open wifi networks available before enabling it. Applications that enable this
     * feature must provide an activity for this action. The framework will launch this activity
     * which must return RESULT_OK if the feature should be enabled.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CUSTOM_ENABLE = "android.net.scoring.CUSTOM_ENABLE";

    /**
     * Meta-data specified on a {@link NetworkRecommendationProvider} that provides a user-visible
     * label of the recommendation service.
     * @hide
     */
    public static final String RECOMMENDATION_SERVICE_LABEL_META_DATA =
            "android.net.scoring.recommendation_service_label";

    /**
     * Meta-data specified on a {@link NetworkRecommendationProvider} that specified the package
     * name of the application that connects and secures open wifi networks automatically. The
     * specified package must provide an Activity for {@link #ACTION_CUSTOM_ENABLE}.
     * @hide
     */
    public static final String USE_OPEN_WIFI_PACKAGE_META_DATA =
            "android.net.wifi.use_open_wifi_package";

    /**
     * Meta-data specified on a {@link NetworkRecommendationProvider} that specifies the
     * {@link android.app.NotificationChannel} ID used to post open network notifications.
     * @hide
     */
    public static final String NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID_META_DATA =
            "android.net.wifi.notification_channel_id_network_available";

    /**
     * Broadcast action: the active scorer has been changed. Scorer apps may listen to this to
     * perform initialization once selected as the active scorer, or clean up unneeded resources
     * if another scorer has been selected. This is an explicit broadcast only sent to the
     * previous scorer and new scorer. Note that it is unnecessary to clear existing scores as
     * this is handled by the system.
     *
     * <p>The new scorer will be specified in {@link #EXTRA_NEW_SCORER}.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCORER_CHANGED = "android.net.scoring.SCORER_CHANGED";

    /**
     * Service action: Used to discover and bind to a network recommendation provider.
     * Implementations should return {@link NetworkRecommendationProvider#getBinder()} from
     * their <code>onBind()</code> method.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_RECOMMEND_NETWORKS = "android.net.action.RECOMMEND_NETWORKS";

    /**
     * Extra used with {@link #ACTION_SCORER_CHANGED} to specify the newly selected scorer's package
     * name. Will be null if scoring was disabled. Can be obtained with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_NEW_SCORER = "newScorer";

    /** @hide */
    @IntDef({SCORE_FILTER_NONE, SCORE_FILTER_CURRENT_NETWORK, SCORE_FILTER_SCAN_RESULTS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScoreUpdateFilter {}

    /**
     * Do not filter updates sent to the {@link NetworkScoreCallback}].
     */
    public static final int SCORE_FILTER_NONE = 0;

    /**
     * Only send updates to the {@link NetworkScoreCallback} when the network matches the connected
     * network.
     */
    public static final int SCORE_FILTER_CURRENT_NETWORK = 1;

    /**
     * Only send updates to the {@link NetworkScoreCallback} when the network is part of the
     * current scan result set.
     */
    public static final int SCORE_FILTER_SCAN_RESULTS = 2;

    /** @hide */
    @IntDef({RECOMMENDATIONS_ENABLED_FORCED_OFF, RECOMMENDATIONS_ENABLED_OFF,
            RECOMMENDATIONS_ENABLED_ON})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RecommendationsEnabledSetting {}

    /**
     * Recommendations have been forced off.
     * <p>
     * This value is never set by any of the NetworkScore classes, it must be set via other means.
     * This state is also "sticky" and we won't transition out of this state once entered. To move
     * to a different state this value has to be explicitly set to a different value via
     * other means.
     * @hide
     */
    public static final int RECOMMENDATIONS_ENABLED_FORCED_OFF = -1;

    /**
     * Recommendations are not enabled.
     * <p>
     * This is a transient state that can be entered when the default recommendation app is enabled
     * but no longer valid. This state will transition to RECOMMENDATIONS_ENABLED_ON when a valid
     * recommendation app is enabled.
     * @hide
     */
    public static final int RECOMMENDATIONS_ENABLED_OFF = 0;

    /**
     * Recommendations are enabled.
     * <p>
     * This is a transient state that means a valid recommendation app is active. This state will
     * transition to RECOMMENDATIONS_ENABLED_OFF if the current and default recommendation apps
     * become invalid.
     * @hide
     */
    public static final int RECOMMENDATIONS_ENABLED_ON = 1;

    private final Context mContext;
    private final INetworkScoreService mService;

    /** @hide */
    public NetworkScoreManager(Context context) throws ServiceNotFoundException {
        mContext = context;
        mService = INetworkScoreService.Stub
                .asInterface(ServiceManager.getServiceOrThrow(Context.NETWORK_SCORE_SERVICE));
    }

    /**
     * Obtain the package name of the current active network scorer.
     *
     * <p>At any time, only one scorer application will receive {@link #ACTION_SCORE_NETWORKS}
     * broadcasts and be allowed to call {@link #updateScores}. Applications may use this method to
     * determine the current scorer and offer the user the ability to select a different scorer via
     * the {@link #ACTION_CHANGE_ACTIVE} intent.
     * @return the full package name of the current active scorer, or null if there is no active
     *         scorer.
     * @throws SecurityException if the caller doesn't hold either {@link permission#SCORE_NETWORKS}
     *                           or {@link permission#REQUEST_NETWORK_SCORES} permissions.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.SCORE_NETWORKS,
                                 android.Manifest.permission.REQUEST_NETWORK_SCORES})
    public String getActiveScorerPackage() {
        try {
            return mService.getActiveScorerPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns metadata about the active scorer or <code>null</code> if there is no active scorer.
     *
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @hide
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public NetworkScorerAppData getActiveScorer() {
        try {
            return mService.getActiveScorer();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of available scorer apps. The list will be empty if there are
     * no valid scorers.
     *
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public List<NetworkScorerAppData> getAllValidScorers() {
        try {
            return mService.getAllValidScorers();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update network scores.
     *
     * <p>This may be called at any time to re-score active networks. Scores will generally be
     * updated quickly, but if this method is called too frequently, the scores may be held and
     * applied at a later time.
     *
     * @param networks the networks which have been scored by the scorer.
     * @return whether the update was successful.
     * @throws SecurityException if the caller is not the active scorer.
     */
    @RequiresPermission(android.Manifest.permission.SCORE_NETWORKS)
    public boolean updateScores(@NonNull ScoredNetwork[] networks) throws SecurityException {
        try {
            return mService.updateScores(networks);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clear network scores.
     *
     * <p>Should be called when all scores need to be invalidated, i.e. because the scoring
     * algorithm has changed and old scores can no longer be compared to future scores.
     *
     * <p>Note that scores will be cleared automatically when the active scorer changes, as scores
     * from one scorer cannot be compared to those from another scorer.
     *
     * @return whether the clear was successful.
     * @throws SecurityException if the caller is not the active scorer or if the caller doesn't
     *                           hold the {@link permission#REQUEST_NETWORK_SCORES} permission.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.SCORE_NETWORKS,
                                 android.Manifest.permission.REQUEST_NETWORK_SCORES})
    public boolean clearScores() throws SecurityException {
        try {
            return mService.clearScores();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the active scorer to a new package and clear existing scores.
     *
     * <p>Should never be called directly without obtaining user consent. This can be done by using
     * the {@link #ACTION_CHANGE_ACTIVE} broadcast, or using a custom configuration activity.
     *
     * @return true if the operation succeeded, or false if the new package is not a valid scorer.
     * @throws SecurityException if the caller doesn't hold either {@link permission#SCORE_NETWORKS}
     *                           or {@link permission#REQUEST_NETWORK_SCORES} permissions.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.SCORE_NETWORKS,
                                 android.Manifest.permission.REQUEST_NETWORK_SCORES})
    public boolean setActiveScorer(String packageName) throws SecurityException {
        try {
            return mService.setActiveScorer(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Turn off network scoring.
     *
     * <p>May only be called by the current scorer app, or the system.
     *
     * @throws SecurityException if the caller is not the active scorer or if the caller doesn't
     *                           hold the {@link permission#REQUEST_NETWORK_SCORES} permission.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.SCORE_NETWORKS,
                                 android.Manifest.permission.REQUEST_NETWORK_SCORES})
    public void disableScoring() throws SecurityException {
        try {
            mService.disableScoring();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request scoring for networks.
     *
     * <p>
     * Note: The results (i.e scores) for these networks, when available will be provided via the
     * callback registered with {@link #registerNetworkScoreCallback(int, int, Executor,
     * NetworkScoreCallback)}. The calling module is responsible for registering a callback to
     * receive the results before requesting new scores via this API.
     *
     * @return true if the request was successfully sent, or false if there is no active scorer.
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public boolean requestScores(@NonNull NetworkKey[] networks) throws SecurityException {
        try {
            return mService.requestScores(networks);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request scoring for networks.
     *
     * <p>
     * Note: The results (i.e scores) for these networks, when available will be provided via the
     * callback registered with {@link #registerNetworkScoreCallback(int, int, Executor,
     * NetworkScoreCallback)}. The calling module is responsible for registering a callback to
     * receive the results before requesting new scores via this API.
     *
     * @return true if the request was successfully sent, or false if there is no active scorer.
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public boolean requestScores(@NonNull Collection<NetworkKey> networks)
            throws SecurityException {
        return requestScores(networks.toArray(new NetworkKey[0]));
    }

    /**
     * Register a network score cache.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}.
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores.
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @deprecated equivalent to registering for cache updates with {@link #SCORE_FILTER_NONE}.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    @Deprecated // migrate to registerNetworkScoreCache(int, INetworkScoreCache, int)
    public void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        registerNetworkScoreCache(networkType, scoreCache, SCORE_FILTER_NONE);
    }

    /**
     * Register a network score cache.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores
     * @param filterType the {@link ScoreUpdateFilter} to apply
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache,
            @ScoreUpdateFilter int filterType) {
        try {
            mService.registerNetworkScoreCache(networkType, scoreCache, filterType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister a network score cache.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}.
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores.
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public void unregisterNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        try {
            mService.unregisterNetworkScoreCache(networkType, scoreCache);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Base class for network score cache callback. Should be extended by applications and set
     * when calling {@link #registerNetworkScoreCallback(int, int, Executor, NetworkScoreCallback)}.
     *
     * @hide
     */
    @SystemApi
    public abstract static class NetworkScoreCallback {
        /**
         * Called when a new set of network scores are available.
         * This is triggered in response when the client invokes
         * {@link #requestScores(Collection)} to score a new set of networks.
         *
         * @param networks List of {@link ScoredNetwork} containing updated scores.
         */
        public abstract void onScoresUpdated(@NonNull Collection<ScoredNetwork> networks);

        /**
         * Invokes when all the previously provided scores are no longer valid.
         */
        public abstract void onScoresInvalidated();
    }

    /**
     * Callback proxy for {@link NetworkScoreCallback} objects.
     */
    private class NetworkScoreCallbackProxy extends INetworkScoreCache.Stub {
        private final Executor mExecutor;
        private final NetworkScoreCallback mCallback;

        NetworkScoreCallbackProxy(Executor executor, NetworkScoreCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void updateScores(@NonNull List<ScoredNetwork> networks) {
            Binder.clearCallingIdentity();
            mExecutor.execute(() -> {
                mCallback.onScoresUpdated(networks);
            });
        }

        @Override
        public void clearScores() {
            Binder.clearCallingIdentity();
            mExecutor.execute(() -> {
                mCallback.onScoresInvalidated();
            });
        }
    }

    /**
     * Register a network score callback.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}
     * @param filterType the {@link ScoreUpdateFilter} to apply
     * @param callback implementation of {@link NetworkScoreCallback} that will be invoked when the
     *                 scores change.
     * @param executor The executor on which to execute the callbacks.
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a callback is already registered for this type.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public void registerNetworkScoreCallback(@NetworkKey.NetworkType int networkType,
            @ScoreUpdateFilter int filterType,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull NetworkScoreCallback callback) throws SecurityException {
        if (callback == null || executor == null) {
            throw new IllegalArgumentException("callback / executor cannot be null");
        }
        Log.v(TAG, "registerNetworkScoreCallback: callback=" + callback + ", executor="
                + executor);
        // Use the @hide method.
        registerNetworkScoreCache(
                networkType, new NetworkScoreCallbackProxy(executor, callback), filterType);
    }

    /**
     * Determine whether the application with the given UID is the enabled scorer.
     *
     * @param callingUid the UID to check
     * @return true if the provided UID is the active scorer, false otherwise.
     * @throws SecurityException if the caller does not hold the
     *         {@link permission#REQUEST_NETWORK_SCORES} permission.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_NETWORK_SCORES)
    public boolean isCallerActiveScorer(int callingUid) {
        try {
            return mService.isCallerActiveScorer(callingUid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
