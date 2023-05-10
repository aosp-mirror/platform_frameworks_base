/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.IntDef;
import android.app.LocaleManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.LocaleList;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;

import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LocaleStore {
    private static final int TIER_LANGUAGE = 1;
    private static final int TIER_REGION = 2;
    private static final int TIER_NUMBERING = 3;
    private static final HashMap<String, LocaleInfo> sLocaleCache = new HashMap<>();
    private static final String TAG = LocaleStore.class.getSimpleName();
    private static boolean sFullyInitialized = false;

    public static class LocaleInfo implements Serializable {
        public static final int SUGGESTION_TYPE_NONE = 0;
        // A mask used to identify the suggested locale is from SIM.
        public static final int SUGGESTION_TYPE_SIM = 1 << 0;
        // A mask used to identify the suggested locale is from the config.
        public static final int SUGGESTION_TYPE_CFG = 1 << 1;
        // Only for per-app language picker
        // A mask used to identify the suggested locale is from the same application's current
        // configured locale.
        public static final int SUGGESTION_TYPE_CURRENT = 1 << 2;
        // Only for per-app language picker
        // A mask used to identify the suggested locale is the system default language.
        public  static final int SUGGESTION_TYPE_SYSTEM_LANGUAGE = 1 << 3;
        // Only for per-app language picker
        // A mask used to identify the suggested locale is from other applications' configured
        // locales.
        public static final int SUGGESTION_TYPE_OTHER_APP_LANGUAGE = 1 << 4;
        // Only for per-app language picker
        // A mask used to identify the suggested locale is what the active IME supports.
        public static final int SUGGESTION_TYPE_IME_LANGUAGE = 1 << 5;
        // Only for per-app language picker
        // A mask used to identify the suggested locale is in the current system languages.
        public static final int SUGGESTION_TYPE_SYSTEM_AVAILABLE_LANGUAGE = 1 << 6;
        /** @hide */
        @IntDef(prefix = { "SUGGESTION_TYPE_" }, value = {
                SUGGESTION_TYPE_NONE,
                SUGGESTION_TYPE_SIM,
                SUGGESTION_TYPE_CFG,
                SUGGESTION_TYPE_CURRENT,
                SUGGESTION_TYPE_SYSTEM_LANGUAGE,
                SUGGESTION_TYPE_OTHER_APP_LANGUAGE,
                SUGGESTION_TYPE_IME_LANGUAGE,
                SUGGESTION_TYPE_SYSTEM_AVAILABLE_LANGUAGE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SuggestionType {}

        private final Locale mLocale;
        private final Locale mParent;
        private final String mId;
        private boolean mIsTranslated;
        private boolean mIsPseudo;
        private boolean mIsChecked; // Used by the LocaleListEditor to mark entries for deletion
        // Combination of flags for various reasons to show a locale as a suggestion.
        // Can be SIM, location, etc.
        // Set to public to be accessible during runtime from the test app.
        @VisibleForTesting public int mSuggestionFlags;

        private String mFullNameNative;
        private String mFullCountryNameNative;
        private String mLangScriptKey;

        private boolean mHasNumberingSystems;

        private LocaleInfo(Locale locale) {
            this.mLocale = locale;
            this.mId = locale.toLanguageTag();
            this.mParent = getParent(locale);
            this.mHasNumberingSystems = false;
            this.mIsChecked = false;
            this.mSuggestionFlags = SUGGESTION_TYPE_NONE;
            this.mIsTranslated = false;
            this.mIsPseudo = false;
        }

        private LocaleInfo(String localeId) {
            this(Locale.forLanguageTag(localeId));
        }

        private LocaleInfo(LocaleInfo localeInfo) {
            this.mLocale = localeInfo.getLocale();
            this.mId = localeInfo.getId();
            this.mParent = localeInfo.getParent();
            this.mHasNumberingSystems = localeInfo.mHasNumberingSystems;
            this.mIsChecked = localeInfo.getChecked();
            this.mSuggestionFlags = localeInfo.mSuggestionFlags;
            this.mIsTranslated = localeInfo.isTranslated();
            this.mIsPseudo = localeInfo.mIsPseudo;
        }

        private static Locale getParent(Locale locale) {
            if (locale.getCountry().isEmpty()) {
                return null;
            }
            return new Locale.Builder()
                    .setLocale(locale)
                    .setRegion("")
                    .setExtension(Locale.UNICODE_LOCALE_EXTENSION, "")
                    .build();
        }

        /** Return true if there are any same locales with different numbering system. */
        public boolean hasNumberingSystems() {
            return mHasNumberingSystems;
        }

        @Override
        public String toString() {
            return mId;
        }

        @UnsupportedAppUsage
        public Locale getLocale() {
            return mLocale;
        }

        @UnsupportedAppUsage
        public Locale getParent() {
            return mParent;
        }

        /**
         * TODO: This method may rename to be more generic i.e. toLanguageTag().
         */
        @UnsupportedAppUsage
        public String getId() {
            return mId;
        }

        public boolean isTranslated() {
            return mIsTranslated;
        }

        public void setTranslated(boolean isTranslated) {
            mIsTranslated = isTranslated;
        }

        public boolean isSuggested() {
            if (!mIsTranslated) { // Never suggest an untranslated locale
                return false;
            }
            return mSuggestionFlags != SUGGESTION_TYPE_NONE;
        }

        /**
         * Check whether the LocaleInfo is suggested by a specific mask
         *
         * @param suggestionMask The mask which is used to identify the suggestion flag.
         * @return true if the locale is suggested by a specific suggestion flag. Otherwise, false.
         */
        public boolean isSuggestionOfType(int suggestionMask) {
            if (!mIsTranslated) { // Never suggest an untranslated locale
                return false;
            }
            return (mSuggestionFlags & suggestionMask) == suggestionMask;
        }

        /**
         * Extend the locale's suggestion type
         *
         * @param suggestionMask The mask to extend the suggestion flag
         */
        public void extendSuggestionOfType(@SuggestionType int suggestionMask) {
            if (!mIsTranslated) { // Never suggest an untranslated locale
                return;
            }
            mSuggestionFlags |= suggestionMask;
        }

        @UnsupportedAppUsage
        public String getFullNameNative() {
            if (mFullNameNative == null) {
                Locale locale = mLocale.stripExtensions();
                mFullNameNative =
                        LocaleHelper.getDisplayName(locale, locale, true /* sentence case */);
            }
            return mFullNameNative;
        }

        public String getFullCountryNameNative() {
            if (mFullCountryNameNative == null) {
                mFullCountryNameNative = LocaleHelper.getDisplayCountry(mLocale, mLocale);
            }
            return mFullCountryNameNative;
        }

        String getFullCountryNameInUiLanguage() {
            // We don't cache the UI name because the default locale keeps changing
            return LocaleHelper.getDisplayCountry(mLocale);
        }

        /** Returns the name of the locale in the language of the UI.
         * It is used for search, but never shown.
         * For instance German will show as "Deutsch" in the list, but we will also search for
         * "allemand" if the system UI is in French.
         */
        @UnsupportedAppUsage
        public String getFullNameInUiLanguage() {
            Locale locale = mLocale.stripExtensions();
            // We don't cache the UI name because the default locale keeps changing
            return LocaleHelper.getDisplayName(locale, true /* sentence case */);
        }

        private String getLangScriptKey() {
            if (mLangScriptKey == null) {
                Locale baseLocale = new Locale.Builder()
                        .setLocale(mLocale)
                        .setExtension(Locale.UNICODE_LOCALE_EXTENSION, "")
                        .build();
                Locale parentWithScript = getParent(LocaleHelper.addLikelySubtags(baseLocale));
                mLangScriptKey =
                        (parentWithScript == null)
                                ? mLocale.toLanguageTag()
                                : parentWithScript.toLanguageTag();
            }
            return mLangScriptKey;
        }

        String getLabel(boolean countryMode) {
            if (countryMode) {
                return getFullCountryNameNative();
            } else {
                return getFullNameNative();
            }
        }

        String getNumberingSystem() {
            return LocaleHelper.getDisplayNumberingSystemKeyValue(mLocale, mLocale);
        }

        String getContentDescription(boolean countryMode) {
            if (countryMode) {
                return getFullCountryNameInUiLanguage();
            } else {
                return getFullNameInUiLanguage();
            }
        }

        public boolean getChecked() {
            return mIsChecked;
        }

        public void setChecked(boolean checked) {
            mIsChecked = checked;
        }

        public boolean isAppCurrentLocale() {
            return (mSuggestionFlags & SUGGESTION_TYPE_CURRENT) > 0;
        }

        public boolean isSystemLocale() {
            return (mSuggestionFlags & SUGGESTION_TYPE_SYSTEM_LANGUAGE) > 0;
        }

        public boolean isInCurrentSystemLocales() {
            return (mSuggestionFlags & SUGGESTION_TYPE_SYSTEM_AVAILABLE_LANGUAGE) > 0;
        }
    }

    private static Set<String> getSimCountries(Context context) {
        Set<String> result = new HashSet<>();

        TelephonyManager tm = context.getSystemService(TelephonyManager.class);

        if (tm != null) {
            String iso = tm.getSimCountryIso().toUpperCase(Locale.US);
            if (!iso.isEmpty()) {
                result.add(iso);
            }

            iso = tm.getNetworkCountryIso().toUpperCase(Locale.US);
            if (!iso.isEmpty()) {
                result.add(iso);
            }
        }

        return result;
    }

    /*
     * This method is added for SetupWizard, to force an update of the suggested locales
     * when the SIM is initialized.
     *
     * <p>When the device is freshly started, it sometimes gets to the language selection
     * before the SIM is properly initialized.
     * So at the time the cache is filled, the info from the SIM might not be available.
     * The SetupWizard has a SimLocaleMonitor class to detect onSubscriptionsChanged events.
     * SetupWizard will call this function when that happens.</p>
     *
     * <p>TODO: decide if it is worth moving such kind of monitoring in this shared code.
     * The user might change the SIM or might cross border and connect to a network
     * in a different country, without restarting the Settings application or the phone.</p>
     */
    public static void updateSimCountries(Context context) {
        Set<String> simCountries = getSimCountries(context);

        for (LocaleInfo li : sLocaleCache.values()) {
            // This method sets the suggestion flags for the (new) SIM locales, but it does not
            // try to clean up the old flags. After all, if the user replaces a German SIM
            // with a French one, it is still possible that they are speaking German.
            // So both French and German are reasonable suggestions.
            if (simCountries.contains(li.getLocale().getCountry())) {
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            }
        }
    }

    /**
     * Get the application's activated locale.
     *
     * @param context UI activity's context.
     * @param appPackageName The application's package name.
     * @param isAppSelected True if the application is selected in the UI; false otherwise.
     * @return A LocaleInfo with the application's activated locale.
     */
    public static LocaleInfo getAppActivatedLocaleInfo(Context context, String appPackageName,
            boolean isAppSelected) {
        if (appPackageName == null) {
            return null;
        }

        LocaleManager localeManager = context.getSystemService(LocaleManager.class);
        try {
            LocaleList localeList = (localeManager == null)
                    ? null : localeManager.getApplicationLocales(appPackageName);
            Locale locale = localeList == null ? null : localeList.get(0);

            if (locale != null) {
                LocaleInfo cacheInfo  = getLocaleInfo(locale, sLocaleCache);
                LocaleInfo localeInfo = new LocaleInfo(cacheInfo);
                if (isAppSelected) {
                    localeInfo.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_CURRENT;
                } else {
                    localeInfo.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_OTHER_APP_LANGUAGE;
                }
                return localeInfo;
            }
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "IllegalArgumentException ", e);
        }
        return null;
    }

    /**
     * Transform IME's language tag to LocaleInfo.
     *
     * @param list A list which includes IME's subtype.
     * @return A LocaleInfo set which includes IME's language tags.
     */
    public static Set<LocaleInfo> transformImeLanguageTagToLocaleInfo(
            List<InputMethodSubtype> list) {
        Set<LocaleInfo> imeLocales = new HashSet<>();
        for (InputMethodSubtype subtype : list) {
            Locale locale = Locale.forLanguageTag(subtype.getLanguageTag());
            LocaleInfo cacheInfo  = getLocaleInfo(locale, sLocaleCache);
            LocaleInfo localeInfo = new LocaleInfo(cacheInfo);
            localeInfo.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_IME_LANGUAGE;
            imeLocales.add(localeInfo);
        }
        return imeLocales;
    }

    /**
     * Returns a list of system locale that removes all extensions except for the numbering system.
     */
    public static List<LocaleInfo> getSystemCurrentLocales() {
        List<LocaleInfo> localeList = new ArrayList<>();
        LocaleList systemLangList = LocaleList.getDefault();
        for(int i = 0; i < systemLangList.size(); i++) {
            Locale sysLocale = getLocaleWithOnlyNumberingSystem(systemLangList.get(i));
            LocaleInfo cacheInfo  = getLocaleInfo(sysLocale, sLocaleCache);
            LocaleInfo localeInfo = new LocaleInfo(cacheInfo);
            localeInfo.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SYSTEM_AVAILABLE_LANGUAGE;
            localeList.add(localeInfo);
        }
        return localeList;
    }

    /**
     * The "system default" is special case for per-app picker. Intentionally keep the locale
     * empty to let activity know "system default" been selected.
     */
    public static LocaleInfo getSystemDefaultLocaleInfo(boolean hasAppLanguage) {
        LocaleInfo systemDefaultInfo = new LocaleInfo("");
        systemDefaultInfo.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SYSTEM_LANGUAGE;
        if (hasAppLanguage) {
            systemDefaultInfo.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_CURRENT;
        }
        systemDefaultInfo.mIsTranslated = true;
        return systemDefaultInfo;
    }

    /*
     * Show all the languages supported for a country in the suggested list.
     * This is also handy for devices without SIM (tablets).
     */
    private static void addSuggestedLocalesForRegion(Locale locale) {
        if (locale == null) {
            return;
        }
        final String country = locale.getCountry();
        if (country.isEmpty()) {
            return;
        }

        for (LocaleInfo li : sLocaleCache.values()) {
            if (country.equals(li.getLocale().getCountry())) {
                // We don't need to differentiate between manual and SIM suggestions
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            }
        }
    }

    @UnsupportedAppUsage
    public static void fillCache(Context context) {
        if (sFullyInitialized) {
            return;
        }

        Set<String> simCountries = getSimCountries(context);

        final boolean isInDeveloperMode = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        Set<Locale> numberSystemLocaleList = new HashSet<>();
        for (String localeId : LocalePicker.getSupportedLocales(context)) {
            if (Locale.forLanguageTag(localeId).getUnicodeLocaleType("nu") != null) {
                numberSystemLocaleList.add(Locale.forLanguageTag(localeId));
            }
        }
        for (String localeId : LocalePicker.getSupportedLocales(context)) {
            if (localeId.isEmpty()) {
                throw new IllformedLocaleException("Bad locale entry in locale_config.xml");
            }
            LocaleInfo li = new LocaleInfo(localeId);

            if (LocaleList.isPseudoLocale(li.getLocale())) {
                if (isInDeveloperMode) {
                    li.setTranslated(true);
                    li.mIsPseudo = true;
                    li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
                } else {
                    // Do not display pseudolocales unless in development mode.
                    continue;
                }
            }

            if (simCountries.contains(li.getLocale().getCountry())) {
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            }
            numberSystemLocaleList.forEach(l -> {
                if (li.getLocale().stripExtensions().equals(l.stripExtensions())) {
                    li.mHasNumberingSystems = true;
                }
            });

            sLocaleCache.put(li.getId(), li);
            final Locale parent = li.getParent();
            if (parent != null) {
                String parentId = parent.toLanguageTag();
                if (!sLocaleCache.containsKey(parentId)) {
                    sLocaleCache.put(parentId, new LocaleInfo(parent));
                }
            }
        }

        // TODO: See if we can reuse what LocaleList.matchScore does
        final HashSet<String> localizedLocales = new HashSet<>();
        for (String localeId : LocalePicker.getSystemAssetLocales()) {
            LocaleInfo li = new LocaleInfo(localeId);
            final String country = li.getLocale().getCountry();
            // All this is to figure out if we should suggest a country
            if (!country.isEmpty()) {
                LocaleInfo cachedLocale = null;
                if (sLocaleCache.containsKey(li.getId())) { // the simple case, e.g. fr-CH
                    cachedLocale = sLocaleCache.get(li.getId());
                } else { // e.g. zh-TW localized, zh-Hant-TW in cache
                    final String langScriptCtry = li.getLangScriptKey() + "-" + country;
                    if (sLocaleCache.containsKey(langScriptCtry)) {
                        cachedLocale = sLocaleCache.get(langScriptCtry);
                    }
                }
                if (cachedLocale != null) {
                    cachedLocale.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_CFG;
                }
            }
            localizedLocales.add(li.getLangScriptKey());
        }

        for (LocaleInfo li : sLocaleCache.values()) {
            li.setTranslated(localizedLocales.contains(li.getLangScriptKey()));
        }

        addSuggestedLocalesForRegion(Locale.getDefault());

        sFullyInitialized = true;
    }

    private static boolean isShallIgnore(
            Set<String> ignorables, LocaleInfo li, boolean translatedOnly) {
        if (ignorables.stream().anyMatch(tag ->
                Locale.forLanguageTag(tag).stripExtensions()
                        .equals(li.getLocale().stripExtensions()))) {
            return true;
        }
        if (li.mIsPseudo) return false;
        if (translatedOnly && !li.isTranslated()) return true;
        if (li.getParent() != null) return false;
        return true;
    }

    private static int getLocaleTier(LocaleInfo parent) {
        if (parent == null) {
            return TIER_LANGUAGE;
        } else if (parent.getLocale().getCountry().isEmpty()) {
            return TIER_REGION;
        } else {
            return TIER_NUMBERING;
        }
    }

    /**
     * Returns a list of locales for language or region selection.
     *
     * If the parent is null, then it is the language list.
     *
     * If it is not null, then the list will contain all the locales that belong to that parent.
     * Example: if the parent is "ar", then the region list will contain all Arabic locales.
     * (this is not language based, but language-script, so that it works for zh-Hant and so on.)
     *
     * If it is not null and has country, then the list will contain all locales with that parent's
     * language and country, i.e. containing alternate numbering systems.
     *
     * Example: if the parent is "ff-Adlm-BF", then the numbering list will contain all
     * Fula (Adlam, Burkina Faso) i.e. "ff-Adlm-BF" and "ff-Adlm-BF-u-nu-latn"
     */
    @UnsupportedAppUsage
    public static Set<LocaleInfo> getLevelLocales(Context context, Set<String> ignorables,
            LocaleInfo parent, boolean translatedOnly) {
        return getLevelLocales(context, ignorables, parent, translatedOnly, null);
    }

    /**
     * @param explicitLocales Indicates only the locales within this list should be shown in the
     *                       locale picker.
     *
     * Returns a list of locales for language or region selection.
     * If the parent is null, then it is the language list.
     * If it is not null, then the list will contain all the locales that belong to that parent.
     * Example: if the parent is "ar", then the region list will contain all Arabic locales.
     * (this is not language based, but language-script, so that it works for zh-Hant and so on.
     */
    public static Set<LocaleInfo> getLevelLocales(Context context, Set<String> ignorables,
            LocaleInfo parent, boolean translatedOnly, LocaleList explicitLocales) {
        if (context != null) {
            fillCache(context);
        }
        HashMap<String, LocaleInfo> supportedLcoaleInfos =
                explicitLocales == null
                        ? sLocaleCache
                        : convertExplicitLocales(explicitLocales, sLocaleCache.values());
        return getTierLocales(ignorables, parent, translatedOnly, supportedLcoaleInfos);
    }

    private static Set<LocaleInfo> getTierLocales(
            Set<String> ignorables,
            LocaleInfo parent,
            boolean translatedOnly,
            HashMap<String, LocaleInfo> supportedLocaleInfos) {

        boolean hasTargetParent = parent != null;
        String parentId = hasTargetParent ? parent.getId() : null;
        HashSet<LocaleInfo> result = new HashSet<>();
        for (LocaleStore.LocaleInfo li : supportedLocaleInfos.values()) {
            if (isShallIgnore(ignorables, li, translatedOnly)) {
                continue;
            }
            switch(getLocaleTier(parent)) {
                case TIER_LANGUAGE:
                    if (li.isSuggestionOfType(LocaleInfo.SUGGESTION_TYPE_SIM)) {
                        result.add(li);
                    } else {
                        Locale locale = li.getParent();
                        LocaleInfo localeInfo = getLocaleInfo(locale, supportedLocaleInfos);
                        addLocaleInfoToMap(locale, localeInfo, supportedLocaleInfos);
                        result.add(localeInfo);
                    }
                    break;
                case TIER_REGION:
                    if (parentId.equals(li.getParent().toLanguageTag())) {
                        Locale locale = li.getLocale().stripExtensions();
                        LocaleInfo localeInfo = getLocaleInfo(locale, supportedLocaleInfos);
                        addLocaleInfoToMap(locale, localeInfo, supportedLocaleInfos);
                        result.add(localeInfo);
                    }
                    break;
                case TIER_NUMBERING:
                    if (parent.getLocale().stripExtensions()
                            .equals(li.getLocale().stripExtensions())) {
                        result.add(li);
                    }
                    break;
            }
        }
        return result;
    }

    /** Converts string array of explicit locales to HashMap */
    public static HashMap<String, LocaleInfo> convertExplicitLocales(
            LocaleList explicitLocales, Collection<LocaleInfo> localeinfo) {
        // Trys to find the matched locale within android supported locales. If there is no matched
        // locale, it will still keep the unsupported lcoale in list.
        // Note: This currently does not support unicode extension check.
        LocaleList localeList = matchLocaleFromSupportedLocaleList(
                explicitLocales, localeinfo);

        HashMap<String, LocaleInfo> localeInfos = new HashMap<>();
        for (int i = 0; i < localeList.size(); i++) {
            Locale locale = localeList.get(i);
            if (locale.toString().isEmpty()) {
                throw new IllformedLocaleException("Bad locale entry");
            }

            LocaleInfo li = new LocaleInfo(locale);
            if (localeInfos.containsKey(li.getId())) {
                continue;
            }
            localeInfos.put(li.getId(), li);
            Locale parent = li.getParent();
            if (parent != null) {
                String parentId = parent.toLanguageTag();
                if (!localeInfos.containsKey(parentId)) {
                    localeInfos.put(parentId, new LocaleInfo(parent));
                }
            }
        }
        return localeInfos;
    }

    private static LocaleList matchLocaleFromSupportedLocaleList(
            LocaleList explicitLocales, Collection<LocaleInfo> localeInfos) {
        if (localeInfos == null) {
            return explicitLocales;
        }
        //TODO: Adds a function for unicode extension if needed.
        Locale[] resultLocales = new Locale[explicitLocales.size()];
        for (int i = 0; i < explicitLocales.size(); i++) {
            Locale locale = explicitLocales.get(i);
            if (!TextUtils.isEmpty(locale.getCountry())) {
                for (LocaleInfo localeInfo :localeInfos) {
                    if (LocaleList.matchesLanguageAndScript(locale, localeInfo.getLocale())
                            && TextUtils.equals(locale.getCountry(),
                            localeInfo.getLocale().getCountry())) {
                        resultLocales[i] = localeInfo.getLocale();
                        break;
                    }
                }
            }
            if (resultLocales[i] == null) {
                resultLocales[i] = locale;
            }
        }
        return new LocaleList(resultLocales);
    }

    @UnsupportedAppUsage
    public static LocaleInfo getLocaleInfo(Locale locale) {
        LocaleInfo localeInfo = getLocaleInfo(locale, sLocaleCache);
        addLocaleInfoToMap(locale, localeInfo, sLocaleCache);
        return localeInfo;
    }

    private static LocaleInfo getLocaleInfo(
            Locale locale, HashMap<String, LocaleInfo> localeInfos) {
        String id = locale.toLanguageTag();
        LocaleInfo result;
        if (!localeInfos.containsKey(id)) {
            // Locale preferences can modify the language tag to current system languages, so we
            // need to check the input locale without extra u extension except numbering system.
            Locale filteredLocale = getLocaleWithOnlyNumberingSystem(locale);
            if (localeInfos.containsKey(filteredLocale.toLanguageTag())) {
                result = new LocaleInfo(locale);
                LocaleInfo localeInfo = localeInfos.get(filteredLocale.toLanguageTag());
                // This locale is included in supported locales, so follow the settings
                // of supported locales.
                result.mIsPseudo = localeInfo.mIsPseudo;
                result.mIsTranslated = localeInfo.mIsTranslated;
                result.mHasNumberingSystems = localeInfo.mHasNumberingSystems;
                result.mSuggestionFlags = localeInfo.mSuggestionFlags;
                return result;
            }
            result = new LocaleInfo(locale);
        } else {
            result = localeInfos.get(id);
        }
        return result;
    }

    private static Locale getLocaleWithOnlyNumberingSystem(Locale locale) {
        return new Locale.Builder()
                .setLocale(locale.stripExtensions())
                .setUnicodeLocaleKeyword("nu", locale.getUnicodeLocaleType("nu"))
                .build();
    }

    private static void addLocaleInfoToMap(Locale locale, LocaleInfo localeInfo,
            HashMap<String, LocaleInfo> map) {
        if (!map.containsKey(locale.toLanguageTag())) {
            Locale localeWithNumberingSystem = getLocaleWithOnlyNumberingSystem(locale);
            if (!map.containsKey(localeWithNumberingSystem.toLanguageTag())) {
                map.put(locale.toLanguageTag(), localeInfo);
            }
        }
    }

    /**
     * API for testing.
     */
    @UnsupportedAppUsage
    @VisibleForTesting
    public static LocaleInfo fromLocale(Locale locale) {
        return new LocaleInfo(locale);
    }
}
