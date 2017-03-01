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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal class for discovering and managing the network scorer/recommendation application.
 *
 * @hide
 */
@VisibleForTesting
public class NetworkScorerAppManager {
    private static final String TAG = "NetworkScorerAppManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private final Context mContext;
    private final SettingsFacade mSettingsFacade;

    public NetworkScorerAppManager(Context context) {
      this(context, new SettingsFacade());
    }

    @VisibleForTesting
    public NetworkScorerAppManager(Context context, SettingsFacade settingsFacade) {
        mContext = context;
        mSettingsFacade = settingsFacade;
    }

    /**
     * Returns the list of available scorer apps. The list will be empty if there are
     * no valid scorers.
     */
    @VisibleForTesting
    public List<NetworkScorerAppData> getAllValidScorers() {
        if (VERBOSE) Log.v(TAG, "getAllValidScorers()");
        final PackageManager pm = mContext.getPackageManager();
        final Intent serviceIntent = new Intent(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS);
        final List<ResolveInfo> resolveInfos =
                pm.queryIntentServices(serviceIntent, PackageManager.GET_META_DATA);
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            if (DEBUG) Log.d(TAG, "Found 0 Services able to handle " + serviceIntent);
            return Collections.emptyList();
        }

        List<NetworkScorerAppData> appDataList = new ArrayList<>();
        for (int i = 0; i < resolveInfos.size(); i++) {
            final ServiceInfo serviceInfo = resolveInfos.get(i).serviceInfo;
            if (hasPermissions(serviceInfo.packageName)) {
                if (VERBOSE) {
                    Log.v(TAG, serviceInfo.packageName + " is a valid scorer/recommender.");
                }
                final ComponentName serviceComponentName =
                        new ComponentName(serviceInfo.packageName, serviceInfo.name);
                final String serviceLabel = getRecommendationServiceLabel(serviceInfo, pm);
                final ComponentName useOpenWifiNetworksActivity =
                        findUseOpenWifiNetworksActivity(serviceInfo);
                appDataList.add(
                        new NetworkScorerAppData(serviceInfo.applicationInfo.uid,
                                serviceComponentName, serviceLabel, useOpenWifiNetworksActivity));
            } else {
                if (VERBOSE) Log.v(TAG, serviceInfo.packageName
                        + " is NOT a valid scorer/recommender.");
            }
        }

        return appDataList;
    }

    @Nullable
    private String getRecommendationServiceLabel(ServiceInfo serviceInfo, PackageManager pm) {
        if (serviceInfo.metaData != null) {
            final String label = serviceInfo.metaData
                    .getString(NetworkScoreManager.RECOMMENDATION_SERVICE_LABEL_META_DATA);
            if (!TextUtils.isEmpty(label)) {
                return label;
            }
        }
        CharSequence label = serviceInfo.loadLabel(pm);
        return label == null ? null : label.toString();
    }

    @Nullable
    private ComponentName findUseOpenWifiNetworksActivity(ServiceInfo serviceInfo) {
        if (serviceInfo.metaData == null) {
            if (DEBUG) {
                Log.d(TAG, "No metadata found on " + serviceInfo.getComponentName());
            }
            return null;
        }
        final String useOpenWifiPackage = serviceInfo.metaData
                .getString(NetworkScoreManager.USE_OPEN_WIFI_PACKAGE_META_DATA);
        if (TextUtils.isEmpty(useOpenWifiPackage)) {
            if (DEBUG) {
                Log.d(TAG, "No use_open_wifi_package metadata found on "
                        + serviceInfo.getComponentName());
            }
            return null;
        }
        final Intent enableUseOpenWifiIntent = new Intent(NetworkScoreManager.ACTION_CUSTOM_ENABLE)
                .setPackage(useOpenWifiPackage);
        final ResolveInfo resolveActivityInfo = mContext.getPackageManager()
                .resolveActivity(enableUseOpenWifiIntent, 0 /* flags */);
        if (VERBOSE) {
            Log.d(TAG, "Resolved " + enableUseOpenWifiIntent + " to " + resolveActivityInfo);
        }

        if (resolveActivityInfo != null && resolveActivityInfo.activityInfo != null) {
            return resolveActivityInfo.activityInfo.getComponentName();
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
    @VisibleForTesting
    public NetworkScorerAppData getActiveScorer() {
        return getScorer(getNetworkRecommendationsPackage());
    }

    private NetworkScorerAppData getScorer(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }

        // Otherwise return the recommendation provider (which may be null).
        List<NetworkScorerAppData> apps = getAllValidScorers();
        for (int i = 0; i < apps.size(); i++) {
            NetworkScorerAppData app = apps.get(i);
            if (app.getRecommendationServicePackageName().equals(packageName)) {
                return app;
            }
        }

        return null;
    }

    private boolean hasPermissions(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        return pm.checkPermission(permission.SCORE_NETWORKS, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Set the specified package as the default scorer application.
     *
     * <p>The caller must have permission to write to {@link Settings.Global}.
     *
     * @param packageName the packageName of the new scorer to use. If null, the scoring app will
     *                    revert back to the configured default. Otherwise, the scorer will only
     *                    be set if it is a valid scorer application.
     * @return true if the scorer was changed, or false if the package is not a valid scorer or
     *         a valid network recommendation provider exists.
     */
    @VisibleForTesting
    public boolean setActiveScorer(String packageName) {
        String oldPackageName = getNetworkRecommendationsPackage();
        if (TextUtils.equals(oldPackageName, packageName)) {
            // No change.
            return true;
        }

        Log.i(TAG, "Changing network scorer from " + oldPackageName + " to " + packageName);

        if (packageName == null) {
            // revert to the default setting.
            setNetworkRecommendationsPackage(getDefaultPackageSetting());
            return true;
        } else {
            // We only make the change if the new package is valid.
            if (getScorer(packageName) != null) {
                setNetworkRecommendationsPackage(packageName);
                return true;
            } else {
                Log.w(TAG, "Requested network scorer is not valid: " + packageName);
                return false;
            }
        }
    }

    /**
     * If the active scorer is null then revert to the default scorer.
     */
    @VisibleForTesting
    public void revertToDefaultIfNoActive() {
        if (getActiveScorer() == null) {
            final String defaultPackage = getDefaultPackageSetting();
            setNetworkRecommendationsPackage(defaultPackage);
            Log.i(TAG, "Defaulted the network recommendations app to: " + defaultPackage);
        }
    }

    private String getDefaultPackageSetting() {
        return mContext.getResources().getString(
                R.string.config_defaultNetworkRecommendationProviderPackage);
    }

    private String getNetworkRecommendationsPackage() {
        return mSettingsFacade.getString(mContext, Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE);
    }

    private void setNetworkRecommendationsPackage(String packageName) {
        mSettingsFacade.putString(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE, packageName);
    }

    /**
     * Wrapper around Settings to make testing easier.
     */
    public static class SettingsFacade {
        public boolean putString(Context context, String name, String value) {
            return Settings.Global.putString(context.getContentResolver(), name, value);
        }

        public String getString(Context context, String name) {
            return Settings.Global.getString(context.getContentResolver(), name);
        }
    }
}
