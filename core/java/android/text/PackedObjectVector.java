/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import libcore.util.EmptyArray;

class PackedObjectVector<E>
{
    private int mColumns;
    private int mRows;

    private int mRowGapStart;
    private int mRowGapLength;

    private Object[] mValues;

    public
    PackedObjectVector(int columns)
    {
        mColumns = columns;
        mValues = EmptyArray.OBJECT;
        mRows = 0;

        mRowGapStart = 0;
        mRowGapLength = mRows;
    }

    public E
    getValue(int row, int column)
    {
        if (row >= mRowGapStart)
            row += mRowGapLength;

        Object value = mValues[row * mColumns + column];

        return (E) value;
    }

    public void
    setValue(int row, int column, E value)
    {
        if (row >= mRowGapStart)
            row += mRowGapLength;

        mValues[row * mColumns + column] = value;
    }

    public void
    insertAt(int row, E[] values)
    {
        moveRowGapTo(row);

        if (mRowGapLength == 0)
            growBuffer();

        mRowGapStart++;
        mRowGapLength--;

        if (values == null)
            for (int i = 0; i < mColumns; i++)
                setValue(row, i, null);
        else
            for (int i = 0; i < mColumns; i++)
                setValue(row, i, values[i]);
    }

    public void
    deleteAt(int row, int count)
    {
        moveRowGapTo(row + count);

        mRowGapStart -= count;
        mRowGapLength += count;

        if (mRowGapLength > size() * 2)
        {
            // dump();
            // growBuffer();
        }
    }

    public int
    size()
    {
        return mRows - mRowGapLength;
    }

    public int
    width()
    {
        return mColumns;
    }

    private void
    growBuffer()
    {
        Object[] newvalues = ArrayUtils.newUnpaddedObjectArray(
                GrowingArrayUtils.growSize(size()) * mColumns);
        int newsize = newvalues.length / mColumns;
        int after = mRows - (mRowGapStart + mRowGapLength);

        System.arraycopy(mValues, 0, newvalues, 0, mColumns * mRowGapStart);
        System.arraycopy(mValues, (mRows - after) * mColumns, newvalues, (newsize - after) * mColumns, after * mColumns);

        mRowGapLength += newsize - mRows;
        mRows = newsize;
        mValues = newvalues;
    }

    private void
    moveRowGapTo(int where)
    {
        if (where == mRowGapStart)
            return;

        if (where > mRowGapStart)
        {
            int moving = where + mRowGapLength - (mRowGapStart + mRowGapLength);

            for (int i = mRowGapStart + mRowGapLength; i < mRowGapStart + mRowGapLength + moving; i++)
            {
                int destrow = i - (mRowGapStart + mRowGapLength) + mRowGapStart;

                for (int j = 0; j < mColumns; j++)
                {
                    Object val = mValues[i * mColumns + j];

                    mValues[destrow * mColumns + j] = val;
                }
            }
        }
        else /* where < mRowGapStart */
        {
            int moving = mRowGapStart - where;

            for (int i = where + moving - 1; i >= where; i--)
            {
                int destrow = i - where + mRowGapStart + mRowGapLength - moving;

                for (int j = 0; j < mColumns; j++)
                {
                    Object val = mValues[i * mColumns + j];

                    mValues[destrow * mColumns + j] = val;
                }
            }
        }

        mRowGapStart = where;
    }

    public void // XXX
    dump()
    {
        for (int i = 0; i < mRows; i++)
        {
            for (int j = 0; j < mColumns; j++)
            {
                Object val = mValues[i * mColumns + j];

                if (i < mRowGapStart || i >= mRowGapStart + mRowGapLength)
                    System.out.print(val + " ");
                else
                    System.out.print("(" + val + ") ");
            }

            System.out.print(" << \n");
        }

        System.out.print("-----\n\n");
    }
}
