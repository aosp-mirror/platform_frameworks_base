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
import android.util.LocaleList;

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
     * <p>See {@link LocaleUtils#calculateMatchingScore(ULocale, LocaleList, byte[])} for
     * details.</p>
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
     * <p>This function returns {@code false} if supported locale doesn't match with any desired
     * locale list.  Otherwise, this function returns {@code true}.</p>
     */
    private static boolean calculateMatchingScore(@NonNull final ULocale supported,
            @NonNull final LocaleList desired, @NonNull byte[] out) {
        if (desired.isEmpty()) {
            return false;
        }

        boolean allZeros = true;
        for (int i = 0; i < desired.size(); ++i) {
            final Locale loc = desired.get(i);

            if (!loc.getLanguage().equals(supported.getLanguage())) {
                // TODO: cache the result of addLikelySubtags if it is slow.
                out[i] = 0;
            } else {
                out[i] = calculateMatchingSubScore(
                        supported, ULocale.addLikelySubtags(ULocale.forLocale(loc)));
                if (allZeros && out[i] != 0) {
                    allZeros = false;
                }
            }
        }
        return !allZeros;
    }

    private static final class ScoreEntry implements Comparable<ScoreEntry> {
        public int index = -1;
        @NonNull public byte[] score;  // matching score of the i-th system languages.

        ScoreEntry(int capacity) {
            score = new byte[capacity];
        }

        /**
         * Update score and index if the given score is better than this.
         */
        public void updateIfBetter(@NonNull byte[] newScore, int newIndex) {
            if (isBetterThan(score) != 1) {
                return;
            }

            for (int i = 0; i < score.length; ++i) {
                score[i] = newScore[i];
            }
            index = newIndex;
        }

        /**
         * Determines given score is better than current.
         *
         * <p>Compares the matching score for the first priority locale. If the given score has
         * higher score than current score, returns 1.  If the current score has higher score than
         * given score, returns -1. Otherwise, do the same comparison for the next priority locale.
         * If given score and current score is same for the all system locale, returns 0.</p>
         */
        private int isBetterThan(@NonNull byte[] other) {
            for (int i = 0; i < score.length; ++i) {
                if (score[i] < other[i]) {
                    return 1;
                } else if (score[i] > other[i]) {
                    return -1;
                }
            }
            return 0;
        }

        @Override
        public int compareTo(ScoreEntry other) {
            return isBetterThan(score);
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

        for (int i = 0; i < sources.size(); ++i) {
            final Locale loc = extractor.get(sources.get(i));
            if (loc == null ||
                    !calculateMatchingScore(ULocale.addLikelySubtags(ULocale.forLocale(loc)),
                            preferredLanguages, score)) {
                continue;
            }

            final String lang = loc.getLanguage();
            ScoreEntry bestScore = scoreboard.get(lang);
            if (bestScore == null) {
                bestScore = new ScoreEntry(score.length);
                scoreboard.put(lang, bestScore);
            }

            bestScore.updateIfBetter(score, i);
        }

        final ScoreEntry[] result = scoreboard.values().toArray(new ScoreEntry[scoreboard.size()]);
        Arrays.sort(result);
        for (final ScoreEntry entry : result) {
            dest.add(sources.get(entry.index));
        }
    }
}
