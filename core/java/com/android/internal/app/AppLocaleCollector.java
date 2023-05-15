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

import android.app.LocaleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.LocaleList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The Locale data collector for per-app language. */
public class AppLocaleCollector implements LocalePickerWithRegion.LocaleCollectorBase {
    private static final String TAG = AppLocaleCollector.class.getSimpleName();
    private final Context mContext;
    private final String mAppPackageName;
    private LocaleStore.LocaleInfo mAppCurrentLocale;
    private Set<LocaleStore.LocaleInfo> mAllAppActiveLocales;
    private Set<LocaleStore.LocaleInfo> mImeLocales;
    private static final String PROP_APP_LANGUAGE_SUGGESTION =
            "android.app.language.suggestion.enhanced";
    private static final boolean ENABLED = true;

    public AppLocaleCollector(Context context, String appPackageName) {
        mContext = context;
        mAppPackageName = appPackageName;
    }

    @VisibleForTesting
    public LocaleStore.LocaleInfo getAppCurrentLocale() {
        return LocaleStore.getAppActivatedLocaleInfo(mContext, mAppPackageName, true);
    }

    /**
     * Get all applications' activated locales.
     * @return A set which includes all applications' activated LocaleInfo.
     */
    @VisibleForTesting
    public Set<LocaleStore.LocaleInfo> getAllAppActiveLocales() {
        PackageManager pm = mContext.getPackageManager();
        LocaleManager lm = mContext.getSystemService(LocaleManager.class);
        HashSet<LocaleStore.LocaleInfo> result = new HashSet<>();
        if (pm != null && lm != null) {
            HashMap<String, LocaleStore.LocaleInfo> map = new HashMap<>();
            for (ApplicationInfo appInfo : pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0))) {
                LocaleStore.LocaleInfo localeInfo = LocaleStore.getAppActivatedLocaleInfo(
                        mContext, appInfo.packageName, false);
                // For the locale to be added into the suggestion area, its country could not be
                // empty.
                if (localeInfo != null && localeInfo.getLocale().getCountry().length() > 0) {
                    map.put(localeInfo.getId(), localeInfo);
                }
            }
            map.forEach((language, localeInfo) -> result.add(localeInfo));
        }
        return result;
    }

    /**
     * Get all locales that active IME supports.
     *
     * @return A set which includes all LocaleInfo that active IME supports.
     */
    @VisibleForTesting
    public Set<LocaleStore.LocaleInfo> getActiveImeLocales() {
        Set<LocaleStore.LocaleInfo> activeImeLocales = null;
        InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        if (imm != null) {
            InputMethodInfo activeIme = getActiveIme(imm);
            if (activeIme != null) {
                activeImeLocales = LocaleStore.transformImeLanguageTagToLocaleInfo(
                        imm.getEnabledInputMethodSubtypeList(activeIme, true));
            }
        }
        if (activeImeLocales == null) {
            return Set.of();
        } else {
            return activeImeLocales.stream().filter(
                    // For the locale to be added into the suggestion area, its country could not be
                    // empty.
                    info -> info.getLocale().getCountry().length() > 0).collect(
                    Collectors.toSet());
        }
    }

    private InputMethodInfo getActiveIme(InputMethodManager imm) {
        InputMethodInfo activeIme = null;
        List<InputMethodInfo> infoList = imm.getEnabledInputMethodList();
        String imeId = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, mContext.getUserId());
        if (infoList != null && imeId != null) {
            for (InputMethodInfo method : infoList) {
                if (method.getId().equals(imeId)) {
                    activeIme = method;
                }
            }
        }
        return activeIme;
    }

    /**
     * Get the AppLocaleResult that the application supports.
     * @return The AppLocaleResult that the application supports.
     */
    @VisibleForTesting
    public AppLocaleStore.AppLocaleResult getAppSupportedLocales() {
        return AppLocaleStore.getAppSupportedLocales(mContext, mAppPackageName);
    }

    /**
     * Get the locales that system supports excluding langTagsToIgnore.
     *
     * @param langTagsToIgnore A language set to be ignored.
     * @param parent The parent locale.
     * @param translatedOnly specified if is is only for translation.
     * @return A set which includes the LocaleInfo that system supports, excluding langTagsToIgnore.
     */
    @VisibleForTesting
    public Set<LocaleStore.LocaleInfo> getSystemSupportedLocale(Set<String> langTagsToIgnore,
            LocaleStore.LocaleInfo parent, boolean translatedOnly) {
        return LocaleStore.getLevelLocales(mContext, langTagsToIgnore, parent, translatedOnly);
    }

    /**
     * Get a list of system locale that removes all extensions except for the numbering system.
     */
    @VisibleForTesting
    public List<LocaleStore.LocaleInfo> getSystemCurrentLocales() {
        List<LocaleStore.LocaleInfo> sysLocales = LocaleStore.getSystemCurrentLocales();
        return sysLocales.stream().filter(
                // For the locale to be added into the suggestion area, its country could not be
                // empty.
                info -> info.getLocale().getCountry().length() > 0).collect(
                Collectors.toList());
    }

    @Override
    public HashSet<String> getIgnoredLocaleList(boolean translatedOnly) {
        HashSet<String> langTagsToIgnore = new HashSet<>();

        if (mAppCurrentLocale != null) {
            langTagsToIgnore.add(mAppCurrentLocale.getLocale().toLanguageTag());
        }

        if (SystemProperties.getBoolean(PROP_APP_LANGUAGE_SUGGESTION, ENABLED)) {
            // Add the locale that other App activated
            mAllAppActiveLocales.forEach(
                    info -> langTagsToIgnore.add(info.getLocale().toLanguageTag()));
            // Add the locale that active IME enabled
            mImeLocales.forEach(info -> langTagsToIgnore.add(info.getLocale().toLanguageTag()));
        }

        // Add System locales
        LocaleList systemLangList = LocaleList.getDefault();
        for (int i = 0; i < systemLangList.size(); i++) {
            langTagsToIgnore.add(systemLangList.get(i).toLanguageTag());
        }
        return langTagsToIgnore;
    }

    @Override
    public Set<LocaleStore.LocaleInfo> getSupportedLocaleList(LocaleStore.LocaleInfo parent,
            boolean translatedOnly, boolean isForCountryMode) {
        if (mAppCurrentLocale == null) {
            mAppCurrentLocale = getAppCurrentLocale();
        }
        if (mAllAppActiveLocales == null) {
            mAllAppActiveLocales = getAllAppActiveLocales();
        }
        if (mImeLocales == null) {
            mImeLocales = getActiveImeLocales();
        }
        AppLocaleStore.AppLocaleResult result = getAppSupportedLocales();
        Set<String> langTagsToIgnore = getIgnoredLocaleList(translatedOnly);
        Set<LocaleStore.LocaleInfo> appLocaleList = new HashSet<>();
        Set<LocaleStore.LocaleInfo> systemLocaleList;
        boolean shouldShowList =
                result.mLocaleStatus == GET_SUPPORTED_LANGUAGE_FROM_LOCAL_CONFIG
                        || result.mLocaleStatus == GET_SUPPORTED_LANGUAGE_FROM_ASSET;

        // Get system supported locale list
        if (isForCountryMode) {
            systemLocaleList = getSystemSupportedLocale(langTagsToIgnore, parent, translatedOnly);
        } else {
            systemLocaleList = getSystemSupportedLocale(langTagsToIgnore, null, translatedOnly);
        }

        // Add current app locale
        if (mAppCurrentLocale != null && !isForCountryMode) {
            appLocaleList.add(mAppCurrentLocale);
        }

        // Add current system language into suggestion list
        if (!isForCountryMode) {
            boolean isCurrentLocale, existsInApp, existsInIme;
            for (LocaleStore.LocaleInfo localeInfo : getSystemCurrentLocales()) {
                isCurrentLocale = mAppCurrentLocale != null
                        && localeInfo.getLocale().equals(mAppCurrentLocale.getLocale());
                // Add the system suggestion flag if the localeInfo exists in mAllAppActiveLocales
                // and mImeLocales.
                existsInApp = addSystemSuggestionFlag(localeInfo, mAllAppActiveLocales);
                existsInIme = addSystemSuggestionFlag(localeInfo, mImeLocales);
                if (!isCurrentLocale && !existsInApp && !existsInIme) {
                    appLocaleList.add(localeInfo);
                }
            }
        }

        // Add the languages that are included in system supported locale
        Set<LocaleStore.LocaleInfo> suggestedSet = null;
        if (shouldShowList) {
            appLocaleList.addAll(filterSupportedLocales(systemLocaleList,
                    result.mAppSupportedLocales));
            suggestedSet = getSuggestedLocales(appLocaleList);
        }

        if (!isForCountryMode && SystemProperties.getBoolean(PROP_APP_LANGUAGE_SUGGESTION,
                ENABLED)) {
            // Add the language that other apps activate into the suggestion list.
            Set<LocaleStore.LocaleInfo> localeSet = filterSupportedLocales(mAllAppActiveLocales,
                    result.mAppSupportedLocales);
            if (suggestedSet != null) {
                // Filter out the locale with the same language and country
                // like zh-TW vs zh-Hant-TW.
                localeSet = filterSameLanguageAndCountry(localeSet, suggestedSet);
                // Add IME suggestion flag if the locale is supported by IME.
                localeSet = addImeSuggestionFlag(localeSet);
            }
            appLocaleList.addAll(localeSet);
            suggestedSet.addAll(localeSet);

            // Add the language that the active IME enables into the suggestion list.
            localeSet = filterSupportedLocales(mImeLocales, result.mAppSupportedLocales);
            if (suggestedSet != null) {
                localeSet = filterSameLanguageAndCountry(localeSet, suggestedSet);
            }
            appLocaleList.addAll(localeSet);
            suggestedSet.addAll(localeSet);
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

    private Set<LocaleStore.LocaleInfo> getSuggestedLocales(Set<LocaleStore.LocaleInfo> localeSet) {
        return localeSet.stream().filter(localeInfo -> localeInfo.isSuggested()).collect(
                Collectors.toSet());
    }

    private boolean addSystemSuggestionFlag(LocaleStore.LocaleInfo localeInfo,
            Set<LocaleStore.LocaleInfo> appLocaleSet) {
        for (LocaleStore.LocaleInfo info : appLocaleSet) {
            if (info.getLocale().equals(localeInfo.getLocale())) {
                info.extendSuggestionOfType(
                        LocaleStore.LocaleInfo.SUGGESTION_TYPE_SYSTEM_AVAILABLE_LANGUAGE);
                return true;
            }
        }
        return false;
    }

    private Set<LocaleStore.LocaleInfo> addImeSuggestionFlag(
            Set<LocaleStore.LocaleInfo> localeSet) {
        for (LocaleStore.LocaleInfo localeInfo : localeSet) {
            for (LocaleStore.LocaleInfo imeLocale : mImeLocales) {
                if (imeLocale.getLocale().equals(localeInfo.getLocale())) {
                    localeInfo.extendSuggestionOfType(
                            LocaleStore.LocaleInfo.SUGGESTION_TYPE_IME_LANGUAGE);
                }
            }
        }
        return localeSet;
    }

    private Set<LocaleStore.LocaleInfo> filterSameLanguageAndCountry(
            Set<LocaleStore.LocaleInfo> newLocaleList,
            Set<LocaleStore.LocaleInfo> existingLocaleList) {
        Set<LocaleStore.LocaleInfo> result = new HashSet<>(newLocaleList.size());
        for (LocaleStore.LocaleInfo appLocaleInfo : newLocaleList) {
            boolean same = false;
            Locale appLocale = appLocaleInfo.getLocale();
            for (LocaleStore.LocaleInfo localeInfo : existingLocaleList) {
                Locale suggested = localeInfo.getLocale();
                if (appLocale.getLanguage().equals(suggested.getLanguage())
                        && appLocale.getCountry().equals(suggested.getCountry())) {
                    same = true;
                    break;
                }
            }
            if (!same) {
                result.add(appLocaleInfo);
            }
        }
        return result;
    }

    private Set<LocaleStore.LocaleInfo> filterSupportedLocales(
            Set<LocaleStore.LocaleInfo> suggestedLocales,
            HashSet<Locale> appSupportedLocales) {
        Set<LocaleStore.LocaleInfo> filteredList = new HashSet<>();

        for (LocaleStore.LocaleInfo li : suggestedLocales) {
            if (appSupportedLocales.contains(li.getLocale())) {
                filteredList.add(li);
            } else {
                for (Locale l : appSupportedLocales) {
                    if (LocaleList.matchesLanguageAndScript(li.getLocale(), l)) {
                        filteredList.add(li);
                        break;
                    }
                }
            }
        }
        return filteredList;
    }
}
