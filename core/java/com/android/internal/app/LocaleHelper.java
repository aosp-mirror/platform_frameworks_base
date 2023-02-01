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

import android.annotation.IntRange;
import android.compat.annotation.UnsupportedAppUsage;
import android.icu.text.CaseMap;
import android.icu.text.ListFormatter;
import android.icu.util.ULocale;
import android.os.LocaleList;
import android.text.TextUtils;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * This class implements some handy methods to process with locales.
 */
public class LocaleHelper {

    /**
     * Sentence-case (first character uppercased).
     *
     * @param str the string to sentence-case.
     * @param locale the locale used for the case conversion.
     * @return the string converted to sentence-case.
     */
    public static String toSentenceCase(String str, Locale locale) {
        // Titlecases only the character at index 0, don't touch anything else
        return CaseMap.toTitle().wholeString().noLowercase().apply(locale, null, str);
    }

    /**
     * Normalizes a string for locale name search. Does case conversion for now,
     * but might do more in the future.
     *
     * <p>Warning: it is only intended to be used in searches by the locale picker.
     * Don't use it for other things, it is very limited.</p>
     *
     * @param str the string to normalize
     * @param locale the locale that might be used for certain operations (i.e. case conversion)
     * @return the string normalized for search
     */
    @UnsupportedAppUsage
    public static String normalizeForSearch(String str, Locale locale) {
        // TODO: tbd if it needs to be smarter (real normalization, remove accents, etc.)
        // If needed we might use case folding and ICU/CLDR's collation-based loose searching.
        // TODO: decide what should the locale be, the default locale, or the locale of the string.
        // Uppercase is better than lowercase because of things like sharp S, Greek sigma, ...
        return str.toUpperCase();
    }

    // For some locales we want to use a "dialect" form, for instance
    // "Dari" instead of "Persian (Afghanistan)", or "Moldavian" instead of "Romanian (Moldova)"
    private static boolean shouldUseDialectName(Locale locale) {
        final String lang = locale.getLanguage();
        return "fa".equals(lang) // Persian
                || "ro".equals(lang) // Romanian
                || "zh".equals(lang); // Chinese
    }

    /**
     * Returns the locale localized for display in the provided locale.
     *
     * @param locale the locale whose name is to be displayed.
     * @param displayLocale the locale in which to display the name.
     * @param sentenceCase true if the result should be sentence-cased
     * @return the localized name of the locale.
     */
    @UnsupportedAppUsage
    public static String getDisplayName(Locale locale, Locale displayLocale, boolean sentenceCase) {
        final ULocale displayULocale = ULocale.forLocale(displayLocale);
        String result = shouldUseDialectName(locale)
                ? ULocale.getDisplayNameWithDialect(locale.toLanguageTag(), displayULocale)
                : ULocale.getDisplayName(locale.toLanguageTag(), displayULocale);
        return sentenceCase ? toSentenceCase(result, displayLocale) : result;
    }

    /**
     * Returns the locale localized for display in the default locale.
     *
     * @param locale the locale whose name is to be displayed.
     * @param sentenceCase true if the result should be sentence-cased
     * @return the localized name of the locale.
     */
    public static String getDisplayName(Locale locale, boolean sentenceCase) {
        return getDisplayName(locale, Locale.getDefault(), sentenceCase);
    }

    /**
     * Returns a locale's country localized for display in the provided locale.
     *
     * @param locale the locale whose country will be displayed.
     * @param displayLocale the locale in which to display the name.
     * @return the localized country name.
     */
    @UnsupportedAppUsage
    public static String getDisplayCountry(Locale locale, Locale displayLocale) {
        final String languageTag = locale.toLanguageTag();
        final ULocale uDisplayLocale = ULocale.forLocale(displayLocale);
        final String country = ULocale.getDisplayCountry(languageTag, uDisplayLocale);
        final String numberingSystem = locale.getUnicodeLocaleType("nu");
        if (numberingSystem != null) {
            return String.format("%s (%s)", country,
                    ULocale.getDisplayKeywordValue(languageTag, "numbers", uDisplayLocale));
        } else {
            return country;
        }
    }

    /**
     * Returns a locale's country localized for display in the default locale.
     *
     * @param locale the locale whose country will be displayed.
     * @return the localized country name.
     */
    public static String getDisplayCountry(Locale locale) {
        return ULocale.getDisplayCountry(locale.toLanguageTag(), ULocale.getDefault());
    }

