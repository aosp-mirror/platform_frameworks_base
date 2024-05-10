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
import android.annotation.NonNull;
import android.graphics.TemporaryBuffer;
import android.graphics.text.GraphemeBreak;

/**
 * Implementation of {@code SegmentFinder} using grapheme clusters as the text segment. Whitespace
 * characters are included as segments.
 *
 * <p>For example, the text "a pot" would be divided into five text segments: "a", " ", "p", "o",
 * "t".
 *
 * @see <a href="https://unicode.org/reports/tr29/#Grapheme_Cluster_Boundaries">Unicode Text
 *     Segmentation - Grapheme Cluster Boundaries</a>
 */
public class GraphemeClusterSegmentFinder extends SegmentFinder {
    private static AutoGrowArray.FloatArray sTempAdvances = null;
    private final boolean[] mIsGraphemeBreak;

    /**
     * Constructs a GraphemeClusterSegmentFinder instance for the specified text which uses the
     * provided TextPaint to determine grapheme cluster boundaries.
     *
     * @param text text to be segmented
     * @param textPaint TextPaint used to draw the text
     */
    public GraphemeClusterSegmentFinder(
            @NonNull CharSequence text, @NonNull TextPaint textPaint) {

        if (sTempAdvances == null) {
            sTempAdvances = new AutoGrowArray.FloatArray(text.length());
        } else if (sTempAdvances.size() < text.length()) {
            sTempAdvances.resize(text.length());
        }

        mIsGraphemeBreak = new boolean[text.length()];
        float[] advances = sTempAdvances.getRawArray();
        char[] chars = TemporaryBuffer.obtain(text.length());

        TextUtils.getChars(text, 0, text.length(), chars, 0);

        textPaint.getTextWidths(chars, 0, text.length(), advances);

        GraphemeBreak.isGraphemeBreak(advances, chars, /* start= */ 0, /* end= */ text.length(),
                mIsGraphemeBreak);
        TemporaryBuffer.recycle(chars);
    }

    private int previousBoundary(@IntRange(from = 0) int offset) {
        if (offset <= 0) return DONE;
        do {
            --offset;
        } while (offset > 0 && !mIsGraphemeBreak[offset]);
        return offset;
    }

    private int nextBoundary(@IntRange(from = 0) int offset) {
        if (offset >= mIsGraphemeBreak.length) return DONE;
        do {
            ++offset;
        } while (offset < mIsGraphemeBreak.length && !mIsGraphemeBreak[offset]);
        return offset;
    }

    @Override
    public int previousStartBoundary(@IntRange(from = 0) int offset) {
        return previousBoundary(offset);
    }

    @Override
    public int previousEndBoundary(@IntRange(from = 0) int offset) {
        if (offset == 0) return DONE;
        int boundary = previousBoundary(offset);
        // Check that there is another cursor position before, otherwise this is not a valid
        // end boundary.
        if (boundary == DONE || previousBoundary(boundary) == DONE) {
            return DONE;
        }
        return boundary;
    }

    @Override
    public int nextStartBoundary(@IntRange(from = 0) int offset) {
        if (offset == mIsGraphemeBreak.length) return DONE;
        int boundary = nextBoundary(offset);
        // Check that there is another cursor position after, otherwise this is not a valid
        // start boundary.
        if (boundary == DONE || nextBoundary(boundary) == DONE) {
            return DONE;
        }
        return boundary;
    }

    @Override
    public int nextEndBoundary(@IntRange(from = 0) int offset) {
        return nextBoundary(offset);
    }
}
