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

package com.android.server.inputmethod;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.icu.util.ULocale;
import android.os.LocaleList;
import android.text.TextUtils;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class LocaleUtils {
    public interface LocaleExtractor<T> {
        @Nullable
        Locale get(@Nullable T source);
    }

    /**
     * Calculates a matching score for the single desired locale.
     *
     * @see LocaleUtils#filterByLanguage(List, LocaleExtractor, LocaleList, ArrayList)
     *
     * @param supported The locale supported by IME subtype.
     * @param desired The locale preferred by user.
     * @return A score based on the locale matching for the default subtype enabling.
     */
    @IntRange(from = 1, to = 4)
    private static byte calculateMatchingSubScore(@NonNull final ULocale supported,
            @NonNull final ULocale desired) {
        // Assuming supported/desired is fully expanded.
        if (supported.equals(desired)) {
            return 4;  // Exact match.
        }

        // addLikelySubtags is a maximization process as per
        // https://www.unicode.org/reports/tr35/#Likely_Subtags
        ULocale maxDesired = ULocale.addLikelySubtags(desired);

        // Skip language matching since it was already done in calculateMatchingScore.

        final String supportedScript = supported.getScript();
        if (supportedScript.isEmpty() || !supportedScript.equals(maxDesired.getScript())) {
            // TODO: Need subscript matching. For example, Hanb should match with Bopo.
            return 1;
        }

        final String supportedCountry = supported.getCountry();
        if (supportedCountry.isEmpty() || !supportedCountry.equals(maxDesired.getCountry())) {
            return 2;
        }

        // Ignore others e.g. variants, extensions.

        // Since addLikelySubtags can canonicalize subtags, e.g. the deprecated country codes
        // an locale with an identical script and country before addLikelySubtags is in favour,
        // and a score of 4 is returned.
        String desiredScript = desired.getScript();
        String desiredCountry = desired.getCountry();
        if ((desiredScript.isEmpty() || desiredScript.equals(maxDesired.getScript()))
                && (desiredCountry.isEmpty() || desiredCountry.equals(maxDesired.getCountry()))) {
            return 4;
        } else {
            return 3;
        }
    }

    private static final class ScoreEntry implements Comparable<ScoreEntry> {
        public int mIndex = -1;
        @NonNull public final byte[] mScore;  // matching score of the i-th system languages.

        ScoreEntry(@NonNull byte[] score, int index) {
            mScore = new byte[score.length];
            set(score, index);
        }

        private void set(@NonNull byte[] score, int index) {
            for (int i = 0; i < mScore.length; ++i) {
                mScore[i] = score[i];
            }
            mIndex = index;
        }

        /**
         * Update score and index if the given score is better than this.
         */
        public void updateIfBetter(@NonNull byte[] score, int index) {
            if (compare(mScore, score) == -1) {  // mScore < score
                set(score, index);
            }
        }

        /**
         * Provides comaprison for bytes[].
         *
         * <p> Comparison does as follows. If the first value of {@code left} is larger than the
         * first value of {@code right}, {@code left} is large than {@code right}.  If the first
         * value of {@code left} is less than the first value of {@code right}, {@code left} is less
         * than {@code right}. If the first value of {@code left} and the first value of
         * {@code right} is equal, do the same comparison to the next value. Finally if all values
         * in {@code left} and {@code right} are equal, {@code left} and {@code right} is equal.</p>
         *
         * @param left The length must be equal to {@code right}.
         * @param right The length must be equal to {@code left}.
         * @return 1 if {@code left} is larger than {@code right}. -1 if {@code left} is less than
         * {@code right}. 0 if {@code left} and {@code right} is equal.
         */
        @IntRange(from = -1, to = 1)
        private static int compare(@NonNull byte[] left, @NonNull byte[] right) {
            for (int i = 0; i < left.length; ++i) {
                if (left[i] > right[i]) {
                    return 1;
                } else if (left[i] < right[i]) {
                    return -1;
                }
            }
            return 0;
        }

        @Override
        public int compareTo(final ScoreEntry other) {
            return -1 * compare(mScore, other.mScore);  // Order by descending order.
        }
    }

    /**
     * Filters the given items based on language preferences.
     *
     * <p>For each language found in {@code preferredLocales}, this method tries to copy at most
     * one best-match item from {@code source} to {@code dest}.  For example, if
     * {@code "en-GB", "ja", "en-AU", "fr-CA", "en-IN"} is specified to {@code preferredLocales},
     * this method tries to copy at most one English locale, at most one Japanese, and at most one
     * French locale from {@code source} to {@code dest}.  Here the best matching English locale
     * will be searched from {@code source} based on matching score. For the score design, see
     * {@link LocaleUtils#calculateMatchingSubScore(ULocale, ULocale)}</p>
     *
     * @param sources Source items to be filtered.
     * @param extractor Type converter from the source items to {@link Locale} object.
     * @param preferredLocales Ordered list of locales with which the input items will be
     * filtered.
     * @param dest Destination into which the filtered items will be added.
     * @param <T> Type of the data items.
     */
    public static <T> void filterByLanguage(
            @NonNull List<T> sources,
            @NonNull LocaleExtractor<T> extractor,
            @NonNull LocaleList preferredLocales,
            @NonNull ArrayList<T> dest) {
        if (preferredLocales.isEmpty()) {
            return;
        }

        final int numPreferredLocales = preferredLocales.size();
        final ArrayMap<String, ScoreEntry> scoreboard = new ArrayMap<>();
        final byte[] score = new byte[numPreferredLocales];
        final ULocale[] preferredULocaleCache = new ULocale[numPreferredLocales];

        final int sourceSize = sources.size();
        for (int i = 0; i < sourceSize; ++i) {
            final Locale locale = extractor.get(sources.get(i));
            if (locale == null) {
                continue;
            }

            boolean canSkip = true;
            for (int j = 0; j < numPreferredLocales; ++j) {
                final Locale preferredLocale = preferredLocales.get(j);
                if (!TextUtils.equals(locale.getLanguage(), preferredLocale.getLanguage())) {
                    score[j] = 0;
                    continue;
                }
                if (preferredULocaleCache[j] == null) {
                    preferredULocaleCache[j] = ULocale.addLikelySubtags(
                            ULocale.forLocale(preferredLocale));
                }
                score[j] = calculateMatchingSubScore(
                        preferredULocaleCache[j], ULocale.forLocale(locale));
                if (canSkip && score[j] != 0) {
                    canSkip = false;
                }
            }
            if (canSkip) {
                continue;
            }

            final String lang = locale.getLanguage();
            final ScoreEntry bestScore = scoreboard.get(lang);
            if (bestScore == null) {
                scoreboard.put(lang, new ScoreEntry(score, i));
            } else {
                bestScore.updateIfBetter(score, i);
            }
        }

        final int numEntries = scoreboard.size();
        final ScoreEntry[] result = new ScoreEntry[numEntries];
        for (int i = 0; i < numEntries; ++i) {
            result[i] = scoreboard.valueAt(i);
        }
        Arrays.sort(result);
        for (final ScoreEntry entry : result) {
            dest.add(sources.get(entry.mIndex));
        }
    }

    /**
     * Returns the language component of a given locale string.
     * TODO(b/321064051): Switch to {@link
     * com.android.internal.inputmethod.SubtypeLocaleUtils#constructLocaleFromString(String)}
     */
    static String getLanguageFromLocaleString(String locale) {
        final int idx = locale.indexOf('_');
        if (idx < 0) {
            return locale;
        } else {
            return locale.substring(0, idx);
        }
    }

    static Locale getSystemLocaleFromContext(Context context) {
        try {
            return context.getResources().getConfiguration().locale;
        } catch (Resources.NotFoundException ex) {
            return null;
        }
    }
}
