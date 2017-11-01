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

import android.annotation.NonNull;
import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;
import android.icu.text.BreakIterator;
import android.text.CharSequenceCharacterIterator;
import android.text.Selection;

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

    private int mStart, mEnd;
    private CharSequence mCharSeq;
    private final BreakIterator mIterator;

    /**
     * Constructs a WordIterator using the default locale.
     */
    public WordIterator() {
        this(Locale.getDefault());
    }

    /**
     * Constructs a new WordIterator for the specified locale.
     * @param locale The locale to be used for analyzing the text.
     */
    public WordIterator(Locale locale) {
        mIterator = BreakIterator.getWordInstance(locale);
    }

    public void setCharSequence(@NonNull CharSequence charSequence, int start, int end) {
        if (0 <= start && end <= charSequence.length()) {
            mCharSeq = charSequence;
            mStart = Math.max(0, start - WINDOW_WIDTH);
            mEnd = Math.min(charSequence.length(), end + WINDOW_WIDTH);
            mIterator.setText(new CharSequenceCharacterIterator(charSequence, mStart, mEnd));
        } else {
            throw new IndexOutOfBoundsException("input indexes are outside the CharSequence");
        }
    }

    /** {@inheritDoc} */
    public int preceding(int offset) {
        checkOffsetIsValid(offset);
        while (true) {
            offset = mIterator.preceding(offset);
            if (offset == BreakIterator.DONE || isOnLetterOrDigit(offset)) {
                return offset;
            }
        }
    }

    /** {@inheritDoc} */
    public int following(int offset) {
        checkOffsetIsValid(offset);
        while (true) {
            offset = mIterator.following(offset);
            if (offset == BreakIterator.DONE || isAfterLetterOrDigit(offset)) {
                return offset;
            }
        }
    }

    /** {@inheritDoc} */
    public boolean isBoundary(int offset) {
        checkOffsetIsValid(offset);
        return mIterator.isBoundary(offset);
    }

    /**
     * Returns the position of next boundary after the given offset. Returns
     * {@code DONE} if there is no boundary after the given offset.
     *
     * @param offset the given start position to search from.
     * @return the position of the last boundary preceding the given offset.
     */
    public int nextBoundary(int offset) {
        checkOffsetIsValid(offset);
        return mIterator.following(offset);
    }

    /**
     * Returns the position of boundary preceding the given offset or
     * {@code DONE} if the given offset specifies the starting position.
     *
     * @param offset the given start position to search from.
     * @return the position of the last boundary preceding the given offset.
     */
    public int prevBoundary(int offset) {
        checkOffsetIsValid(offset);
        return mIterator.preceding(offset);
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
        // TODO: Check if usage of this can be updated to getBeginning(offset, true) if
        // so this method can be removed.
        return getBeginning(offset, false);
    }

    /**
     * If <code>offset</code> is within a word, returns the index of the last character of that
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
        // TODO: Check if usage of this can be updated to getEnd(offset, true), if
        // so this method can be removed.
        return getEnd(offset, false);
    }

    /**
     * If the <code>offset</code> is within a word or on a word boundary that can only be
     * considered the start of a word (e.g. _word where "_" is any character that would not
     * be considered part of the word) then this returns the index of the first character of
     * that word.
     *
     * If the offset is on a word boundary that can be considered the start and end of a
     * word, e.g. AABB (where AA and BB are both words) and the offset is the boundary
     * between AA and BB, this would return the start of the previous word, AA.
     *
     * Returns BreakIterator.DONE if there is no previous boundary.
     *
     * @throws IllegalArgumentException is offset is not valid.
     */
    public int getPrevWordBeginningOnTwoWordsBoundary(int offset) {
        return getBeginning(offset, true);
    }

    /**
     * If the <code>offset</code> is within a word or on a word boundary that can only be
     * considered the end of a word (e.g. word_ where "_" is any character that would not
     * be considered part of the word) then this returns the index of the last character
     * plus one of that word.
     *
     * If the offset is on a word boundary that can be considered the start and end of a
     * word, e.g. AABB (where AA and BB are both words) and the offset is the boundary
     * between AA and BB, this would return the end of the next word, BB.
     *
     * Returns BreakIterator.DONE if there is no next boundary.
     *
     * @throws IllegalArgumentException is offset is not valid.
     */
    public int getNextWordEndOnTwoWordBoundary(int offset) {
        return getEnd(offset, true);
    }

    /**
     * If the <code>offset</code> is within a word or on a word boundary that can only be
     * considered the start of a word (e.g. _word where "_" is any character that would not
     * be considered part of the word) then this returns the index of the first character of
     * that word.
     *
     * If the offset is on a word boundary that can be considered the start and end of a
     * word, e.g. AABB (where AA and BB are both words) and the offset is the boundary
     * between AA and BB, and getPrevWordBeginningOnTwoWordsBoundary is true then this would
     * return the start of the previous word, AA. Otherwise it would return the current offset,
     * the start of BB.
     *
     * Returns BreakIterator.DONE if there is no previous boundary.
     *
     * @throws IllegalArgumentException is offset is not valid.
     */
    private int getBeginning(int offset, boolean getPrevWordBeginningOnTwoWordsBoundary) {
        checkOffsetIsValid(offset);

        if (isOnLetterOrDigit(offset)) {
            if (mIterator.isBoundary(offset)
                    && (!isAfterLetterOrDigit(offset)
                            || !getPrevWordBeginningOnTwoWordsBoundary)) {
                return offset;
            } else {
                return mIterator.preceding(offset);
            }
        } else {
            if (isAfterLetterOrDigit(offset)) {
                return mIterator.preceding(offset);
            }
        }
        return BreakIterator.DONE;
    }

    /**
     * If the <code>offset</code> is within a word or on a word boundary that can only be
     * considered the end of a word (e.g. word_ where "_" is any character that would not be
     * considered part of the word) then this returns the index of the last character plus one
     * of that word.
     *
     * If the offset is on a word boundary that can be considered the start and end of a
     * word, e.g. AABB (where AA and BB are both words) and the offset is the boundary
     * between AA and BB, and getNextWordEndOnTwoWordBoundary is true then this would return
     * the end of the next word, BB. Otherwise it would return the current offset, the end
     * of AA.
     *
     * Returns BreakIterator.DONE if there is no next boundary.
     *
     * @throws IllegalArgumentException is offset is not valid.
     */
    private int getEnd(int offset, boolean getNextWordEndOnTwoWordBoundary) {
        checkOffsetIsValid(offset);

        if (isAfterLetterOrDigit(offset)) {
            if (mIterator.isBoundary(offset)
                    && (!isOnLetterOrDigit(offset) || !getNextWordEndOnTwoWordBoundary)) {
                return offset;
            } else {
                return mIterator.following(offset);
            }
        } else {
            if (isOnLetterOrDigit(offset)) {
                return mIterator.following(offset);
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
        checkOffsetIsValid(offset);
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
        checkOffsetIsValid(offset);
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
        if (mStart < offset && offset <= mEnd) {
            final int codePoint = Character.codePointBefore(mCharSeq, offset);
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
        if (mStart <= offset && offset < mEnd) {
            final int codePoint = Character.codePointAt(mCharSeq, offset);
            return isPunctuation(codePoint);
        }
        return false;
    }

    /**
     * Indicates if the codepoint is a mid-word-only punctuation.
     *
     * At the moment, this is locale-independent, and includes all the characters in
     * the MidLetter, MidNumLet, and Single_Quote class of Unicode word breaking algorithm (see
     * UAX #29 "Unicode Text Segmentation" at http://unicode.org/reports/tr29/). These are all the
     * characters that according to the rules WB6 and WB7 of UAX #29 prevent word breaks if they are
     * in the middle of a word, but they become word breaks if they happen at the end of a word
     * (accroding to rule WB999 that breaks word in any place that is not prohibited otherwise).
     *
     * @param locale the locale to consider the codepoint in. Presently ignored.
     * @param codePoint the codepoint to check.
     * @return True if the codepoint is a mid-word punctuation.
     */
    public static boolean isMidWordPunctuation(Locale locale, int codePoint) {
        final int wb = UCharacter.getIntPropertyValue(codePoint, UProperty.WORD_BREAK);
        return (wb == UCharacter.WordBreak.MIDLETTER
                || wb == UCharacter.WordBreak.MIDNUMLET
                || wb == UCharacter.WordBreak.SINGLE_QUOTE);
    }

    private boolean isPunctuationStartBoundary(int offset) {
        return isOnPunctuation(offset) && !isAfterPunctuation(offset);
    }

    private boolean isPunctuationEndBoundary(int offset) {
        return !isOnPunctuation(offset) && isAfterPunctuation(offset);
    }

    private static boolean isPunctuation(int cp) {
        final int type = Character.getType(cp);
        return (type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.START_PUNCTUATION);
    }

    private boolean isAfterLetterOrDigit(int offset) {
        if (mStart < offset && offset <= mEnd) {
            final int codePoint = Character.codePointBefore(mCharSeq, offset);
            if (Character.isLetterOrDigit(codePoint)) return true;
        }
        return false;
    }

    private boolean isOnLetterOrDigit(int offset) {
        if (mStart <= offset && offset < mEnd) {
            final int codePoint = Character.codePointAt(mCharSeq, offset);
            if (Character.isLetterOrDigit(codePoint)) return true;
        }
        return false;
    }

    private void checkOffsetIsValid(int offset) {
        if (!(mStart <= offset && offset <= mEnd)) {
            throw new IllegalArgumentException("Invalid offset: " + (offset) +
                    ". Valid range is [" + mStart + ", " + mEnd + "]");
        }
    }
}
