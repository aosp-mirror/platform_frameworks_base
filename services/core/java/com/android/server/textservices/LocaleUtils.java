/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.textservices;

import android.annotation.Nullable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Provides {@code Locale} related utility methods for {@link TextServicesManagerService}.
 * <p>This class is intentionally package-private.  Utility methods here are tightly coupled with
 * implementation details in {@link TextServicesManagerService}.  Hence this class is not suitable
 * for other components to directly use.</p>
 */
final class LocaleUtils {
    /**
     * Returns a list of {@link Locale} in the order of appropriateness for the default spell
     * checker service.
     *
     * <p>If the system language is English, and the region is also explicitly specified in the
     * system locale, the following fallback order will be applied.</p>
     * <ul>
     * <li>(system-locale-language, system-locale-region, system-locale-variant) (if exists)</li>
     * <li>(system-locale-language, system-locale-region)</li>
     * <li>("en", "US")</li>
     * <li>("en", "GB")</li>
     * <li>("en")</li>
     * </ul>
     *
     * <p>If the system language is English, but no region is specified in the system locale,
     * the following fallback order will be applied.</p>
     * <ul>
     * <li>("en")</li>
     * <li>("en", "US")</li>
     * <li>("en", "GB")</li>
     * </ul>
     *
     * <p>If the system language is not English, the following fallback order will be applied.</p>
     * <ul>
     * <li>(system-locale-language, system-locale-region, system-locale-variant) (if exists)</li>
     * <li>(system-locale-language, system-locale-region) (if exists)</li>
     * <li>(system-locale-language) (if exists)</li>
     * <li>("en", "US")</li>
     * <li>("en", "GB")</li>
     * <li>("en")</li>
     * </ul>
     *
     * @param systemLocale the current system locale to be taken into consideration.
     * @return a list of {@link Locale}. The first one is considered to be most appropriate.
     */
    public static ArrayList<Locale> getSuitableLocalesForSpellChecker(
            @Nullable final Locale systemLocale) {
        final Locale systemLocaleLanguageCountryVariant;
        final Locale systemLocaleLanguageCountry;
        final Locale systemLocaleLanguage;
        if (systemLocale != null) {
            final String language = systemLocale.getLanguage();
            final boolean hasLanguage = !TextUtils.isEmpty(language);
            final String country = systemLocale.getCountry();
            final boolean hasCountry = !TextUtils.isEmpty(country);
            final String variant = systemLocale.getVariant();
            final boolean hasVariant = !TextUtils.isEmpty(variant);
            if (hasLanguage && hasCountry && hasVariant) {
                systemLocaleLanguageCountryVariant = new Locale(language, country, variant);
            } else {
                systemLocaleLanguageCountryVariant = null;
            }
            if (hasLanguage && hasCountry) {
                systemLocaleLanguageCountry = new Locale(language, country);
            } else {
                systemLocaleLanguageCountry = null;
            }
            if (hasLanguage) {
                systemLocaleLanguage = new Locale(language);
            } else {
                systemLocaleLanguage = null;
            }
        } else {
            systemLocaleLanguageCountryVariant = null;
            systemLocaleLanguageCountry = null;
            systemLocaleLanguage = null;
        }

        final ArrayList<Locale> locales = new ArrayList<>();
        if (systemLocaleLanguageCountryVariant != null) {
            locales.add(systemLocaleLanguageCountryVariant);
        }

        if (Locale.ENGLISH.equals(systemLocaleLanguage)) {
            if (systemLocaleLanguageCountry != null) {
                // If the system language is English, and the region is also explicitly specified,
                // following fallback order will be applied.
                // - systemLocaleLanguageCountry [if systemLocaleLanguageCountry is non-null]
                // - en_US [if systemLocaleLanguageCountry is non-null and not en_US]
                // - en_GB [if systemLocaleLanguageCountry is non-null and not en_GB]
                // - en
                if (systemLocaleLanguageCountry != null) {
                    locales.add(systemLocaleLanguageCountry);
                }
                if (!Locale.US.equals(systemLocaleLanguageCountry)) {
                    locales.add(Locale.US);
                }
                if (!Locale.UK.equals(systemLocaleLanguageCountry)) {
                    locales.add(Locale.UK);
                }
                locales.add(Locale.ENGLISH);
            } else {
                // If the system language is English, but no region is specified, following
                // fallback order will be applied.
                // - en
                // - en_US
                // - en_GB
                locales.add(Locale.ENGLISH);
                locales.add(Locale.US);
                locales.add(Locale.UK);
            }
        } else {
            // If the system language is not English, the fallback order will be
            // - systemLocaleLanguageCountry  [if non-null]
            // - systemLocaleLanguage  [if non-null]
            // - en_US
            // - en_GB
            // - en
            if (systemLocaleLanguageCountry != null) {
                locales.add(systemLocaleLanguageCountry);
            }
            if (systemLocaleLanguage != null) {
                locales.add(systemLocaleLanguage);
            }
            locales.add(Locale.US);
            locales.add(Locale.UK);
            locales.add(Locale.ENGLISH);
        }
        return locales;
    }
}
