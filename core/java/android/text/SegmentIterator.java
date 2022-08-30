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

/**
 * Finds text segment boundaries within text. Subclasses can implement different types of text
 * segments. Grapheme clusters and words are examples of possible text segments.
 *
 * <p>Granular units may not overlap, so every character belongs to at most one text segment. A
 * character may belong to no text segments.
 *
 * <p>For example, a word level text segment iterator may subdivide the text "Hello, World!" into
 * four text segments: "Hello", ",", "World", "!". The space character does not belong to any text
 * segments.
 *
 * @hide
 */
public abstract class SegmentIterator {
    public static final int DONE = -1;

    private int mRunStartOffset;
    private int mRunEndOffset;

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
     * Sets the start and end of a run which can be used to constrain the scope of the iterator's
     * search.
     *
     * @hide
     */
    void setRunLimits(
            @IntRange(from = 0) int runStartOffset, @IntRange(from = 0) int runEndOffset) {
        mRunStartOffset = runStartOffset;
        mRunEndOffset = runEndOffset;
    }

    /** @hide */
    int previousStartBoundaryOrRunStart(@IntRange(from = 0) int offset) {
        int start = previousStartBoundary(offset);
        if (start == DONE) {
            return DONE;
        }
        return Math.max(start, mRunStartOffset);
    }

    /** @hide */
    int previousEndBoundaryWithinRunLimits(@IntRange(from = 0) int offset) {
        int end = previousEndBoundary(offset);
        if (end <= mRunStartOffset) {
            return DONE;
        }
        return end;
    }

    /** @hide */
    int nextStartBoundaryWithinRunLimits(@IntRange(from = 0) int offset) {
        int start = nextStartBoundary(offset);
        if (start >= mRunEndOffset) {
            return DONE;
        }
        return start;
    }

    /** @hide */
    int nextEndBoundaryOrRunEnd(@IntRange(from = 0) int offset) {
        int end = nextEndBoundary(offset);
        if (end == DONE) {
            return DONE;
        }
        return Math.min(end, mRunEndOffset);
    }
}
