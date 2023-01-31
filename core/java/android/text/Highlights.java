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

package android.text;

import android.graphics.Paint;
import android.util.Pair;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class that represents of the highlight of the text.
 */
public class Highlights {
    private final List<Pair<Paint, int[]>> mHighlights;

    private Highlights(List<Pair<Paint, int[]>> highlights) {
        mHighlights = highlights;
    }

    /**
     * Returns the number of highlight.
     *
     * @return the number of highlight.
     *
     * @see Builder#addRange(Paint, int, int)
     * @see Builder#addRanges(Paint, int...)
     */
    public @IntRange(from = 0) int getSize() {
        return mHighlights.size();
    }

    /**
     * Returns a paint used for the i-th highlight.
     *
     * @param index an index of the highlight. Must be between 0 and {@link #getSize()}
     * @return the paint object
     *
     * @see Builder#addRange(Paint, int, int)
     * @see Builder#addRanges(Paint, int...)
     */
    public @NonNull Paint getPaint(@IntRange(from = 0) int index) {
        return mHighlights.get(index).first;
    }

    /**
     * Returns ranges of the i-th highlight.
     *
     * Ranges are represented of flattened inclusive start and exclusive end integers array. The
     * inclusive start offset of the {@code i}-th range is stored in {@code 2 * i}-th of the array.
     * The exclusive end offset of the {@code i}-th range is stored in {@code 2* i + 1}-th of the
     * array. For example, the two ranges: (1, 2) and (3, 4) are flattened into single int array
     * [1, 2, 3, 4].
     *
     * @param index an index of the highlight. Must be between 0 and {@link #getSize()}
     * @return the flattened ranges.
     *
     * @see Builder#addRange(Paint, int, int)
     * @see Builder#addRanges(Paint, int...)
     */
    public @NonNull int[] getRanges(int index) {
        return mHighlights.get(index).second;
    }

    /**
     * A builder for the Highlights.
     */
    public static final class Builder {
        private final List<Pair<Paint, int[]>> mHighlights = new ArrayList<>();

        /**
         * Add single range highlight.
         *
         * If the given range has overlaps with the already added ranges, the previous highlights
         * are overdrawn by this range.
         *
         * @param paint a paint object used for drawing highlight path.
         * @param start an inclusive offset of the text.
         * @param end an exclusive offset of the text.
         * @return this builder instance.
         */
        public @NonNull Builder addRange(@NonNull Paint paint, @IntRange(from = 0) int start,
                @IntRange(from = 0) int end) {
            if (start > end) {
                throw new IllegalArgumentException("start must not be larger than end: "
                        + start + ", " + end);
            }
            Objects.requireNonNull(paint);

            int[] range = new int[] {start, end};
            mHighlights.add(new Pair<>(paint, range));
            return this;
        }

        /**
         * Add multiple ranges highlight.
         *
         * If the given ranges have overlap with the already added ranges, the previous highlights
         * are overdrawn by this range.
         *
         * @param paint a paint object used for drawing highlight path.
         * @param ranges a flatten ranges. The {@code 2 * i}-th element is an inclusive start offset
         *              of the {@code i}-th character. The {@code 2 * i + 1}-th element is an
         *              exclusive end offset of the {@code i}-th character.
         * @return this builder instance.
         */
        public @NonNull Builder addRanges(@NonNull Paint paint, @NonNull int... ranges) {
            if (ranges.length % 2 == 1) {
                throw new IllegalArgumentException(
                        "Flatten ranges must have even numbered elements");
            }
            for (int j = 0; j < ranges.length / 2; ++j) {
                int start = ranges[j * 2];
                int end = ranges[j * 2 + 1];
                if (start > end) {
                    throw new IllegalArgumentException(
                            "Reverse range found in the flatten range: " + Arrays.toString(
                                    ranges));
                }
            }
            Objects.requireNonNull(paint);
            mHighlights.add(new Pair<>(paint, ranges));
            return this;
        }

        /**
         * Build a new Highlights instance.
         *
         * @return a new Highlights instance.
         */
        public @NonNull Highlights build() {
            return new Highlights(mHighlights);
        }
    }
}
