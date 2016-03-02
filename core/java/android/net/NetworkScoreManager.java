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
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkScorerAppManager.NetworkScorerAppData;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

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
 * <li>Includes a receiver for {@link #ACTION_SCORE_NETWORKS} guarded by the
 *     {@link android.Manifest.permission#BROADCAST_NETWORK_PRIVILEGED} permission which scores
 *     networks and (eventually) calls {@link #updateScores} with the results. If this receiver
 *     specifies an android:label attribute, this label will be used when referring to the
 *     application throughout system settings; otherwise, the application label will be used.
 * </ul>
 *
 * <p>The system keeps track of an active scorer application; at any time, only this application
 * will receive {@link #ACTION_SCORE_NETWORKS} broadcasts and will be permitted to call
 * {@link #updateScores}. Applications may determine the current active scorer with
 * {@link #getActiveScorerPackage()} and request to change the active scorer by sending an
 * {@link #ACTION_CHANGE_ACTIVE} broadcast with another scorer.
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
     * Extra used with {@link #ACTION_SCORER_CHANGED} to specify the newly selected scorer's package
     * name. Will be null if scoring was disabled. Can be obtained with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_NEW_SCORER = "newScorer";

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
        NetworkScorerAppData app = NetworkScorerAppManager.getActiveScorer(mContext);
        if (app == null) {
            return null;
        }
        return app.mPackageName;
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
     * <p>Note that this is just a helper method to assemble the broadcast, and will run in the
     * calling process.
     *
     * @return true if the broadcast was sent, or false if there is no active scorer.
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#BROADCAST_NETWORK_PRIVILEGED} permission.
     * @hide
     */
    public boolean requestScores(NetworkKey[] networks) throws SecurityException {
        String activeScorer = getActiveScorerPackage();
        if (activeScorer == null) {
            return false;
        }
        Intent intent = new Intent(ACTION_SCORE_NETWORKS);
        intent.setPackage(activeScorer);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(EXTRA_NETWORKS_TO_SCORE, networks);
        // A scorer should never become active if its package doesn't hold SCORE_NETWORKS, but
        // ensure the package still holds it to be extra safe.
        // TODO: http://b/23422763
        mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM, Manifest.permission.SCORE_NETWORKS);
        return true;
    }

    /**
     * Register a network score cache.
     *
     * @param networkType the type of network this cache can handle. See {@link NetworkKey#type}.
     * @param scoreCache implementation of {@link INetworkScoreCache} to store the scores.
     * @throws SecurityException if the caller does not hold the
     *         {@link android.Manifest.permission#BROADCAST_NETWORK_PRIVILEGED} permission.
     * @throws IllegalArgumentException if a score cache is already registered for this type.
     * @hide
     */
    public void registerNetworkScoreCache(int networkType, INetworkScoreCache scoreCache) {
        try {
            mService.registerNetworkScoreCache(networkType, scoreCache);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
