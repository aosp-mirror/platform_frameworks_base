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

import static com.android.internal.app.AppLocaleStore.AppLocaleResult.LocaleStatus.GET_SUPPORTED_LANGUAGE_FROM_ASSET;
import static com.android.internal.app.AppLocaleStore.AppLocaleResult.LocaleStatus.GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG;

import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The Locale data collector for per-app language. */
class AppLocaleCollector implements LocalePickerWithRegion.LocaleCollectorBase {
    private static final String TAG = AppLocaleCollector.class.getSimpleName();
    private final Context mContext;
    private final String mAppPackageName;
    private final LocaleStore.LocaleInfo mAppCurrentLocale;

    AppLocaleCollector(Context context, String appPackageName) {
        mContext = context;
        mAppPackageName = appPackageName;
        mAppCurrentLocale = LocaleStore.getAppCurrentLocaleInfo(
                mContext, mAppPackageName);
    }

    @Override
    public HashSet<String> getIgnoredLocaleList(boolean translatedOnly) {
        HashSet<String> langTagsToIgnore = new HashSet<>();

        LocaleList systemLangList = LocaleList.getDefault();
        for(int i = 0; i < systemLangList.size(); i++) {
            langTagsToIgnore.add(systemLangList.get(i).toLanguageTag());
        }

        if (mAppCurrentLocale != null) {
            langTagsToIgnore.add(mAppCurrentLocale.getLocale().toLanguageTag());
        }
        return langTagsToIgnore;
    }

    @Override
    public Set<LocaleStore.LocaleInfo> getSupportedLocaleList(LocaleStore.LocaleInfo parent,
            boolean translatedOnly, boolean isForCountryMode) {
        AppLocaleStore.AppLocaleResult result =
                AppLocaleStore.getAppSupportedLocales(mContext, mAppPackageName);
        Set<String> langTagsToIgnore = getIgnoredLocaleList(translatedOnly);
        Set<LocaleStore.LocaleInfo> appLocaleList = new HashSet<>();
        Set<LocaleStore.LocaleInfo> systemLocaleList;
        boolean shouldShowList =
                result.mLocaleStatus == GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG
                        || result.mLocaleStatus == GET_SUPPORTED_LANGUAGE_FROM_ASSET;

        // Get system supported locale list
        if (isForCountryMode) {
            systemLocaleList = LocaleStore.getLevelLocales(mContext,
                    langTagsToIgnore, parent, translatedOnly);
        } else {
            systemLocaleList = LocaleStore.getLevelLocales(mContext, langTagsToIgnore,
                    null /* no parent */, translatedOnly);
        }

        // Add current app locale
        if (mAppCurrentLocale != null && !isForCountryMode) {
            appLocaleList.add(mAppCurrentLocale);
        }

        // Add current system language into suggestion list
        for(LocaleStore.LocaleInfo localeInfo:
                LocaleStore.getSystemCurrentLocaleInfo()) {
            boolean isNotCurrentLocale = mAppCurrentLocale == null
                    || !localeInfo.getLocale().equals(mAppCurrentLocale.getLocale());
            if (!isForCountryMode && isNotCurrentLocale) {
                appLocaleList.add(localeInfo);
            }
        }

        // Add the languages that included in system supported locale
        if (shouldShowList) {
            appLocaleList.addAll(filterTheLanguagesNotIncludedInSystemLocale(
                    systemLocaleList, result.mAppSupportedLocales));
        }

        // Add "system language" option
        if (!isForCountryMode && shouldShowList) {
            appLocaleList.add(LocaleStore.getSystemDefaultLocaleInfo(
                    mAppCurrentLocale == null));
        }

        if (Build.isDebuggable()) {
            Log.d(TAG, "App locale list: " + appLocaleList);
        }

        return appLocaleList;
    }

    @Override
    public boolean hasSpecificPackageName() {
        return true;
    }

    private Set<LocaleStore.LocaleInfo> filterTheLanguagesNotIncludedInSystemLocale(
            Set<LocaleStore.LocaleInfo> systemLocaleList,
            HashSet<Locale> appSupportedLocales) {
        Set<LocaleStore.LocaleInfo> filteredList = new HashSet<>();

        for(LocaleStore.LocaleInfo li: systemLocaleList) {
            if (appSupportedLocales.contains(li.getLocale())) {
                filteredList.add(li);
            } else {
                for(Locale l: appSupportedLocales) {
                    if(LocaleList.matchesLanguageAndScript(li.getLocale(), l)) {
                        filteredList.add(li);
                        break;
                    }
                }
            }
        }
        return filteredList;
    }
}
