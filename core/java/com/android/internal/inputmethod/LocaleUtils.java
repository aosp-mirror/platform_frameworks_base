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

package com.android.internal.inputmethod;

import com.android.internal.annotations.VisibleForTesting;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.LocaleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LocaleUtils {

    @VisibleForTesting
    public interface LocaleExtractor<T> {
        @Nullable
        Locale get(@Nullable T source);
    }

    @Nullable
    private static String getLanguage(@Nullable Locale locale) {
        if (locale == null) {
            return null;
        }
        return locale.getLanguage();
    }

    /**
     * Filters the given items based on language preferences.
     *
     * <p>For each language found in {@code preferredLanguages}, this method tries to copy at most
     * one best-match item from {@code source} to {@code dest}.  For example, if
     * {@code "en-GB", "ja", "en-AU", "fr-CA", "en-IN"} is specified to {@code preferredLanguages},
     * this method tries to copy at most one English locale, at most one Japanese, and at most one
     * French locale from {@code source} to {@code dest}.  Here the best matching English locale
     * will be searched from {@code source} as follows.
     * <ol>
     *     <li>The first instance in {@code sources} that exactly matches {@code "en-GB"}</li>
     *     <li>The first instance in {@code sources} that exactly matches {@code "en-AU"}</li>
     *     <li>The first instance in {@code sources} that exactly matches {@code "en-IN"}</li>
     *     <li>The first instance in {@code sources} that partially matches {@code "en"}</li>
     * </ol>
     * <p>Then this method iterates the same algorithm for Japanese then French.</p>
     *
     * @param sources Source items to be filtered.
     * @param extractor Type converter from the source items to {@link Locale} object.
     * @param preferredLanguages Ordered list of locales with which the input items will be
     * filtered.
     * @param dest Destination into which the filtered items will be added.
     * @param <T> Type of the data items.
     */
    @VisibleForTesting
    public static <T> void filterByLanguage(
            @NonNull List<T> sources,
            @NonNull LocaleExtractor<T> extractor,
            @NonNull LocaleList preferredLanguages,
            @NonNull ArrayList<T> dest) {
        final Locale[] availableLocales = new Locale[sources.size()];
        for (int i = 0; i < availableLocales.length; ++i) {
            availableLocales[i] = extractor.get(sources.get(i));
        }
        final Locale[] sortedPreferredLanguages = new Locale[preferredLanguages.size()];
        if (sortedPreferredLanguages.length > 0) {
            int nextIndex = 0;
            final int N = preferredLanguages.size();
            languageLoop:
            for (int i = 0; i < N; ++i) {
                final String language = getLanguage(preferredLanguages.get(i));
                for (int j = 0; j < nextIndex; ++j) {
                    if (TextUtils.equals(getLanguage(sortedPreferredLanguages[j]), language)) {
                        continue languageLoop;
                    }
                }
                for (int j = i; j < N; ++j) {
                    final Locale locale = preferredLanguages.get(j);
                    if (TextUtils.equals(language, getLanguage(locale))) {
                        sortedPreferredLanguages[nextIndex] = locale;
                        ++nextIndex;
                    }
                }
            }
        }


        for (int languageIndex = 0; languageIndex < sortedPreferredLanguages.length;) {
            // Finding the range.
            final String language = getLanguage(sortedPreferredLanguages[languageIndex]);
            int nextLanguageIndex = languageIndex;
            for (; nextLanguageIndex < sortedPreferredLanguages.length; ++nextLanguageIndex) {
                final Locale locale = sortedPreferredLanguages[nextLanguageIndex];
                if (!TextUtils.equals(getLanguage(locale), language)) {
                    break;
                }
            }

            // Check exact match
            boolean found = false;
            for (int i = languageIndex; !found && i < nextLanguageIndex; ++i) {
                final Locale locale = sortedPreferredLanguages[i];
                for (int j = 0; j < availableLocales.length; ++j) {
                    if (!Objects.equals(locale, availableLocales[j])) {
                        continue;
                    }
                    dest.add(sources.get(j));
                    found = true;
                    break;
                }
            }

            if (!found) {
                // No exact match.  Use language match.
                for (int j = 0; j < availableLocales.length; ++j) {
                    if (!TextUtils.equals(language, getLanguage(availableLocales[j]))) {
                        continue;
                    }
                    dest.add(sources.get(j));
                    break;
                }
            }
            languageIndex = nextLanguageIndex;
        }
    }
}