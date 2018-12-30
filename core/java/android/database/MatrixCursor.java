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

package android.database;

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import java.util.ArrayList;

/**
 * A mutable cursor implementation backed by an array of {@code Object}s. Use
 * {@link #newRow()} to add rows. Automatically expands internal capacity
 * as needed.
 */
public class MatrixCursor extends AbstractCursor {

    private final String[] columnNames;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Object[] data;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int rowCount = 0;
    private final int columnCount;

    /**
     * Constructs a new cursor with the given initial capacity.
     *
     * @param columnNames names of the columns, the ordering of which
     *  determines column ordering elsewhere in this cursor
     * @param initialCapacity in rows
     */
    public MatrixCursor(String[] columnNames, int initialCapacity) {
        this.columnNames = columnNames;
        this.columnCount = columnNames.length;

        if (initialCapacity < 1) {
            initialCapacity = 1;
        }

        this.data = new Object[columnCount * initialCapacity];
    }

    /**
     * Constructs a new cursor.
     *
     * @param columnNames names of the columns, the ordering of which
     *  determines column ordering elsewhere in this cursor
     */
    public MatrixCursor(String[] columnNames) {
        this(columnNames, 16);
    }

    /**
     * Gets value at the given column for the current row.
     */
    @UnsupportedAppUsage
    private Object get(int column) {
        if (column < 0 || column >= columnCount) {
            throw new CursorIndexOutOfBoundsException("Requested column: "
                    + column + ", # of columns: " +  columnCount);
        }
        if (mPos < 0) {
            throw new CursorIndexOutOfBoundsException("Before first row.");
        }
        if (mPos >= rowCount) {
            throw new CursorIndexOutOfBoundsException("After last row.");
        }
        return data[mPos * columnCount + column];
    }

    /**
     * Adds a new row to the end and returns a builder for that row. Not safe
     * for concurrent use.
     *
     * @return builder which can be used to set the column values for the new
     *  row
     */
    public RowBuilder newRow() {
        final int row = rowCount++;
        final int endIndex = rowCount * columnCount;
        ensureCapacity(endIndex);
        return new RowBuilder(row);
    }

    /**
     * Adds a new row to the end with the given column values. Not safe
     * for concurrent use.
     *
     * @throws IllegalArgumentException if {@code columnValues.length !=
     *  columnNames.length}
     * @param columnValues in the same order as the the column names specified
     *  at cursor construction time
     */
    public void addRow(Object[] columnValues) {
        if (columnValues.length != columnCount) {
            throw new IllegalArgumentException("columnNames.length = "
                    + columnCount + ", columnValues.length = "
                    + columnValues.length);
        }

        int start = rowCount++ * columnCount;
        ensureCapacity(start + columnCount);
        System.arraycopy(columnValues, 0, data, start, columnCount);
    }

    /**
     * Adds a new row to the end with the given column values. Not safe
     * for concurrent use.
     *
     * @throws IllegalArgumentException if {@code columnValues.size() !=
     *  columnNames.length}
     * @param columnValues in the same order as the the column names specified
     *  at cursor construction time
     */
    public void addRow(Iterable<?> columnValues) {
        int start = rowCount * columnCount;
        int end = start + columnCount;
        ensureCapacity(end);

        if (columnValues instanceof ArrayList<?>) {
            addRow((ArrayList<?>) columnValues, start);
            return;
        }

        int current = start;
        Object[] localData = data;
        for (Object columnValue : columnValues) {
            if (current == end) {
                // TODO: null out row?
                throw new IllegalArgumentException(
                        "columnValues.size() > columnNames.length");
            }
            localData[current++] = columnValue;
        }

        if (current != end) {
            // TODO: null out row?
            throw new IllegalArgumentException(
                    "columnValues.size() < columnNames.length");
        }

        // Increase row count here in case we encounter an exception.
        rowCount++;
    }

    /** Optimization for {@link ArrayList}. */
    private void addRow(ArrayList<?> columnValues, int start) {
        int size = columnValues.size();
        if (size != columnCount) {
            throw new IllegalArgumentException("columnNames.length = "
                    + columnCount + ", columnValues.size() = " + size);
        }

        rowCount++;
        Object[] localData = data;
        for (int i = 0; i < size; i++) {
            localData[start + i] = columnValues.get(i);
        }
    }

    /** Ensures that this cursor has enough capacity. */
    private void ensureCapacity(int size) {
        if (size > data.length) {
            Object[] oldData = this.data;
            int newSize = data.length * 2;
            if (newSize < size) {
                newSize = size;
            }
            this.data = new Object[newSize];
            System.arraycopy(oldData, 0, this.data, 0, oldData.length);
        }
    }

    /**
     * Builds a row of values using either of these approaches:
     * <ul>
     * <li>Values can be added with explicit column ordering using
     * {@link #add(Object)}, which starts from the left-most column and adds one
     * column value at a time. This follows the same ordering as the column
     * names specified at cursor construction time.
     * <li>Column and value pairs can be offered for possible inclusion using
     * {@link #add(String, Object)}. If the cursor includes the given column,
     * the value will be set for that column, otherwise the value is ignored.
     * This approach is useful when matching data to a custom projection.
     * </ul>
     * Undefined values are left as {@code null}.
     */
    public class RowBuilder {
        private final int row;
        private final int endIndex;

        private int index;

        RowBuilder(int row) {
            this.row = row;
            this.index = row * columnCount;
            this.endIndex = index + columnCount;
        }

        /**
         * Sets the next column value in this row.
         *
         * @throws CursorIndexOutOfBoundsException if you try to add too many
         *  values
         * @return this builder to support chaining
         */
        public RowBuilder add(Object columnValue) {
            if (index == endIndex) {
                throw new CursorIndexOutOfBoundsException(
                        "No more columns left.");
            }

            data[index++] = columnValue;
            return this;
        }

        /**
         * Offer value for possible inclusion if this cursor defines the given
         * column. Columns not defined by the cursor are silently ignored.
         *
         * @return this builder to support chaining
         */
        public RowBuilder add(String columnName, Object value) {
            for (int i = 0; i < columnNames.length; i++) {
                if (columnName.equals(columnNames[i])) {
                    data[(row * columnCount) + i] = value;
                }
            }
            return this;
        }
    }

    // AbstractCursor implementation.

    @Override
    public int getCount() {
        return rowCount;
    }

    @Override
    public String[] getColumnNames() {
        return columnNames;
    }

    @Override
    public String getString(int column) {
        Object value = get(column);
        if (value == null) return null;
        return value.toString();
    }

    @Override
    public short getShort(int column) {
        Object value = get(column);
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).shortValue();
        return Short.parseShort(value.toString());
    }

    @Override
    public int getInt(int column) {
        Object value = get(column);
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    @Override
    public long getLong(int column) {
        Object value = get(column);
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }

    @Override
    public float getFloat(int column) {
        Object value = get(column);
        if (value == null) return 0.0f;
        if (value instanceof Number) return ((Number) value).floatValue();
        return Float.parseFloat(value.toString());
    }

    @Override
    public double getDouble(int column) {
        Object value = get(column);
        if (value == null) return 0.0d;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    @Override
    public byte[] getBlob(int column) {
        Object value = get(column);
        return (byte[]) value;
    }

    @Override
    public int getType(int column) {
        return DatabaseUtils.getTypeOfObject(get(column));
    }

    @Override
    public boolean isNull(int column) {
        return get(column) == null;
    }
}
