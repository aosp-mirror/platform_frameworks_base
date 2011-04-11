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

import java.text.CharacterIterator;

/** {@hide} */
public class CharSequenceIterator implements CharacterIterator {
    private final CharSequence mValue;

    private final int mLength;
    private int mIndex;

    public CharSequenceIterator(CharSequence value) {
        mValue = value;
        mLength = value.length();
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
        if (mIndex == mLength) {
            return DONE;
        }
        return mValue.charAt(mIndex);
    }

    /** {@inheritDoc} */
    public int getBeginIndex() {
        return 0;
    }

    /** {@inheritDoc} */
    public int getEndIndex() {
        return mLength;
    }

    /** {@inheritDoc} */
    public int getIndex() {
        return mIndex;
    }

    /** {@inheritDoc} */
    public char first() {
        return setIndex(0);
    }

    /** {@inheritDoc} */
    public char last() {
        return setIndex(mLength - 1);
    }

    /** {@inheritDoc} */
    public char next() {
        if (mIndex == mLength) {
            return DONE;
        }
        return setIndex(mIndex + 1);
    }

    /** {@inheritDoc} */
    public char previous() {
        if (mIndex == 0) {
            return DONE;
        }
        return setIndex(mIndex - 1);
    }

    /** {@inheritDoc} */
    public char setIndex(int index) {
        if ((index < 0) || (index > mLength)) {
            throw new IllegalArgumentException("Valid range is [" + 0 + "..." + mLength + "]");
        }
        mIndex = index;
        return current();
    }
}
