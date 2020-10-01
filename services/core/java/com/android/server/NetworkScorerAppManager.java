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
import android.content.PermissionChecker;
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
            if (hasPermissions(serviceInfo.applicationInfo.uid, serviceInfo.packageName)) {
                if (VERBOSE) {
                    Log.v(TAG, serviceInfo.packageName + " is a valid scorer/recommender.");
                }
                final ComponentName serviceComponentName =
                        new ComponentName(serviceInfo.packageName, serviceInfo.name);
                final String serviceLabel = getRecommendationServiceLabel(serviceInfo, pm);
                final ComponentName useOpenWifiNetworksActivity =
                        findUseOpenWifiNetworksActivity(serviceInfo);
                final String networkAvailableNotificationChannelId =
                        getNetworkAvailableNotificationChannelId(serviceInfo);
                appDataList.add(
                        new NetworkScorerAppData(serviceInfo.applicationInfo.uid,
                                serviceComponentName, serviceLabel, useOpenWifiNetworksActivity,
                                networkAvailableNotificationChannelId));
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

    @Nullable
    private static String getNetworkAvailableNotificationChannelId(ServiceInfo serviceInfo) {
        if (serviceInfo.metaData == null) {
            if (DEBUG) {
                Log.d(TAG, "No metadata found on " + serviceInfo.getComponentName());
            }
            return null;
        }

        return serviceInfo.metaData.getString(
                NetworkScoreManager.NETWORK_AVAILABLE_NOTIFICATION_CHANNEL_ID_META_DATA);
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
        final int enabledSetting = getNetworkRecommendationsEnabledSetting();
        if (enabledSetting == NetworkScoreManager.RECOMMENDATIONS_ENABLED_FORCED_OFF) {
            return null;
        }

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

    private boolean hasPermissions(final int uid, final String packageName) {
        return hasScoreNetworksPermission(packageName)
                && canAccessLocation(uid, packageName);
    }

    private boolean hasScoreNetworksPermission(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        return pm.checkPermission(permission.SCORE_NETWORKS, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canAccessLocation(int uid, String packageName) {
        return isLocationModeEnabled() && PermissionChecker.checkPermissionForPreflight(mContext,
                permission.ACCESS_COARSE_LOCATION, PermissionChecker.PID_UNKNOWN, uid, packageName)
                == PermissionChecker.PERMISSION_GRANTED;
    }

    private boolean isLocationModeEnabled() {
        return mSettingsFacade.getSecureInt(mContext, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    /**
     * Set the specified package as the default scorer application.
     *
     * <p>The caller must have permission to write to {@link Settings.Global}.
     *
     * @param packageName the packageName of the new scorer to use. If null, scoring will be forced
     *                    off, otherwise the scorer will only be set if it is a valid scorer
     *                    application.
     * @return true if the package was a valid scorer (including <code>null</code>) and now
     *         represents the active scorer, false otherwise.
     */
    @VisibleForTesting
    public boolean setActiveScorer(String packageName) {
        final String oldPackageName = getNetworkRecommendationsPackage();

        if (TextUtils.equals(oldPackageName, packageName)) {
            // No change.
            return true;
        }

        if (TextUtils.isEmpty(packageName)) {
            Log.i(TAG, "Network scorer forced off, was: " + oldPackageName);
            setNetworkRecommendationsPackage(null);
            setNetworkRecommendationsEnabledSetting(
                    NetworkScoreManager.RECOMMENDATIONS_ENABLED_FORCED_OFF);
            return true;
        }

        // We only make the change if the new package is valid.
        if (getScorer(packageName) != null) {
            Log.i(TAG, "Changing network scorer from " + oldPackageName + " to " + packageName);
            setNetworkRecommendationsPackage(packageName);
            setNetworkRecommendationsEnabledSetting(NetworkScoreManager.RECOMMENDATIONS_ENABLED_ON);
            return true;
        } else {
            Log.w(TAG, "Requested network scorer is not valid: " + packageName);
            return false;
        }
    }

    /**
     * Ensures the {@link Settings.Global#NETWORK_RECOMMENDATIONS_PACKAGE} setting points to a valid
     * package and {@link Settings.Global#NETWORK_RECOMMENDATIONS_ENABLED} is consistent.
     *
     * If {@link Settings.Global#NETWORK_RECOMMENDATIONS_PACKAGE} doesn't point to a valid package
     * then it will be reverted to the default package specified by
     * {@link R.string#config_defaultNetworkRecommendationProviderPackage}. If the default package
     * is no longer valid then {@link Settings.Global#NETWORK_RECOMMENDATIONS_ENABLED} will be set
     * to <code>0</code> (disabled).
     */
    @VisibleForTesting
    public void updateState() {
        final int enabledSetting = getNetworkRecommendationsEnabledSetting();
        if (enabledSetting == NetworkScoreManager.RECOMMENDATIONS_ENABLED_FORCED_OFF) {
            // Don't change anything if it's forced off.
            if (DEBUG) Log.d(TAG, "Recommendations forced off.");
            return;
        }

        // First, see if the current package is still valid. If so, then we can exit early.
        final String currentPackageName = getNetworkRecommendationsPackage();
        if (getScorer(currentPackageName) != null) {
            if (VERBOSE) Log.v(TAG, currentPackageName + " is the active scorer.");
            setNetworkRecommendationsEnabledSetting(NetworkScoreManager.RECOMMENDATIONS_ENABLED_ON);
            return;
        }

        int newEnabledSetting = NetworkScoreManager.RECOMMENDATIONS_ENABLED_OFF;
        // the active scorer isn't valid, revert to the default if it's different and valid
        final String defaultPackageName = getDefaultPackageSetting();
        if (!TextUtils.equals(currentPackageName, defaultPackageName)
                && getScorer(defaultPackageName) != null) {
            if (DEBUG) {
                Log.d(TAG, "Defaulting the network recommendations app to: "
                        + defaultPackageName);
            }
            setNetworkRecommendationsPackage(defaultPackageName);
            newEnabledSetting = NetworkScoreManager.RECOMMENDATIONS_ENABLED_ON;
        }

        setNetworkRecommendationsEnabledSetting(newEnabledSetting);
    }

    /**
     * Migrates the NETWORK_SCORER_APP Setting to the USE_OPEN_WIFI_PACKAGE Setting.
     */
    @VisibleForTesting
    public void migrateNetworkScorerAppSettingIfNeeded() {
        final String scorerAppPkgNameSetting =
                mSettingsFacade.getString(mContext, Settings.Global.NETWORK_SCORER_APP);
        if (TextUtils.isEmpty(scorerAppPkgNameSetting)) {
            // Early exit, nothing to do.
            return;
        }

        final NetworkScorerAppData currentAppData = getActiveScorer();
        if (currentAppData == null) {
            // Don't touch anything until we have an active scorer to work with.
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Migrating Settings.Global.NETWORK_SCORER_APP "
                    + "(" + scorerAppPkgNameSetting + ")...");
        }

        // If the new (useOpenWifi) Setting isn't set and the old Setting's value matches the
        // new metadata value then update the new Setting with the old value. Otherwise it's a
        // mismatch so we shouldn't enable the Setting automatically.
        final ComponentName enableUseOpenWifiActivity =
                currentAppData.getEnableUseOpenWifiActivity();
        final String useOpenWifiSetting =
                mSettingsFacade.getString(mContext, Settings.Global.USE_OPEN_WIFI_PACKAGE);
        if (TextUtils.isEmpty(useOpenWifiSetting)
                && enableUseOpenWifiActivity != null
                && scorerAppPkgNameSetting.equals(enableUseOpenWifiActivity.getPackageName())) {
            mSettingsFacade.putString(mContext, Settings.Global.USE_OPEN_WIFI_PACKAGE,
                    scorerAppPkgNameSetting);
            if (DEBUG) {
                Log.d(TAG, "Settings.Global.USE_OPEN_WIFI_PACKAGE set to "
                        + "'" + scorerAppPkgNameSetting + "'.");
            }
        }

        // Clear out the old setting so we don't run through the migration code again.
        mSettingsFacade.putString(mContext, Settings.Global.NETWORK_SCORER_APP, null);
        if (DEBUG) {
            Log.d(TAG, "Settings.Global.NETWORK_SCORER_APP migration complete.");
            final String setting =
                    mSettingsFacade.getString(mContext, Settings.Global.USE_OPEN_WIFI_PACKAGE);
            Log.d(TAG, "Settings.Global.USE_OPEN_WIFI_PACKAGE is: '" + setting + "'.");
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
        if (VERBOSE) {
            Log.d(TAG, Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE + " set to " + packageName);
        }
    }

    private int getNetworkRecommendationsEnabledSetting() {
        return mSettingsFacade.getInt(mContext, Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0);
    }

    private void setNetworkRecommendationsEnabledSetting(int value) {
        mSettingsFacade.putInt(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, value);
        if (VERBOSE) {
            Log.d(TAG, Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED + " set to " + value);
        }
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

        public boolean putInt(Context context, String name, int value) {
            return Settings.Global.putInt(context.getContentResolver(), name, value);
        }

        public int getInt(Context context, String name, int defaultValue) {
            return Settings.Global.getInt(context.getContentResolver(), name, defaultValue);
        }

        public int getSecureInt(Context context, String name, int defaultValue) {
            return Settings.Secure.getInt(context.getContentResolver(), name, defaultValue);
        }
    }
}
