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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.util.ULocale;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class LocaleUtils {

    @VisibleForTesting
    public interface LocaleExtractor<T> {
        @Nullable
        Locale get(@Nullable T source);
    }

    /**
     * Calculates a matching score for the single desired locale.
     *
     * @see LocaleUtils#calculateMatchingScore(ULocale, LocaleList, byte[])
     *
     * @param supported The locale supported by IME subtype.
     * @param desired The locale preferred by user.
     * @return A score based on the locale matching for the default subtype enabling.
     */
    @IntRange(from=1, to=3)
    private static byte calculateMatchingSubScore(@NonNull final ULocale supported,
            @NonNull final ULocale desired) {
        // Assuming supported/desired is fully expanded.
        if (supported.equals(desired)) {
            return 3;  // Exact match.
        }

        // Skip language matching since it was already done in calculateMatchingScore.

        final String supportedScript = supported.getScript();
        if (supportedScript.isEmpty() || !supportedScript.equals(desired.getScript())) {
            // TODO: Need subscript matching. For example, Hanb should match with Bopo.
            return 1;
        }

        final String supportedCountry = supported.getCountry();
        if (supportedCountry.isEmpty() || !supportedCountry.equals(desired.getCountry())) {
            return 2;
        }

        // Ignore others e.g. variants, extensions.
        return 3;
    }

    /**
     * Calculates a matching score for the desired locale list.
     *
     * <p>The supported locale gets a matching score of 3 if all language, script and country of the
     * supported locale matches with the desired locale.  The supported locale gets a matching
     * score of 2 if the language and script of the supported locale matches with the desired
     * locale. The supported locale gets a matching score of 1 if only language of the supported
     * locale matches with the desired locale.  The supported locale gets a matching score of 0 if
     * the language of the supported locale doesn't match with the desired locale.</p>
     *
     * @param supported The locale supported by IME subtyle.
     * @param desired The locale list preferred by user. Typically system locale list.
     * @param out The output buffer to be stored the individual score for the desired language list.
     * The length of {@code out} must be same as the length of {@code desired} language list.
     * @return {@code false} if supported locale doesn't match with any desired locale list.
     * Otherwise {@code true}.
     */
    private static boolean calculateMatchingScore(@NonNull final ULocale supported,
            @NonNull final LocaleList desired, @NonNull byte[] out) {
        if (desired.isEmpty()) {
            return false;
        }

        boolean allZeros = true;
        final int N = desired.size();
        for (int i = 0; i < N; ++i) {
            final Locale locale = desired.get(i);

            if (!locale.getLanguage().equals(supported.getLanguage())) {
                // TODO: cache the result of addLikelySubtags if it is slow.
                out[i] = 0;
            } else {
                out[i] = calculateMatchingSubScore(
                        supported, ULocale.addLikelySubtags(ULocale.forLocale(locale)));
                if (allZeros && out[i] != 0) {
                    allZeros = false;
                }
            }
        }
        return !allZeros;
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
        @IntRange(from=-1, to=1)
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
     * <p>For each language found in {@code preferredLanguages}, this method tries to copy at most
     * one best-match item from {@code source} to {@code dest}.  For example, if
     * {@code "en-GB", "ja", "en-AU", "fr-CA", "en-IN"} is specified to {@code preferredLanguages},
     * this method tries to copy at most one English locale, at most one Japanese, and at most one
     * French locale from {@code source} to {@code dest}.  Here the best matching English locale
     * will be searched from {@code source} based on matching score. For the score design, see
     * {@link LocaleUtils#calculateMatchingScore(ULocale, LocaleList, byte[])}</p>
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
        final HashMap<String, ScoreEntry> scoreboard = new HashMap<>();
        final byte[] score = new byte[preferredLanguages.size()];

        final int sourceSize = sources.size();
        for (int i = 0; i < sourceSize; ++i) {
            final Locale locale = extractor.get(sources.get(i));
            if (locale == null ||
                    !calculateMatchingScore(ULocale.addLikelySubtags(ULocale.forLocale(locale)),
                            preferredLanguages, score)) {
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

        final ScoreEntry[] result = scoreboard.values().toArray(new ScoreEntry[scoreboard.size()]);
        Arrays.sort(result);
        for (final ScoreEntry entry : result) {
            dest.add(sources.get(entry.mIndex));
        }
    }
}
