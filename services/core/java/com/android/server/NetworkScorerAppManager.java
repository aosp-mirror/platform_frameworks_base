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
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal class for discovering and managing the network scorer/recommendation application.
 *
 * @hide
 */
public class NetworkScorerAppManager {
    private static final String TAG = "NetworkScorerAppManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private final Context mContext;

    public NetworkScorerAppManager(Context context) {
      mContext = context;
    }

    /**
     * Returns the list of available scorer apps. The list will be empty if there are
     * no valid scorers.
     */
    public List<NetworkScorerAppData> getAllValidScorers() {
        return Collections.emptyList();
    }

    /**
     * @return A {@link NetworkScorerAppData} instance containing information about the
     *         best configured network recommendation provider installed or {@code null}
     *         if none of the configured packages can recommend networks.
     *
     * <p>A network recommendation provider is any application which:
     * <ul>
     * <li>Is listed in the <code>config_networkRecommendationPackageNames</code> config.
     * <li>Declares the {@link permission#SCORE_NETWORKS} permission.
     * <li>Includes a Service for {@link NetworkScoreManager#ACTION_RECOMMEND_NETWORKS}.
     * </ul>
     */
    @Nullable public NetworkScorerAppData getNetworkRecommendationProviderData() {
        // Network recommendation apps can only run as the primary user right now.
        // http://b/23422763
        if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
            return null;
        }

        final List<String> potentialPkgs = getPotentialRecommendationProviderPackages();
        if (potentialPkgs.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "No Network Recommendation Providers specified.");
            }
            return null;
        }

        for (int i = 0; i < potentialPkgs.size(); i++) {
            final String potentialPkg = potentialPkgs.get(i);

            // Look for the recommendation service class and required receiver.
            final ServiceInfo serviceInfo = findRecommendationService(potentialPkg);
            if (serviceInfo != null) {
                final ComponentName serviceComponentName =
                    new ComponentName(potentialPkg, serviceInfo.name);
                final ComponentName useOpenWifiNetworksActivity =
                        findUseOpenWifiNetworksActivity(serviceInfo);
                return new NetworkScorerAppData(serviceInfo.applicationInfo.uid,
                    serviceComponentName, useOpenWifiNetworksActivity);
            } else {
                if (DEBUG) {
                    Log.d(TAG, potentialPkg + " does not have the required components, skipping.");
                }
            }
        }

        // None of the configured packages are valid.
        return null;
    }

    @Nullable private ComponentName findUseOpenWifiNetworksActivity(ServiceInfo serviceInfo) {
        if (serviceInfo.metaData == null) {
            if (DEBUG) {
                Log.d(TAG, "No metadata found on recommendation service.");
            }
            return null;
        }
        final String useOpenWifiPackage = serviceInfo.metaData
                .getString(NetworkScoreManager.USE_OPEN_WIFI_PACKAGE_META_DATA);
        if (TextUtils.isEmpty(useOpenWifiPackage)) {
            if (DEBUG) {
                Log.d(TAG, "No use_open_wifi_package metadata found.");
            }
            return null;
        }
        final Intent enableUseOpenWifiIntent = new Intent(NetworkScoreManager.ACTION_CUSTOM_ENABLE)
                .setPackage(useOpenWifiPackage);
        final ResolveInfo resolveActivityInfo = mContext.getPackageManager()
                .resolveActivity(enableUseOpenWifiIntent, 0 /* flags */);
        if (VERBOSE) {
            Log.d(TAG, "Resolved " + enableUseOpenWifiIntent + " to " + serviceInfo);
        }

        if (resolveActivityInfo != null && resolveActivityInfo.activityInfo != null) {
            return resolveActivityInfo.activityInfo.getComponentName();
        }

        return null;
    }

    /**
     * @return A priority order list of package names that have been granted the
     *         permission needed for them to act as a network recommendation provider.
     *         The packages in the returned list may not contain the other required
     *         network recommendation provider components so additional checks are required
     *         before making a package the network recommendation provider.
     */
    public List<String> getPotentialRecommendationProviderPackages() {
        final String[] packageArray = mContext.getResources().getStringArray(
                R.array.config_networkRecommendationPackageNames);
        if (packageArray == null || packageArray.length == 0) {
            if (DEBUG) {
                Log.d(TAG, "No Network Recommendation Providers specified.");
            }
            return Collections.emptyList();
        }

        if (VERBOSE) {
            Log.d(TAG, "Configured packages: " + TextUtils.join(", ", packageArray));
        }

        List<String> packages = new ArrayList<>();
        final PackageManager pm = mContext.getPackageManager();
        for (String potentialPkg : packageArray) {
            if (pm.checkPermission(permission.SCORE_NETWORKS, potentialPkg)
                    == PackageManager.PERMISSION_GRANTED) {
                packages.add(potentialPkg);
            } else {
                if (DEBUG) {
                    Log.d(TAG, potentialPkg + " has not been granted " + permission.SCORE_NETWORKS
                            + ", skipping.");
                }
            }
        }

        return packages;
    }

    @Nullable private ServiceInfo findRecommendationService(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        final int resolveFlags = PackageManager.GET_META_DATA;
        final Intent serviceIntent = new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS);
        serviceIntent.setPackage(packageName);
        final ResolveInfo resolveServiceInfo =
                pm.resolveService(serviceIntent, resolveFlags);

        if (VERBOSE) {
            Log.d(TAG, "Resolved " + serviceIntent + " to " + resolveServiceInfo);
        }

        if (resolveServiceInfo != null && resolveServiceInfo.serviceInfo != null) {
            return resolveServiceInfo.serviceInfo;
        }

        if (VERBOSE) {
            Log.v(TAG, packageName + " does not have a service for " + serviceIntent);
        }
        return null;
    }

    /**
     * Get the application to use for scoring networks.
     *
     * @return the scorer app info or null if scoring is disabled (including if no scorer was ever
     *     selected) or if the previously-set scorer is no longer a valid scorer app (e.g. because
     *     it was disabled or uninstalled).
     */
    @Nullable
    public NetworkScorerAppData getActiveScorer() {
        if (isNetworkRecommendationsDisabled()) {
            // If recommendations are disabled then there can't be an active scorer.
            return null;
        }

        // Otherwise return the recommendation provider (which may be null).
        return getNetworkRecommendationProviderData();
    }

    /**
     * Set the specified package as the default scorer application.
     *
     * <p>The caller must have permission to write to {@link android.provider.Settings.Global}.
     *
     * @param packageName the packageName of the new scorer to use. If null, scoring will be
     *     disabled. Otherwise, the scorer will only be set if it is a valid scorer application.
     * @return true if the scorer was changed, or false if the package is not a valid scorer or
     *         a valid network recommendation provider exists.
     * @deprecated Scorers are now selected from a configured list.
     */
    @Deprecated
    public boolean setActiveScorer(String packageName) {
        return false;
    }

    private boolean isNetworkRecommendationsDisabled() {
        final ContentResolver cr = mContext.getContentResolver();
        // A value of 1 indicates enabled.
        return Settings.Global.getInt(cr, Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0) != 1;
    }
}
