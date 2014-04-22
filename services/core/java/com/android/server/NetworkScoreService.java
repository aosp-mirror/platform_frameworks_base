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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.INetworkScoreService;
import android.net.NetworkKey;
import android.net.NetworkScorerAppManager;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.text.TextUtils;

import com.android.internal.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Backing service for {@link android.net.NetworkScoreManager}.
 * @hide
 */
public class NetworkScoreService extends INetworkScoreService.Stub {
    private static final String TAG = "NetworkScoreService";

    /** SharedPreference bit set to true after the service is first initialized. */
    private static final String PREF_SCORING_PROVISIONED = "is_provisioned";

    private final Context mContext;

    // TODO: Delete this temporary class once we have a real place for scores.
    private final Map<NetworkKey, RssiCurve> mScoredNetworks;

    public NetworkScoreService(Context context) {
        mContext = context;
        mScoredNetworks = new HashMap<>();
    }

    /** Called when the system is ready to run third-party code but before it actually does so. */
    void systemReady() {
        SharedPreferences prefs = mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_SCORING_PROVISIONED, false)) {
            // On first run, we try to initialize the scorer to the one configured at build time.
            // This will be a no-op if the scorer isn't actually valid.
            String defaultPackage = mContext.getResources().getString(
                    R.string.config_defaultNetworkScorerPackageName);
            if (!TextUtils.isEmpty(defaultPackage)) {
                NetworkScorerAppManager.setActiveScorer(mContext, defaultPackage);
            }
            prefs.edit().putBoolean(PREF_SCORING_PROVISIONED, true).apply();
        }
    }

    @Override
    public boolean updateScores(ScoredNetwork[] networks) {
        if (!NetworkScorerAppManager.isCallerActiveScorer(mContext, getCallingUid())) {
            throw new SecurityException("Caller with UID " + getCallingUid() +
                    " is not the active scorer.");
        }

        // TODO: Propagate these scores down to the network subsystem layer instead of just holding
        // them in memory.
        for (ScoredNetwork network : networks) {
            mScoredNetworks.put(network.networkKey, network.rssiCurve);
        }

        return true;
    }

    @Override
    public boolean clearScores() {
        // Only the active scorer or the system (who can broadcast BROADCAST_SCORE_NETWORKS) should
        // be allowed to flush all scores.
        if (NetworkScorerAppManager.isCallerActiveScorer(mContext, getCallingUid()) ||
                mContext.checkCallingOrSelfPermission(permission.BROADCAST_SCORE_NETWORKS) ==
                        PackageManager.PERMISSION_GRANTED) {
            clearInternal();
            return true;
        } else {
            throw new SecurityException(
                    "Caller is neither the active scorer nor the scorer manager.");
        }
    }

    @Override
    public boolean setActiveScorer(String packageName) {
        mContext.enforceCallingOrSelfPermission(permission.BROADCAST_SCORE_NETWORKS, TAG);
        // Preemptively clear scores even though the set operation could fail. We do this for safety
        // as scores should never be compared across apps; in practice, Settings should only be
        // allowing valid apps to be set as scorers, so failure here should be rare.
        clearInternal();
        return NetworkScorerAppManager.setActiveScorer(mContext, packageName);
    }

    /** Clear scores. Callers are responsible for checking permissions as appropriate. */
    private void clearInternal() {
        // TODO: Propagate the flush down to the network subsystem layer.
        mScoredNetworks.clear();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(permission.DUMP, TAG);
        String currentScorer = NetworkScorerAppManager.getActiveScorer(mContext);
        if (currentScorer == null) {
            writer.println("Scoring is disabled.");
            return;
        }
        writer.println("Current scorer: " + currentScorer);
        if (mScoredNetworks.isEmpty()) {
            writer.println("No networks scored.");
        } else {
            for (Map.Entry<NetworkKey, RssiCurve> entry : mScoredNetworks.entrySet()) {
                writer.println(entry.getKey() + ": " + entry.getValue());
            }
        }
    }
}
