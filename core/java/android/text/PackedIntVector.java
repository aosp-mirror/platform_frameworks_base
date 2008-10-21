/*
 * Copyright (C) 2007 The Android Open Source Project
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


/**
 * PackedIntVector stores a two-dimensional array of integers,
 * optimized for inserting and deleting rows and for
 * offsetting the values in segments of a given column.
 */
class PackedIntVector {
    private final int mColumns;
    private int mRows;

    private int mRowGapStart;
    private int mRowGapLength;

    private int[] mValues;
    private int[] mValueGap; // starts, followed by lengths

    /**
     * Creates a new PackedIntVector with the specified width and
     * a height of 0.
     *
     * @param columns the width of the PackedIntVector.
     */
    public PackedIntVector(int columns) {
        mColumns = columns;
        mRows = 0;

        mRowGapStart = 0;
        mRowGapLength = mRows;

        mValues = null;
        mValueGap = new int[2 * columns];
    }

    /**
     * Returns the value at the specified row and column.
     *
     * @param row the index of the row to return.
     * @param column the index of the column to return.
     *
     * @return the value stored at the specified position.
     *
     * @throws IndexOutOfBoundsException if the row is out of range
     *         (row &lt; 0 || row >= size()) or the column is out of range
     *         (column &lt; 0 || column >= width()).
     */
    public int getValue(int row, int column) {
        final int columns = mColumns;

        if (((row | column) < 0) || (row >= size()) || (column >= columns)) {
            throw new IndexOutOfBoundsException(row + ", " + column);
        }

        if (row >= mRowGapStart) {
            row += mRowGapLength;
        }

        int value = mValues[row * columns + column];

        int[] valuegap = mValueGap;
        if (row >= valuegap[column]) {
            value += valuegap[column + columns];
        }

        return value;
    }

    /**
     * Sets the value at the specified row and column.
     *
     * @param row the index of the row to set.
     * @param column the index of the column to set.
     *
     * @throws IndexOutOfBoundsException if the row is out of range
     *         (row &lt; 0 || row >= size()) or the column is out of range
     *         (column &lt; 0 || column >= width()).
     */
    public void setValue(int row, int column, int value) {
        if (((row | column) < 0) || (row >= size()) || (column >= mColumns)) {
            throw new IndexOutOfBoundsException(row + ", " + column);
        }

        if (row >= mRowGapStart) {
            row += mRowGapLength;
        }

        int[] valuegap = mValueGap;
        if (row >= valuegap[column]) {
            value -= valuegap[column + mColumns];
        }

        mValues[row * mColumns + column] = value;
    }

    /**
     * Sets the value at the specified row and column.
     * Private internal version: does not check args.
     *
     * @param row the index of the row to set.
     * @param column the index of the column to set.
     *
     */
    private void setValueInternal(int row, int column, int value) {
        if (row >= mRowGapStart) {
            row += mRowGapLength;
        }

        int[] valuegap = mValueGap;
        if (row >= valuegap[column]) {
            value -= valuegap[column + mColumns];
        }

        mValues[row * mColumns + column] = value;
    }


    /**
     * Increments all values in the specified column whose row >= the
     * specified row by the specified delta.
     *
     * @param startRow the row at which to begin incrementing.
     *        This may be == size(), which case there is no effect.
     * @param column the index of the column to set.
     *
     * @throws IndexOutOfBoundsException if the row is out of range
     *         (startRow &lt; 0 || startRow > size()) or the column
     *         is out of range (column &lt; 0 || column >= width()).
     */
    public void adjustValuesBelow(int startRow, int column, int delta) {
        if (((startRow | column) < 0) || (startRow > size()) ||
                (column >= width())) {
            throw new IndexOutOfBoundsException(startRow + ", " + column);
        }

        if (startRow >= mRowGapStart) {
            startRow += mRowGapLength;
        }

        moveValueGapTo(column, startRow);
        mValueGap[column + mColumns] += delta;
    }

    /**
     * Inserts a new row of values at the specified row offset.
     *
     * @param row the row above which to insert the new row.
     *        This may be == size(), which case the new row is added
     *        at the end.
     * @param values the new values to be added.  If this is null,
     *        a row of zeroes is added.
     *
     * @throws IndexOutOfBoundsException if the row is out of range
     *         (row &lt; 0 || row > size()) or if the length of the
     *         values array is too small (values.length < width()).
     */
    public void insertAt(int row, int[] values) {
        if ((row < 0) || (row > size())) {
            throw new IndexOutOfBoundsException("row " + row);
        }

        if ((values != null) && (values.length < width())) {
            throw new IndexOutOfBoundsException("value count " + values.length);
        }

        moveRowGapTo(row);

        if (mRowGapLength == 0) {
            growBuffer();
        }

        mRowGapStart++;
        mRowGapLength--;

        if (values == null) {
            for (int i = mColumns - 1; i >= 0; i--) {
                setValueInternal(row, i, 0);
            }
        } else {
            for (int i = mColumns - 1; i >= 0; i--) {
                setValueInternal(row, i, values[i]);
            }
        }
    }

