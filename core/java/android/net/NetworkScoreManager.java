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

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class that manages communication between network subsystems and a network scorer.
 *
 * <p>You can get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String)}:
 *
 * <pre>NetworkScoreManager manager =
 *     (NetworkScoreManager) getSystemService(Context.NETWORK_SCORE_SERVICE)</pre>
 *
 * <p>A network scorer is any application which:
 * <ul>
 * <li>Declares the {@link android.Manifest.permission#SCORE_NETWORKS} permission.
 * <li>Include a Service for the {@link #ACTION_RECOMMEND_NETWORKS} action
 *     protected by the {@link android.Manifest.permission#BIND_NETWORK_RECOMMENDATION_SERVICE}
 *     permission.
 * </ul>
 *
 * @hide
 */
@SystemApi
public class NetworkScoreManager {
    /**
     * Activity action: ask the user to change the active network scorer. This will show a dialog
     * that asks the user whether they want to replace the current active scorer with the one
     * specified in {@link #EXTRA_PACKAGE_NAME}. The activity will finish with RESULT_OK if the
     * active scorer was changed or RESULT_CANCELED if it failed for any reason.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHANGE_ACTIVE = "android.net.scoring.CHANGE_ACTIVE";

    /**
     * Extra used with {@link #ACTION_CHANGE_ACTIVE} to specify the new scorer package. Set with
     * {@link android.content.Intent#putExtra(String, String)}.
     */
    public static final String EXTRA_PACKAGE_NAME = "packageName";

    /**
     * Broadcast action: new network scores are being requested. This intent will only be delivered
     * to the current active scorer app. That app is responsible for scoring the networks and
     * calling {@link #updateScores} when complete. The networks to score are specified in
     * {@link #EXTRA_NETWORKS_TO_SCORE}, and will generally consist of all networks which have been
     * configured by the user as well as any open networks.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCORE_NETWORKS = "android.net.scoring.SCORE_NETWORKS";

    /**
     * Extra used with {@link #ACTION_SCORE_NETWORKS} to specify the networks to be scored, as an
     * array of {@link NetworkKey}s. Can be obtained with
     * {@link android.content.Intent#getParcelableArrayExtra(String)}}.
     */
    public static final String EXTRA_NETWORKS_TO_SCORE = "networksToScore";

    /**
     * Activity action: launch a custom activity for configuring a scorer before enabling it.
     * Scorer applications may choose to specify an activity for this action, in which case the
     * framework will launch that activity which should return RESULT_OK if scoring was enabled.
     *
     * <p>If no activity is included in a scorer which implements this action, the system dialog for
     * selecting a scorer will be shown instead.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CUSTOM_ENABLE = "android.net.scoring.CUSTOM_ENABLE";

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
    @IntDef({CACHE_FILTER_NONE, CACHE_FILTER_CURRENT_NETWORK, CACHE_FILTER_SCAN_RESULTS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CacheUpdateFilter {}

    /**
     * Do not filter updates sent to the cache.
     * @hide
     */
    public static final int CACHE_FILTER_NONE = 0;

    /**
     * Only send cache updates when the network matches the connected network.
     * @hide
     */
    public static final int CACHE_FILTER_CURRENT_NETWORK = 1;

    /**
     * Only send cache updates when the network is part of the current scan result set.
     * @hide
     */
    public static final int CACHE_FILTER_SCAN_RESULTS = 2;

    private final Context mContext;
    private final INetworkScoreService mService;

    /** @hide */
    public NetworkScoreManager(Context context) {
        mContext = context;
        IBinder iBinder = ServiceManager.getService(Context.NETWORK_SCORE_SERVICE);
        mService = INetworkScoreService.Stub.asInterface(iBinder);
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
     */
    public String getActiveScorerPackage() {
        try {
            return mService.getActiveScorerPackage();
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
    public boolean updateScores(ScoredNetwork[] networks) throws SecurityException {
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
     * @throws SecurityException if the caller is not the active scorer or privileged.
     */
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
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#SCORE_NETWORKS} permission.
     * @hide
     */
    @SystemApi
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
     * @throws SecurityException if the caller is neither the active scorer nor the system.
     */
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
     * @return true if the broadcast was sent, or false if there is no active scorer.
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#REQUEST_NETWORK_SCORES} permission.
     * @hide
     */
    public boolean requestScores(NetworkKey[] networks) throws SecurityException {
        try {
            return mService.requestScores(networks);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register a network score cache.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}.
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores.
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @deprecated equivalent to registering for cache updates with CACHE_FILTER_NONE.
     * @hide
     */
    @Deprecated // migrate to registerNetworkScoreCache(int, INetworkScoreCache, int)
    public void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        registerNetworkScoreCache(networkType, scoreCache, CACHE_FILTER_NONE);
    }

    /**
     * Register a network score cache.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores
     * @param filterType the {@link CacheUpdateFilter} to apply
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @hide
     */
    public void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache,
            @CacheUpdateFilter int filterType) {
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
     *         {@link android.Manifest.permission#REQUEST_NETWORK_SCORES} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @hide
     */
    public void unregisterNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        try {
            mService.unregisterNetworkScoreCache(networkType, scoreCache);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request a recommendation for which network to connect to.
     *
     * <p>It is not safe to call this method from the main thread.
     *
     * @param request a {@link RecommendationRequest} instance containing additional
     *                request details
     * @return a {@link RecommendationResult} instance containing the recommended network
     *         to connect to
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#REQUEST_NETWORK_SCORES} permission.
     */
    public RecommendationResult requestRecommendation(RecommendationRequest request)
            throws SecurityException {
        try {
            return mService.requestRecommendation(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determine whether the application with the given UID is the enabled scorer.
     *
     * @param callingUid the UID to check
     * @return true if the provided UID is the active scorer, false otherwise.
     * @hide
     */
    public boolean isCallerActiveScorer(int callingUid) {
        try {
            return mService.isCallerActiveScorer(callingUid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
