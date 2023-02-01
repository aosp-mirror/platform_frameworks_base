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

package com.android.internal.app;

import static com.android.internal.app.AppLocaleStore.AppLocaleResult.LocaleStatus;

import android.app.LocaleConfig;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.LocaleList;
import android.util.Log;

import java.util.HashSet;
import java.util.Locale;
import java.util.stream.Collectors;

class AppLocaleStore {
    private static final String TAG = AppLocaleStore.class.getSimpleName();

    public static AppLocaleResult getAppSupportedLocales(
            Context context, String packageName) {
        LocaleConfig localeConfig = null;
        AppLocaleResult.LocaleStatus localeStatus = LocaleStatus.UNKNOWN_FAILURE;
        HashSet<Locale> appSupportedLocales = new HashSet<>();
        HashSet<Locale> assetLocale = getAssetLocales(context, packageName);

        try {
            localeConfig = new LocaleConfig(context.createPackageContext(packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Can not found the package name : " + packageName + " / " + e);
        }

        if (localeConfig != null) {
            if (localeConfig.getStatus() == LocaleConfig.STATUS_SUCCESS) {
                LocaleList packageLocaleList = localeConfig.getSupportedLocales();
                boolean shouldFilterNotMatchingLocale = !hasInstallerInfo(context, packageName) &&
                        isSystemApp(context, packageName);

                Log.d(TAG, "filterNonMatchingLocale. " +
                        ", shouldFilterNotMatchingLocale: " + shouldFilterNotMatchingLocale +
                        ", assetLocale size: " + assetLocale.size() +
                        ", packageLocaleList size: " + packageLocaleList.size());

                for (int i = 0; i < packageLocaleList.size(); i++) {
                    appSupportedLocales.add(packageLocaleList.get(i));
                }
                if (shouldFilterNotMatchingLocale) {
                    appSupportedLocales = filterNotMatchingLocale(appSupportedLocales, assetLocale);
                }

                if (appSupportedLocales.size() > 0) {
                    localeStatus = LocaleStatus.GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG;
                } else {
                    localeStatus = LocaleStatus.NO_SUPPORTED_LANGUAGE_IN_APP;
                }
            } else if (localeConfig.getStatus() == LocaleConfig.STATUS_NOT_SPECIFIED) {
                if (assetLocale.size() > 0) {
                    localeStatus = LocaleStatus.GET_SUPPORTED_LANGUAGE_FROM_ASSET;
                    appSupportedLocales = assetLocale;
                } else {
                    localeStatus = LocaleStatus.ASSET_LOCALE_IS_EMPTY;
                }
            }
        }
        Log.d(TAG, "getAppSupportedLocales(). package: " + packageName
                + ", status: " + localeStatus
                + ", appSupportedLocales:" + appSupportedLocales.size());
        return new AppLocaleResult(localeStatus, appSupportedLocales);
    }

    private static HashSet<Locale> getAssetLocales(Context context, String packageName) {
        HashSet<Locale> result = new HashSet<>();
        try {
            PackageManager packageManager = context.getPackageManager();
            String[] locales = packageManager.getResourcesForApplication(
                    packageManager.getPackageInfo(packageName, PackageManager.MATCH_ALL)
                            .applicationInfo).getAssets().getNonSystemLocales();
            if (locales == null) {
                Log.i(TAG, "[" + packageName + "] locales are null.");
            } else if (locales.length <= 0) {
                Log.i(TAG, "[" + packageName + "] locales length is 0.");
            } else {
                for (String language : locales) {
                    result.add(Locale.forLanguageTag(language));
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Can not found the package name : " + packageName + " / " + e);
        }
        return result;
    }

    private static HashSet<Locale> filterNotMatchingLocale(
            HashSet<Locale> appSupportedLocales, HashSet<Locale> assetLocale) {
        return appSupportedLocales.stream()
                .filter(locale -> matchLanguageInSet(locale, assetLocale))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static boolean matchLanguageInSet(Locale locale, HashSet<Locale> localesSet) {
        if (localesSet.contains(locale)) {
            return true;
        }
        for (Locale l: localesSet) {
            if(LocaleList.matchesLanguageAndScript(l, locale)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInstallerInfo(Context context, String packageName) {
        InstallSourceInfo installSourceInfo;
        try {
            installSourceInfo = context.getPackageManager().getInstallSourceInfo(packageName);
            return installSourceInfo != null;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Installer info not found for: " + packageName);
        }
        return false;
    }

    private static boolean isSystemApp(Context context, String packageName) {
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = context.getPackageManager()
                    .getApplicationInfoAsUser(packageName, /* flags= */ 0, context.getUserId());
            return applicationInfo.isSystemApp();
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Application info not found for: " + packageName);
        }
        return false;
    }

    static class AppLocaleResult {
        enum LocaleStatus {
            UNKNOWN_FAILURE,
            NO_SUPPORTED_LANGUAGE_IN_APP,
            ASSET_LOCALE_IS_EMPTY,
            GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG,
            GET_SUPPORTED_LANGUAGE_FROM_ASSET,
        }

        LocaleStatus mLocaleStatus;
        HashSet<Locale> mAppSupportedLocales;

        public AppLocaleResult(LocaleStatus localeStatus, HashSet<Locale> appSupportedLocales) {
            this.mLocaleStatus = localeStatus;
            this.mAppSupportedLocales = appSupportedLocales;
        }
    }
}