    /**
     * Deletes the specified number of rows starting with the specified
     * row.
     *
     * @param row the index of the first row to be deleted.
     * @param count the number of rows to delete.
     *
     * @throws IndexOutOfBoundsException if any of the rows to be deleted
     *         are out of range (row &lt; 0 || count &lt; 0 ||
     *         row + count > size()).
     */
    public void deleteAt(int row, int count) {
        if (((row | count) < 0) || (row + count > size())) {
            throw new IndexOutOfBoundsException(row + ", " + count);
        }

        moveRowGapTo(row + count);

        mRowGapStart -= count;
        mRowGapLength += count;

        // TODO: Reclaim memory when the new height is much smaller
        // than the allocated size.
    }

    /**
     * Returns the number of rows in the PackedIntVector.  This number
     * will change as rows are inserted and deleted.
     *
     * @return the number of rows.
     */
    public int size() {
        return mRows - mRowGapLength;
    }

    /**
     * Returns the width of the PackedIntVector.  This number is set
     * at construction and will not change.
     *
     * @return the number of columns.
     */
    public int width() {
        return mColumns;
    }

    /**
     * Grows the value and gap arrays to be large enough to store at least
     * one more than the current number of rows.
     */
    private final void growBuffer() {
        final int columns = mColumns;
        int newsize = size() + 1;
        newsize = ArrayUtils.idealIntArraySize(newsize * columns) / columns;
        int[] newvalues = new int[newsize * columns];

        final int[] valuegap = mValueGap;
        final int rowgapstart = mRowGapStart;

        int after = mRows - (rowgapstart + mRowGapLength);

        if (mValues != null) {
            System.arraycopy(mValues, 0, newvalues, 0, columns * rowgapstart);
            System.arraycopy(mValues, (mRows - after) * columns,
                             newvalues, (newsize - after) * columns,
                             after * columns);
        }

        for (int i = 0; i < columns; i++) {
            if (valuegap[i] >= rowgapstart) {
                valuegap[i] += newsize - mRows;

                if (valuegap[i] < rowgapstart) {
                    valuegap[i] = rowgapstart;
                }
            }
        }

        mRowGapLength += newsize - mRows;
        mRows = newsize;
        mValues = newvalues;
    }

    /**
     * Moves the gap in the values of the specified column to begin at
     * the specified row.
     */
    private final void moveValueGapTo(int column, int where) {
        final int[] valuegap = mValueGap;
        final int[] values = mValues;
        final int columns = mColumns;

        if (where == valuegap[column]) {
            return;
        } else if (where > valuegap[column]) {
            for (int i = valuegap[column]; i < where; i++) {
                values[i * columns + column] += valuegap[column + columns];
            }
        } else /* where < valuegap[column] */ {
            for (int i = where; i < valuegap[column]; i++) {
                values[i * columns + column] -= valuegap[column + columns];
            }
        }

        valuegap[column] = where;
    }

    /**
     * Moves the gap in the row indices to begin at the specified row.
     */
    private final void moveRowGapTo(int where) {
        if (where == mRowGapStart) {
            return;
        } else if (where > mRowGapStart) {
            int moving = where + mRowGapLength - (mRowGapStart + mRowGapLength);
            final int columns = mColumns;
            final int[] valuegap = mValueGap;
            final int[] values = mValues;
            final int gapend = mRowGapStart + mRowGapLength;

            for (int i = gapend; i < gapend + moving; i++) {
                int destrow = i - gapend + mRowGapStart;

                for (int j = 0; j < columns; j++) {
                    int val = values[i * columns+ j];

                    if (i >= valuegap[j]) {
                        val += valuegap[j + columns];
                    }

                    if (destrow >= valuegap[j]) {
                        val -= valuegap[j + columns];
                    }

                    values[destrow * columns + j] = val;
                }
            }
        } else /* where < mRowGapStart */ {
            int moving = mRowGapStart - where;
            final int columns = mColumns;
            final int[] valuegap = mValueGap;
            final int[] values = mValues;
            final int gapend = mRowGapStart + mRowGapLength;

            for (int i = where + moving - 1; i >= where; i--) {
                int destrow = i - where + gapend - moving;

                for (int j = 0; j < columns; j++) {
                    int val = values[i * columns+ j];

                    if (i >= valuegap[j]) {
                        val += valuegap[j + columns];
                    }

                    if (destrow >= valuegap[j]) {
                        val -= valuegap[j + columns];
                    }

                    values[destrow * columns + j] = val;
                }
            }
        }

        mRowGapStart = where;
    }
}
