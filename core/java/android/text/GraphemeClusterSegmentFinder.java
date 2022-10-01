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
import android.graphics.Paint;

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
    private final CharSequence mText;
    private final TextPaint mTextPaint;

    public GraphemeClusterSegmentFinder(
            @NonNull CharSequence text, @NonNull TextPaint textPaint) {
        mText = text;
        mTextPaint = textPaint;
    }

    @Override
    public int previousStartBoundary(@IntRange(from = 0) int offset) {
        int boundary = mTextPaint.getTextRunCursor(
                mText, 0, mText.length(), false, offset, Paint.CURSOR_BEFORE);
        return boundary == -1 ? DONE : boundary;
    }

    @Override
    public int previousEndBoundary(@IntRange(from = 0) int offset) {
        int boundary = mTextPaint.getTextRunCursor(
                mText, 0, mText.length(), false, offset, Paint.CURSOR_BEFORE);
        // Check that there is another cursor position before, otherwise this is not a valid
        // end boundary.
        if (mTextPaint.getTextRunCursor(
                mText, 0, mText.length(), false, boundary, Paint.CURSOR_BEFORE) == -1) {
            return DONE;
        }
        return boundary == -1 ? DONE : boundary;
    }

    @Override
    public int nextStartBoundary(@IntRange(from = 0) int offset) {
        int boundary = mTextPaint.getTextRunCursor(
                mText, 0, mText.length(), false, offset, Paint.CURSOR_AFTER);
        // Check that there is another cursor position after, otherwise this is not a valid
        // start boundary.
        if (mTextPaint.getTextRunCursor(
                mText, 0, mText.length(), false, boundary, Paint.CURSOR_AFTER) == -1) {
            return DONE;
        }
        return boundary == -1 ? DONE : boundary;
    }

    @Override
    public int nextEndBoundary(@IntRange(from = 0) int offset) {
        int boundary = mTextPaint.getTextRunCursor(
                mText, 0, mText.length(), false, offset, Paint.CURSOR_AFTER);
        return boundary == -1 ? DONE : boundary;
    }
}
