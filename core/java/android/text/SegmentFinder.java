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

import android.annotation.IntRange;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Objects;

/**
 * Finds text segment boundaries within text. Subclasses can implement different types of text
 * segments. Grapheme clusters and words are examples of possible text segments. These are
 * implemented by {@link GraphemeClusterSegmentFinder} and {@link WordSegmentFinder}.
 *
 * <p>Text segments may not overlap, so every character belongs to at most one text segment. A
 * character may belong to no text segments.
 *
 * <p>For example, WordSegmentFinder subdivides the text "Hello, World!" into four text segments:
 * "Hello", ",", "World", "!". The space character does not belong to any text segments.
 *
 * @see Layout#getRangeForRect(RectF, SegmentFinder, Layout.TextInclusionStrategy)
 */
public abstract class SegmentFinder {
    /**
     * Return value of previousStartBoundary(int), previousEndBoundary(int), nextStartBoundary(int),
     * and nextEndBoundary(int) when there are no boundaries of the specified type in the specified
     * direction.
     */
    public static final int DONE = -1;

    /**
     * Returns the character offset of the previous text segment start boundary before the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int previousStartBoundary(@IntRange(from = 0) int offset);

    /**
     * Returns the character offset of the previous text segment end boundary before the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int previousEndBoundary(@IntRange(from = 0) int offset);

    /**
     * Returns the character offset of the next text segment start boundary after the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int nextStartBoundary(@IntRange(from = 0) int offset);

    /**
     * Returns the character offset of the next text segment end boundary after the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int nextEndBoundary(@IntRange(from = 0) int offset);

    /**
     * The default {@link SegmentFinder} implementation based on given segment ranges.
     */
    public static class PrescribedSegmentFinder extends SegmentFinder {
        private final int[] mSegments;

        /**
         * Create a SegmentFinder with segments stored in an array, where i-th segment's start is
         * stored at segments[2 * i] and end is stored at segments[2 * i + 1] respectively.
         *
         * <p> It is required that segments do not overlap, and are already sorted by their start
         * indices. </p>
         * @param segments the array that stores the segment ranges.
         * @throws IllegalArgumentException if the given segments array's length is not even; the
         * given segments are not sorted or there are segments overlap with others.
         */
        public PrescribedSegmentFinder(@NonNull int[] segments) {
            checkSegmentsValid(segments);
            mSegments = segments;
        }

        /** {@inheritDoc} */
        @Override
        public int previousStartBoundary(@IntRange(from = 0) int offset) {
            return findPrevious(offset, /* isStart = */ true);
        }

        /** {@inheritDoc} */
        @Override
        public int previousEndBoundary(@IntRange(from = 0) int offset) {
            return findPrevious(offset, /* isStart = */ false);
        }

        /** {@inheritDoc} */
        @Override
        public int nextStartBoundary(@IntRange(from = 0) int offset) {
            return findNext(offset, /* isStart = */ true);
        }

        /** {@inheritDoc} */
        @Override
        public int nextEndBoundary(@IntRange(from = 0) int offset) {
            return findNext(offset, /* isStart = */ false);
        }

        private int findNext(int offset, boolean isStart) {
            if (offset < 0) return DONE;
            if (mSegments.length < 1 || offset > mSegments[mSegments.length - 1]) return DONE;

            if (offset < mSegments[0]) {
                return isStart ? mSegments[0] : mSegments[1];
            }

            int index = Arrays.binarySearch(mSegments, offset);
            if (index >= 0) {
                // mSegments may have duplicate elements (The previous segments end equals
                // to the following segments start.) Move the index forwards since we are searching
                // for the next segment.
                if (index + 1 < mSegments.length && mSegments[index + 1] == offset) {
                    index = index + 1;
                }
                // Point the index to the first segment boundary larger than the given offset.
                index += 1;
            } else {
                // binarySearch returns the insertion point, it's the first segment boundary larger
                // than the given offset.
                index = -(index + 1);
            }
            if (index >= mSegments.length) return DONE;

            //  +---------------------------------------+
            //  |               | isStart   | isEnd     |
            //  |---------------+-----------+-----------|
            //  | indexIsStart  | index     | index + 1 |
            //  |---------------+-----------+-----------|
            //  | indexIsEnd    | index + 1 | index     |
            //  +---------------------------------------+
            boolean indexIsStart = index % 2 == 0;
            if (isStart != indexIsStart) {
                return (index + 1 < mSegments.length) ? mSegments[index + 1] : DONE;
            }
            return mSegments[index];
        }

        private int findPrevious(int offset, boolean isStart) {
            if (mSegments.length < 1 || offset < mSegments[0]) return DONE;

            if (offset > mSegments[mSegments.length - 1]) {
                return isStart ? mSegments[mSegments.length - 2] : mSegments[mSegments.length - 1];
            }

            int index = Arrays.binarySearch(mSegments, offset);
            if (index >= 0) {
                // mSegments may have duplicate elements (when the previous segments end equal
                // to the following segments start). Move the index backwards since we are searching
                // for the previous segment.
                if (index > 0 && mSegments[index - 1] == offset) {
                    index = index - 1;
                }
                // Point the index to the first segment boundary smaller than the given offset.
                index -= 1;
            } else {
                // binarySearch returns the insertion point, insertionPoint - 1 is the first
                // segment boundary smaller than the given offset.
                index = -(index + 1) - 1;
            }
            if (index < 0) return DONE;

            //  +---------------------------------------+
            //  |               | isStart   | isEnd     |
            //  |---------------+-----------+-----------|
            //  | indexIsStart  | index     | index - 1 |
            //  |---------------+-----------+-----------|
            //  | indexIsEnd    | index - 1 | index     |
            //  +---------------------------------------+
            boolean indexIsStart = index % 2 == 0;
            if (isStart != indexIsStart) {
                return (index > 0) ? mSegments[index - 1] : DONE;
            }
            return mSegments[index];
        }

        private static void checkSegmentsValid(int[] segments) {
            Objects.requireNonNull(segments);
            Preconditions.checkArgument(segments.length % 2 == 0,
                    "the length of segments must be even");
            if (segments.length == 0) return;
            int lastSegmentEnd = Integer.MIN_VALUE;
            for (int index = 0; index < segments.length; index += 2) {
                if (segments[index] < lastSegmentEnd) {
                    throw new IllegalArgumentException("segments can't overlap");
                }
                if (segments[index] >= segments[index + 1]) {
                    throw new IllegalArgumentException("the segment range can't be empty");
                }
                lastSegmentEnd = segments[index + 1];
            }
        }
    }
}
