
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

import android.text.CharSequenceIterator;
import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextWatcher;

import java.text.BreakIterator;
import java.text.CharacterIterator;
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
    private CharSequence mCurrent;
    private boolean mCurrentDirty = false;

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

    private final TextWatcher mWatcher = new TextWatcher() {
        /** {@inheritDoc} */
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // ignored
        }

        /** {@inheritDoc} */
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            mCurrentDirty = true;
        }

        /** {@inheritDoc} */
        public void afterTextChanged(Editable s) {
            // ignored
        }
    };

    public void forceUpdate() {
        mCurrentDirty = true;
    }

    public void setCharSequence(CharSequence incoming) {
        // When incoming is different object, move listeners to new sequence
        // and mark as dirty so we reload contents.
        if (mCurrent != incoming) {
            if (mCurrent instanceof Editable) {
                ((Editable) mCurrent).removeSpan(mWatcher);
            }

            if (incoming instanceof Editable) {
                ((Editable) incoming).setSpan(
                        mWatcher, 0, incoming.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }

            mCurrent = incoming;
            mCurrentDirty = true;
        }

        if (mCurrentDirty) {
            final CharacterIterator charIterator = new CharSequenceIterator(mCurrent);
            mIterator.setText(charIterator);

            mCurrentDirty = false;
        }
    }

    /** {@inheritDoc} */
    public int preceding(int offset) {
        do {
            offset = mIterator.preceding(offset);
            if (offset == BreakIterator.DONE || isOnLetterOrDigit(offset)) {
                break;
            }
        } while (true);

        return offset;
    }

    /** {@inheritDoc} */
    public int following(int offset) {
        do {
            offset = mIterator.following(offset);
            if (offset == BreakIterator.DONE || isAfterLetterOrDigit(offset)) {
                break;
            }
        } while (true);

        return offset;
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
        checkOffsetIsValid(offset);

        if (isOnLetterOrDigit(offset)) {
            if (mIterator.isBoundary(offset)) {
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
        checkOffsetIsValid(offset);

        if (isAfterLetterOrDigit(offset)) {
            if (mIterator.isBoundary(offset)) {
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

    private boolean isAfterLetterOrDigit(int offset) {
        if (offset - 1 >= 0) {
            final char previousChar = mCurrent.charAt(offset - 1);
            if (Character.isLetterOrDigit(previousChar)) return true;
            if (offset - 2 >= 0) {
                final char previousPreviousChar = mCurrent.charAt(offset - 2);
                if (Character.isSurrogatePair(previousPreviousChar, previousChar)) {
                    final int codePoint = Character.toCodePoint(previousPreviousChar, previousChar);
                    return Character.isLetterOrDigit(codePoint);
                }
            }
        }
        return false;
    }

    private boolean isOnLetterOrDigit(int offset) {
        final int length = mCurrent.length();
        if (offset < length) {
            final char currentChar = mCurrent.charAt(offset);
            if (Character.isLetterOrDigit(currentChar)) return true;
            if (offset + 1 < length) {
                final char nextChar = mCurrent.charAt(offset + 1);
                if (Character.isSurrogatePair(currentChar, nextChar)) {
                    final int codePoint = Character.toCodePoint(currentChar, nextChar);
                    return Character.isLetterOrDigit(codePoint);
                }
            }
        }
        return false;
    }

    private void checkOffsetIsValid(int offset) {
        if (offset < 0 || offset > mCurrent.length()) {
            final String message = "Invalid offset: " + offset +
                    ". Valid range is [0, " + mCurrent.length() + "]";
            throw new IllegalArgumentException(message);
        }
    }
}
