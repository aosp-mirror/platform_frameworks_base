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

import android.content.Context;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Set;

public class LocaleStore {
    private static final HashMap<String, LocaleInfo> sLocaleCache = new HashMap<>();
    private static boolean sFullyInitialized = false;

    public static class LocaleInfo {
        private static final int SUGGESTION_TYPE_NONE = 0;
        private static final int SUGGESTION_TYPE_SIM = 1 << 0;
        private static final int SUGGESTION_TYPE_CFG = 1 << 1;

        private final Locale mLocale;
        private final Locale mParent;
        private final String mId;
        private boolean mIsTranslated;
        private boolean mIsPseudo;
        private boolean mIsChecked; // Used by the LocaleListEditor to mark entries for deletion
        // Combination of flags for various reasons to show a locale as a suggestion.
        // Can be SIM, location, etc.
        private int mSuggestionFlags;

        private String mFullNameNative;
        private String mFullCountryNameNative;
        private String mLangScriptKey;

        private LocaleInfo(Locale locale) {
            this.mLocale = locale;
            this.mId = locale.toLanguageTag();
            this.mParent = getParent(locale);
            this.mIsChecked = false;
            this.mSuggestionFlags = SUGGESTION_TYPE_NONE;
            this.mIsTranslated = false;
            this.mIsPseudo = false;
        }

        private LocaleInfo(String localeId) {
            this(Locale.forLanguageTag(localeId));
        }

        private static Locale getParent(Locale locale) {
            if (locale.getCountry().isEmpty()) {
                return null;
            }
            return new Locale.Builder()
                    .setLocale(locale).setRegion("")
                    .build();
        }

        @Override
        public String toString() {
            return mId;
        }

        public Locale getLocale() {
            return mLocale;
        }

        public Locale getParent() {
            return mParent;
        }

        public String getId() {
            return mId;
        }

        public boolean isTranslated() {
            return mIsTranslated;
        }

        public void setTranslated(boolean isTranslated) {
            mIsTranslated = isTranslated;
        }

        /* package */ boolean isSuggested() {
            if (!mIsTranslated) { // Never suggest an untranslated locale
                return false;
            }
            return mSuggestionFlags != SUGGESTION_TYPE_NONE;
        }

        private boolean isSuggestionOfType(int suggestionMask) {
            if (!mIsTranslated) { // Never suggest an untranslated locale
                return false;
            }
            return (mSuggestionFlags & suggestionMask) == suggestionMask;
        }

        public String getFullNameNative() {
            if (mFullNameNative == null) {
                mFullNameNative =
                        LocaleHelper.getDisplayName(mLocale, mLocale, true /* sentence case */);
            }
            return mFullNameNative;
        }

        String getFullCountryNameNative() {
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
        public String getFullNameInUiLanguage() {
            // We don't cache the UI name because the default locale keeps changing
            return LocaleHelper.getDisplayName(mLocale, true /* sentence case */);
        }

        private String getLangScriptKey() {
            if (mLangScriptKey == null) {
                Locale parentWithScript = getParent(LocaleHelper.addLikelySubtags(mLocale));
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
    }

    private static Set<String> getSimCountries(Context context) {
        Set<String> result = new HashSet<>();

        TelephonyManager tm = TelephonyManager.from(context);

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

    public static void fillCache(Context context) {
        if (sFullyInitialized) {
            return;
        }

        Set<String> simCountries = getSimCountries(context);

        for (String localeId : LocalePicker.getSupportedLocales(context)) {
            if (localeId.isEmpty()) {
                throw new IllformedLocaleException("Bad locale entry in locale_config.xml");
            }
            LocaleInfo li = new LocaleInfo(localeId);
            if (simCountries.contains(li.getLocale().getCountry())) {
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            }
            sLocaleCache.put(li.getId(), li);
            final Locale parent = li.getParent();
            if (parent != null) {
                String parentId = parent.toLanguageTag();
                if (!sLocaleCache.containsKey(parentId)) {
                    sLocaleCache.put(parentId, new LocaleInfo(parent));
                }
            }
        }

        boolean isInDeveloperMode = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        for (String localeId : LocalePicker.getPseudoLocales()) {
            LocaleInfo li = getLocaleInfo(Locale.forLanguageTag(localeId));
            if (isInDeveloperMode) {
                li.setTranslated(true);
                li.mIsPseudo = true;
                li.mSuggestionFlags |= LocaleInfo.SUGGESTION_TYPE_SIM;
            } else {
                sLocaleCache.remove(li.getId());
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

        // Serbian in Latin script is only partially localized in N.
        localizedLocales.remove("sr-Latn");

        for (LocaleInfo li : sLocaleCache.values()) {
            li.setTranslated(localizedLocales.contains(li.getLangScriptKey()));
        }

        addSuggestedLocalesForRegion(Locale.getDefault());

        sFullyInitialized = true;
    }

    private static int getLevel(Set<String> ignorables, LocaleInfo li, boolean translatedOnly) {
        if (ignorables.contains(li.getId())) return 0;
        if (li.mIsPseudo) return 2;
        if (translatedOnly && !li.isTranslated()) return 0;
        if (li.getParent() != null) return 2;
        return 0;
    }

    /**
     * Returns a list of locales for language or region selection.
     * If the parent is null, then it is the language list.
     * If it is not null, then the list will contain all the locales that belong to that parent.
     * Example: if the parent is "ar", then the region list will contain all Arabic locales.
     * (this is not language based, but language-script, so that it works for zh-Hant and so on.
     */
    public static Set<LocaleInfo> getLevelLocales(Context context, Set<String> ignorables,
            LocaleInfo parent, boolean translatedOnly) {
        fillCache(context);
        String parentId = parent == null ? null : parent.getId();

        HashSet<LocaleInfo> result = new HashSet<>();
        for (LocaleStore.LocaleInfo li : sLocaleCache.values()) {
            int level = getLevel(ignorables, li, translatedOnly);
            if (level == 2) {
                if (parent != null) { // region selection
                    if (parentId.equals(li.getParent().toLanguageTag())) {
                        result.add(li);
                    }
                } else { // language selection
                    if (li.isSuggestionOfType(LocaleInfo.SUGGESTION_TYPE_SIM)) {
                        result.add(li);
                    } else {
                        result.add(getLocaleInfo(li.getParent()));
                    }
                }
            }
        }
        return result;
    }

    public static LocaleInfo getLocaleInfo(Locale locale) {
        String id = locale.toLanguageTag();
        LocaleInfo result;
        if (!sLocaleCache.containsKey(id)) {
            result = new LocaleInfo(locale);
            sLocaleCache.put(id, result);
        } else {
            result = sLocaleCache.get(id);
        }
        return result;
    }
}
