/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.locales;

import android.app.LocaleConfig;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Track when a app is being updated.
 */
public class AppUpdateTracker {
    private static final String TAG = "AppUpdateTracker";

    private final Context mContext;
    private final LocaleManagerService mLocaleManagerService;
    private final LocaleManagerBackupHelper mBackupHelper;

    AppUpdateTracker(Context context, LocaleManagerService localeManagerService,
            LocaleManagerBackupHelper backupHelper) {
        mContext = context;
        mLocaleManagerService = localeManagerService;
        mBackupHelper = backupHelper;
    }

    /**
     * <p><b>Note:</b> This is invoked by service's common monitor
     * {@link LocaleManagerServicePackageMonitor#onPackageUpdateFinished} when a package is upgraded
     * on device.
     */
    public void onPackageUpdateFinished(String packageName, int uid) {
        Log.d(TAG, "onPackageUpdateFinished " + packageName);
        int userId = UserHandle.getUserId(uid);
        cleanApplicationLocalesIfNeeded(packageName, userId);
    }

    /**
     * When the user has set per-app locales for a specific application from a delegate selector,
     * and then the LocaleConfig of that application is removed in the upgraded version, the per-app
     * locales needs to be reset to system default locales to avoid the user being unable to change
     * system locales setting.
     */
    private void cleanApplicationLocalesIfNeeded(String packageName, int userId) {
        Set<String> packageNames = new ArraySet<>();
        SharedPreferences delegateAppLocalePackages = mBackupHelper.getPersistedInfo();
        if (delegateAppLocalePackages != null) {
            packageNames = delegateAppLocalePackages.getStringSet(Integer.toString(userId),
                    new ArraySet<>());
        }

        try {
            LocaleList appLocales = mLocaleManagerService.getApplicationLocales(packageName,
                    userId);
            if (appLocales.isEmpty() || isLocalesExistedInLocaleConfig(appLocales, packageName,
                    userId) || !packageNames.contains(packageName)) {
                return;
            }
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Exception when getting locales for " + packageName, e);
            return;
        }

        Slog.d(TAG, "Clear app locales for " + packageName);
        try {
            mLocaleManagerService.setApplicationLocales(packageName, userId,
                    LocaleList.forLanguageTags(""), false);
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Could not clear locales for " + packageName, e);
        }
    }

    /**
     * Check whether the LocaleConfig is existed and the per-app locales is presented in the
     * LocaleConfig file after the application is upgraded.
     */
    private boolean isLocalesExistedInLocaleConfig(LocaleList appLocales, String packageName,
            int userId) {
        LocaleList packageLocalesList = getPackageLocales(packageName, userId);
        HashSet<Locale> packageLocales = new HashSet<>();

        if (isSettingsAppLocalesOptIn()) {
            if (packageLocalesList == null || packageLocalesList.isEmpty()) {
                // The app locale feature is not enabled by the app
                Slog.d(TAG, "opt-in: the app locale feature is not enabled");
                return false;
            }
        } else {
            if (packageLocalesList != null && packageLocalesList.isEmpty()) {
                // The app locale feature is not enabled by the app
                Slog.d(TAG, "opt-out: the app locale feature is not enabled");
                return false;
            }
        }

        if (packageLocalesList != null && !packageLocalesList.isEmpty()) {
            // The app has added the supported locales into the LocaleConfig
            for (int i = 0; i < packageLocalesList.size(); i++) {
                packageLocales.add(packageLocalesList.get(i));
            }
            if (!matchesLocale(packageLocales, appLocales)) {
                // The set app locales do not match with the list of app supported locales
                Slog.d(TAG, "App locales: " + appLocales.toLanguageTags()
                        + " are not existed in the supported locale list");
                return false;
            }
        }

        return true;
    }

    /**
     * Get locales from LocaleConfig.
     */
    @VisibleForTesting
    public LocaleList getPackageLocales(String packageName, int userId) {
        try {
            LocaleConfig localeConfig = new LocaleConfig(
                    mContext.createPackageContextAsUser(packageName, 0, UserHandle.of(userId)));
            if (localeConfig.getStatus() == LocaleConfig.STATUS_SUCCESS) {
                return localeConfig.getSupportedLocales();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Can not found the package name : " + packageName + " / " + e);
        }
        return null;
    }

    /**
     * Check whether the feature to show per-app locales list in Settings is enabled.
     */
    @VisibleForTesting
    public boolean isSettingsAppLocalesOptIn() {
        return FeatureFlagUtils.isEnabled(mContext,
                FeatureFlagUtils.SETTINGS_APP_LOCALE_OPT_IN_ENABLED);
    }

    private boolean matchesLocale(HashSet<Locale> supported, LocaleList appLocales) {
        if (supported.size() <= 0 || appLocales.size() <= 0) {
            return true;
        }

        for (int i = 0; i < appLocales.size(); i++) {
            final Locale appLocale = appLocales.get(i);
            if (supported.stream().anyMatch(
                    locale -> LocaleList.matchesLanguageAndScript(locale, appLocale))) {
                return true;
            }
        }

        return false;
    }
}