    /**
     * Returns the locale list localized for display in the provided locale.
     *
     * @param locales the list of locales whose names is to be displayed.
     * @param displayLocale the locale in which to display the names.
     *                      If this is null, it will use the default locale.
     * @param maxLocales maximum number of locales to display. Generates ellipsis after that.
     * @return the locale aware list of locale names
     */
    public static String getDisplayLocaleList(
            LocaleList locales, Locale displayLocale, @IntRange(from=1) int maxLocales) {

        final Locale dispLocale = displayLocale == null ? Locale.getDefault() : displayLocale;

        final boolean ellipsisNeeded = locales.size() > maxLocales;
        final int localeCount, listCount;
        if (ellipsisNeeded) {
            localeCount = maxLocales;
            listCount = maxLocales + 1;  // One extra slot for the ellipsis
        } else {
            listCount = localeCount = locales.size();
        }
        final String[] localeNames = new String[listCount];
        for (int i = 0; i < localeCount; i++) {
            localeNames[i] = LocaleHelper.getDisplayName(locales.get(i), dispLocale, false);
        }
        if (ellipsisNeeded) {
            // Theoretically, we want to extract this from ICU's Resource Bundle for
            // "Ellipsis/final", which seems to have different strings than the normal ellipsis for
            // Hong Kong Traditional Chinese (zh_Hant_HK) and Dzongkha (dz). But that has two
            // problems: it's expensive to extract it, and in case the output string becomes
            // automatically ellipsized, it can result in weird output.
            localeNames[maxLocales] = TextUtils.getEllipsisString(TextUtils.TruncateAt.END);
        }

        ListFormatter lfn = ListFormatter.getInstance(dispLocale);
        return lfn.format((Object[]) localeNames);
    }

    /**
     * Adds the likely subtags for a provided locale ID.
     *
     * @param locale the locale to maximize.
     * @return the maximized Locale instance.
     */
    public static Locale addLikelySubtags(Locale locale) {
        return ULocale.addLikelySubtags(ULocale.forLocale(locale)).toLocale();
    }

    /**
     * Locale-sensitive comparison for LocaleInfo.
     *
     * <p>It uses the label, leaving the decision on what to put there to the LocaleInfo.
     * For instance fr-CA can be shown as "français" as a generic label in the language selection,
     * or "français (Canada)" if it is a suggestion, or "Canada" in the country selection.</p>
     *
     * <p>Gives priority to suggested locales (to sort them at the top).</p>
     */
    public static final class LocaleInfoComparator implements Comparator<LocaleStore.LocaleInfo> {
        private final Collator mCollator;
        private final boolean mCountryMode;
        private static final String PREFIX_ARABIC = "\u0627\u0644"; // ALEF-LAM, ال

        /**
         * Constructor.
         *
         * @param sortLocale the locale to be used for sorting.
         */
        @UnsupportedAppUsage
        public LocaleInfoComparator(Locale sortLocale, boolean countryMode) {
            mCollator = Collator.getInstance(sortLocale);
            mCountryMode = countryMode;
        }

        /*
         * The Arabic collation should ignore Alef-Lam at the beginning (b/26277596)
         *
         * We look at the label's locale, not the current system locale.
         * This is because the name of the Arabic language itself is in Arabic,
         * and starts with Alef-Lam, no matter what the system locale is.
         */
        private String removePrefixForCompare(Locale locale, String str) {
            if ("ar".equals(locale.getLanguage()) && str.startsWith(PREFIX_ARABIC)) {
                return str.substring(PREFIX_ARABIC.length());
            }
            return str;
        }

        /**
         * Compares its two arguments for order.
         *
         * @param lhs   the first object to be compared
         * @param rhs   the second object to be compared
         * @return  a negative integer, zero, or a positive integer as the first
         *          argument is less than, equal to, or greater than the second.
         */
        @UnsupportedAppUsage
        @Override
        public int compare(LocaleStore.LocaleInfo lhs, LocaleStore.LocaleInfo rhs) {
            // We don't care about the various suggestion types, just "suggested" (!= 0)
            // and "all others" (== 0)
            if (lhs.isAppCurrentLocale() || rhs.isAppCurrentLocale()) {
                return lhs.isAppCurrentLocale() ? -1 : 1;
            } else if (lhs.isSystemLocale() || rhs.isSystemLocale()) {
                    return lhs.isSystemLocale() ? -1 : 1;
            } else if (lhs.isSuggested() == rhs.isSuggested()) {
                // They are in the same "bucket" (suggested / others), so we compare the text
                return mCollator.compare(
                        removePrefixForCompare(lhs.getLocale(), lhs.getLabel(mCountryMode)),
                        removePrefixForCompare(rhs.getLocale(), rhs.getLabel(mCountryMode)));
            } else {
                // One locale is suggested and one is not, so we put them in different "buckets"
                return lhs.isSuggested() ? -1 : 1;
            }
        }
    }
}
