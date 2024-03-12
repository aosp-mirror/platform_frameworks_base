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
import android.icu.text.BreakIterator;
import android.icu.util.ULocale;
import android.text.method.WordIterator;

/**
 * Implementation of {@link SegmentFinder} using words as the text segment. Word boundaries are
 * found using {@code WordIterator}. Whitespace characters are excluded, so they are not included in
 * any text segments.
 *
 * <p>For example, the text "Hello, World!" would be subdivided into four text segments: "Hello",
 * ",", "World", "!". The space character does not belong to any text segments.
 *
 * @see <a href="https://unicode.org/reports/tr29/#Word_Boundaries">Unicode Text Segmentation - Word
 *     Boundaries</a>
 */
public class WordSegmentFinder extends SegmentFinder {
    private final CharSequence mText;
    private final WordIterator mWordIterator;

    /**
     * Constructs a WordSegmentFinder instance for the specified text which uses the provided locale
     * to determine word boundaries.
     *
     * @param text text to be segmented
     * @param locale locale used for analyzing the text
     */
    public WordSegmentFinder(
            @NonNull CharSequence text, @NonNull ULocale locale) {
        mText = text;
        mWordIterator = new WordIterator(locale);
        mWordIterator.setCharSequence(text, 0, text.length());
    }

    /**
     * Constructs a WordSegmentFinder instance for the specified text which uses the provided
     * WordIterator to determine word boundaries.
     *
     * @param text text to be segmented
     * @param wordIterator word iterator used to find word boundaries in the text
     * @hide
     */
    public WordSegmentFinder(@NonNull CharSequence text, @NonNull WordIterator wordIterator) {
        mText = text;
        mWordIterator = wordIterator;
    }

    @Override
    public int previousStartBoundary(@IntRange(from = 0) int offset) {
        int boundary = offset;
        do {
            boundary = mWordIterator.prevBoundary(boundary);
            if (boundary == BreakIterator.DONE) {
                return DONE;
            }
        } while (Character.isWhitespace(mText.charAt(boundary)));
        return boundary;
    }

    @Override
    public int previousEndBoundary(@IntRange(from = 0) int offset) {
        int boundary = offset;
        do {
            boundary = mWordIterator.prevBoundary(boundary);
            if (boundary == BreakIterator.DONE || boundary == 0) {
                return DONE;
            }
        } while (Character.isWhitespace(mText.charAt(boundary - 1)));
        return boundary;
    }

    @Override
    public int nextStartBoundary(@IntRange(from = 0) int offset) {
        int boundary = offset;
        do {
            boundary = mWordIterator.nextBoundary(boundary);
            if (boundary == BreakIterator.DONE || boundary == mText.length()) {
                return DONE;
            }
        } while (Character.isWhitespace(mText.charAt(boundary)));
        return boundary;
    }

    @Override
    public int nextEndBoundary(@IntRange(from = 0) int offset) {
        int boundary = offset;
        do {
            boundary = mWordIterator.nextBoundary(boundary);
            if (boundary == BreakIterator.DONE) {
                return DONE;
            }
        } while (Character.isWhitespace(mText.charAt(boundary - 1)));
        return boundary;
    }
}
