
/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.text.method;

import android.text.Selection;
import android.text.SpannableStringBuilder;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * Walks through cursor positions at word boundaries. Internally uses
 * {@link BreakIterator#getWordInstance()}, and caches {@link CharSequence}
 * for performance reasons.
 *
 * Also provides methods to determine word boundaries.
 * {@hide}
 */
public class WordIterator implements Selection.PositionIterator {
    // Size of the window for the word iterator, should be greater than the longest word's length
    private static final int WINDOW_WIDTH = 50;

    private String mString;
    private int mOffsetShift;

    private BreakIterator mIterator;

    /**
     * Constructs a WordIterator using the default locale.
     */
    public WordIterator() {
        this(Locale.getDefault());
    }

    /**
     * Constructs a new WordIterator for the specified locale.
     * @param locale The locale to be used when analysing the text.
     */
    public WordIterator(Locale locale) {
        mIterator = BreakIterator.getWordInstance(locale);
    }

    public void setCharSequence(CharSequence charSequence, int start, int end) {
        mOffsetShift = Math.max(0, start - WINDOW_WIDTH);
        final int windowEnd = Math.min(charSequence.length(), end + WINDOW_WIDTH);

        if (charSequence instanceof SpannableStringBuilder) {
            mString = ((SpannableStringBuilder) charSequence).substring(mOffsetShift, windowEnd);
        } else {
            mString = charSequence.subSequence(mOffsetShift, windowEnd).toString();
        }
        mIterator.setText(mString);
    }

    /** {@inheritDoc} */
    public int preceding(int offset) {
        int shiftedOffset = offset - mOffsetShift;
        do {
            shiftedOffset = mIterator.preceding(shiftedOffset);
            if (shiftedOffset == BreakIterator.DONE) {
                return BreakIterator.DONE;
            }
            if (isOnLetterOrDigit(shiftedOffset)) {
                return shiftedOffset + mOffsetShift;
            }
        } while (true);
    }

    /** {@inheritDoc} */
    public int following(int offset) {
        int shiftedOffset = offset - mOffsetShift;
        do {
            shiftedOffset = mIterator.following(shiftedOffset);
            if (shiftedOffset == BreakIterator.DONE) {
                return BreakIterator.DONE;
            }
            if (isAfterLetterOrDigit(shiftedOffset)) {
                return shiftedOffset + mOffsetShift;
            }
        } while (true);
    }

    /** {@inheritDoc} */
    public boolean isBoundary(int offset) {
        int shiftedOffset = offset - mOffsetShift;
        checkOffsetIsValid(shiftedOffset);
        return mIterator.isBoundary(shiftedOffset);
    }

    /**
     * Returns the position of next boundary after the given offset. Returns
     * {@code DONE} if there is no boundary after the given offset.
     *
     * @param offset the given start position to search from.
     * @return the position of the last boundary preceding the given offset.
     */
    public int nextBoundary(int offset) {
        int shiftedOffset = offset - mOffsetShift;
        shiftedOffset = mIterator.following(shiftedOffset);
        if (shiftedOffset == BreakIterator.DONE) {
            return BreakIterator.DONE;
        }
        return shiftedOffset + mOffsetShift;
    }

    /**
     * Returns the position of boundary preceding the given offset or
     * {@code DONE} if the given offset specifies the starting position.
     *
     * @param offset the given start position to search from.
     * @return the position of the last boundary preceding the given offset.
     */
    public int prevBoundary(int offset) {
        int shiftedOffset = offset - mOffsetShift;
        shiftedOffset = mIterator.preceding(shiftedOffset);
        if (shiftedOffset == BreakIterator.DONE) {
            return BreakIterator.DONE;
        }
        return shiftedOffset + mOffsetShift;
    }

    /** If <code>offset</code> is within a word, returns the index of the first character of that
     * word, otherwise returns BreakIterator.DONE.
     *
     * The offsets that are considered to be part of a word are the indexes of its characters,
     * <i>as well as</i> the index of its last character plus one.
     * If offset is the index of a low surrogate character, BreakIterator.DONE will be returned.
     *
     * Valid range for offset is [0..textLength] (note the inclusive upper bound).
     * The returned value is within [0..offset] or BreakIterator.DONE.
     *
     * @throws IllegalArgumentException is offset is not valid.
     */
    public int getBeginning(int offset) {
        final int shiftedOffset = offset - mOffsetShift;
        checkOffsetIsValid(shiftedOffset);

        if (isOnLetterOrDigit(shiftedOffset)) {
            if (mIterator.isBoundary(shiftedOffset)) {
                return shiftedOffset + mOffsetShift;
            } else {
                return mIterator.preceding(shiftedOffset) + mOffsetShift;
            }
        } else {
            if (isAfterLetterOrDigit(shiftedOffset)) {
                return mIterator.preceding(shiftedOffset) + mOffsetShift;
            }
        }
        return BreakIterator.DONE;
    }

    /** If <code>offset</code> is within a word, returns the index of the last character of that
     * word plus one, otherwise returns BreakIterator.DONE.
     *
     * The offsets that are considered to be part of a word are the indexes of its characters,
     * <i>as well as</i> the index of its last character plus one.
     * If offset is the index of a low surrogate character, BreakIterator.DONE will be returned.
     *
     * Valid range for offset is [0..textLength] (note the inclusive upper bound).
     * The returned value is within [offset..textLength] or BreakIterator.DONE.
     *
     * @throws IllegalArgumentException is offset is not valid.
     */
    public int getEnd(int offset) {
        final int shiftedOffset = offset - mOffsetShift;
        checkOffsetIsValid(shiftedOffset);

        if (isAfterLetterOrDigit(shiftedOffset)) {
            if (mIterator.isBoundary(shiftedOffset)) {
                return shiftedOffset + mOffsetShift;
            } else {
                return mIterator.following(shiftedOffset) + mOffsetShift;
            }
        } else {
            if (isOnLetterOrDigit(shiftedOffset)) {
                return mIterator.following(shiftedOffset) + mOffsetShift;
            }
        }
        return BreakIterator.DONE;
    }

    /**
     * If <code>offset</code> is within a group of punctuation as defined
     * by {@link #isPunctuation(int)}, returns the index of the first character
     * of that group, otherwise returns BreakIterator.DONE.
     *
     * @param offset the offset to search from.
     */
    public int getPunctuationBeginning(int offset) {
        while (offset != BreakIterator.DONE && !isPunctuationStartBoundary(offset)) {
            offset = prevBoundary(offset);
        }
        // No need to shift offset, prevBoundary handles that.
        return offset;
    }

    /**
     * If <code>offset</code> is within a group of punctuation as defined
     * by {@link #isPunctuation(int)}, returns the index of the last character
     * of that group plus one, otherwise returns BreakIterator.DONE.
     *
     * @param offset the offset to search from.
     */
    public int getPunctuationEnd(int offset) {
        while (offset != BreakIterator.DONE && !isPunctuationEndBoundary(offset)) {
            offset = nextBoundary(offset);
        }
        // No need to shift offset, nextBoundary handles that.
        return offset;
    }

    /**
     * Indicates if the provided offset is after a punctuation character
     * as defined by {@link #isPunctuation(int)}.
     *
     * @param offset the offset to check from.
     * @return Whether the offset is after a punctuation character.
     */
    public boolean isAfterPunctuation(int offset) {
        final int shiftedOffset = offset - mOffsetShift;
        if (shiftedOffset >= 1 && shiftedOffset <= mString.length()) {
            final int codePoint = mString.codePointBefore(shiftedOffset);
            return isPunctuation(codePoint);
        }
        return false;
    }

    /**
     * Indicates if the provided offset is at a punctuation character
     * as defined by {@link #isPunctuation(int)}.
     *
     * @param offset the offset to check from.
     * @return Whether the offset is at a punctuation character.
     */
    public boolean isOnPunctuation(int offset) {
        final int shiftedOffset = offset - mOffsetShift;
        if (shiftedOffset >= 0 && shiftedOffset < mString.length()) {
            final int codePoint = mString.codePointAt(shiftedOffset);
            return isPunctuation(codePoint);
        }
        return false;
    }

    private boolean isPunctuationStartBoundary(int offset) {
        return isOnPunctuation(offset) && !isAfterPunctuation(offset);
    }

    private boolean isPunctuationEndBoundary(int offset) {
        return !isOnPunctuation(offset) && isAfterPunctuation(offset);
    }

    private boolean isPunctuation(int cp) {
        int type = Character.getType(cp);
        return (type == Character.CONNECTOR_PUNCTUATION ||
                type == Character.DASH_PUNCTUATION ||
                type == Character.END_PUNCTUATION ||
                type == Character.FINAL_QUOTE_PUNCTUATION ||
                type == Character.INITIAL_QUOTE_PUNCTUATION ||
                type == Character.OTHER_PUNCTUATION ||
                type == Character.START_PUNCTUATION);
    }

    private boolean isAfterLetterOrDigit(int shiftedOffset) {
        if (shiftedOffset >= 1 && shiftedOffset <= mString.length()) {
            final int codePoint = mString.codePointBefore(shiftedOffset);
            if (Character.isLetterOrDigit(codePoint)) return true;
        }
        return false;
    }

    private boolean isOnLetterOrDigit(int shiftedOffset) {
        if (shiftedOffset >= 0 && shiftedOffset < mString.length()) {
            final int codePoint = mString.codePointAt(shiftedOffset);
            if (Character.isLetterOrDigit(codePoint)) return true;
        }
        return false;
    }

    private void checkOffsetIsValid(int shiftedOffset) {
        if (shiftedOffset < 0 || shiftedOffset > mString.length()) {
            throw new IllegalArgumentException("Invalid offset: " + (shiftedOffset + mOffsetShift) +
                    ". Valid range is [" + mOffsetShift + ", " + (mString.length() + mOffsetShift) +
                    "]");
        }
    }
}
