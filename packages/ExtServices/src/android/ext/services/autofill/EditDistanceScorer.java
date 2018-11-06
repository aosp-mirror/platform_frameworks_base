/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.ext.services.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.autofill.AutofillValue;

import com.android.internal.annotations.VisibleForTesting;

final class EditDistanceScorer {

    private static final String TAG = "EditDistanceScorer";

    // TODO(b/70291841): STOPSHIP - set to false before launching
    private static final boolean DEBUG = true;

    /**
     * Gets the field classification score of 2 values based on the edit distance between them.
     *
     * <p>The score is defined as: @(max_length - edit_distance) / max_length
     */
    @VisibleForTesting
    static float calculateScore(@Nullable AutofillValue actualValue,
            @Nullable String userDataValue) {
        if (actualValue == null || !actualValue.isText() || userDataValue == null) return 0;

        final String actualValueText = actualValue.getTextValue().toString();
        final int actualValueLength = actualValueText.length();
        final int userDatalength = userDataValue.length();
        if (userDatalength == 0) {
            return (actualValueLength == 0) ? 1 : 0;
        }

        final int distance = editDistance(actualValueText.toLowerCase(),
                userDataValue.toLowerCase());
        final int maxLength = Math.max(actualValueLength, userDatalength);
        return ((float) maxLength - distance) / maxLength;
    }

    /**
     * Computes the edit distance (number of insertions, deletions or substitutions to edit one
     * string into the other) between two strings. In particular, this will compute the Levenshtein
     * distance.
     *
     * <p>See http://en.wikipedia.org/wiki/Levenshtein_distance for details.
     *
     * @param s the first string to compare
     * @param t the second string to compare
     * @return the edit distance between the two strings
     */
    // Note: copied verbatim from com.android.tools.lint.detector.api.LintUtils.java
    public static int editDistance(@NonNull String s, @NonNull String t) {
        return editDistance(s, t, Integer.MAX_VALUE);
    }

    /**
     * Computes the edit distance (number of insertions, deletions or substitutions to edit one
     * string into the other) between two strings. In particular, this will compute the Levenshtein
     * distance.
     *
     * <p>See http://en.wikipedia.org/wiki/Levenshtein_distance for details.
     *
     * @param s the first string to compare
     * @param t the second string to compare
     * @param max the maximum edit distance that we care about; if for example the string length
     *     delta is greater than this we don't bother computing the exact edit distance since the
     *     caller has indicated they're not interested in the result
     * @return the edit distance between the two strings, or some other value greater than that if
     *     the edit distance is at least as big as the {@code max} parameter
     */
    // Note: copied verbatim from com.android.tools.lint.detector.api.LintUtils.java
    private static int editDistance(@NonNull String s, @NonNull String t, int max) {
        if (s.equals(t)) {
            return 0;
        }

        if (Math.abs(s.length() - t.length()) > max) {
            // The string lengths differ more than the allowed edit distance;
            // no point in even attempting to compute the edit distance (requires
            // O(n*m) storage and O(n*m) speed, where n and m are the string lengths)
            return Integer.MAX_VALUE;
        }

        int m = s.length();
        int n = t.length();
        int[][] d = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            d[0][j] = j;
        }
        for (int j = 1; j <= n; j++) {
            for (int i = 1; i <= m; i++) {
                if (s.charAt(i - 1) == t.charAt(j - 1)) {
                    d[i][j] = d[i - 1][j - 1];
                } else {
                    int deletion = d[i - 1][j] + 1;
                    int insertion = d[i][j - 1] + 1;
                    int substitution = d[i - 1][j - 1] + 1;
                    d[i][j] = Math.min(deletion, Math.min(insertion, substitution));
                }
            }
        }

        return d[m][n];
    }

}
