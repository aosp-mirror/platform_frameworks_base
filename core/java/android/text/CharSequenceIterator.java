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

package android.text;

import android.util.MathUtils;

import java.text.CharacterIterator;

/** {@hide} */
public class CharSequenceIterator implements CharacterIterator {
    private final CharSequence mValue;

    private final int mStart;
    private final int mEnd;
    private int mIndex;

    public CharSequenceIterator(CharSequence value) {
        mValue = value;
        mStart = 0;
        mEnd = value.length();
        mIndex = 0;
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** {@inheritDoc} */
    public char current() {
        if (mIndex == mEnd) {
            return DONE;
        }
        return mValue.charAt(mIndex);
    }

    /** {@inheritDoc} */
    public int getBeginIndex() {
        return mStart;
    }

    /** {@inheritDoc} */
    public int getEndIndex() {
        return mEnd;
    }

    /** {@inheritDoc} */
    public int getIndex() {
        return mIndex;
    }

    /** {@inheritDoc} */
    public char first() {
        return setIndex(mStart);
    }

    /** {@inheritDoc} */
    public char last() {
        return setIndex(mEnd - 1);
    }

    /** {@inheritDoc} */
    public char next() {
        return setIndex(mIndex + 1);
    }

    /** {@inheritDoc} */
    public char previous() {
        return setIndex(mIndex - 1);
    }

    /** {@inheritDoc} */
    public char setIndex(int index) {
        mIndex = MathUtils.constrain(index, mStart, mEnd);
        return current();
    }
}
