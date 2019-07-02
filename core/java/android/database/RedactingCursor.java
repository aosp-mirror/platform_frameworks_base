/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.util.SparseArray;

import java.util.Map;

/**
 * Cursor that offers to redact values of requested columns.
 *
 * @hide
 */
public class RedactingCursor extends CrossProcessCursorWrapper {
    private final SparseArray<Object> mRedactions;

    private RedactingCursor(@NonNull Cursor cursor, SparseArray<Object> redactions) {
        super(cursor);
        mRedactions = redactions;
    }

    /**
     * Create a wrapped instance of the given {@link Cursor} which redacts the
     * requested columns so they always return specific values when accessed.
     * <p>
     * If a redacted column appears multiple times in the underlying cursor, all
     * instances will be redacted. If none of the redacted columns appear in the
     * given cursor, the given cursor will be returned untouched to improve
     * performance.
     */
    public static Cursor create(@NonNull Cursor cursor, @NonNull Map<String, Object> redactions) {
        final SparseArray<Object> internalRedactions = new SparseArray<>();

        final String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            if (redactions.containsKey(columns[i])) {
                internalRedactions.put(i, redactions.get(columns[i]));
            }
        }

        if (internalRedactions.size() == 0) {
            return cursor;
        } else {
            return new RedactingCursor(cursor, internalRedactions);
        }
    }

    @Override
    public void fillWindow(int position, CursorWindow window) {
        // Fill window directly to ensure data is redacted
        DatabaseUtils.cursorFillWindow(this, position, window);
    }

    @Override
    public CursorWindow getWindow() {
        // Returning underlying window risks leaking redacted data
        return null;
    }

    @Override
    public Cursor getWrappedCursor() {
        throw new UnsupportedOperationException(
                "Returning underlying cursor risks leaking redacted data");
    }

    @Override
    public double getDouble(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (double) mRedactions.valueAt(i);
        } else {
            return super.getDouble(columnIndex);
        }
    }

    @Override
    public float getFloat(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (float) mRedactions.valueAt(i);
        } else {
            return super.getFloat(columnIndex);
        }
    }

    @Override
    public int getInt(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (int) mRedactions.valueAt(i);
        } else {
            return super.getInt(columnIndex);
        }
    }

    @Override
    public long getLong(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (long) mRedactions.valueAt(i);
        } else {
            return super.getLong(columnIndex);
        }
    }

    @Override
    public short getShort(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (short) mRedactions.valueAt(i);
        } else {
            return super.getShort(columnIndex);
        }
    }

    @Override
    public String getString(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (String) mRedactions.valueAt(i);
        } else {
            return super.getString(columnIndex);
        }
    }

    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            buffer.data = ((String) mRedactions.valueAt(i)).toCharArray();
            buffer.sizeCopied = buffer.data.length;
        } else {
            super.copyStringToBuffer(columnIndex, buffer);
        }
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return (byte[]) mRedactions.valueAt(i);
        } else {
            return super.getBlob(columnIndex);
        }
    }

    @Override
    public int getType(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return DatabaseUtils.getTypeOfObject(mRedactions.valueAt(i));
        } else {
            return super.getType(columnIndex);
        }
    }

    @Override
    public boolean isNull(int columnIndex) {
        final int i = mRedactions.indexOfKey(columnIndex);
        if (i >= 0) {
            return mRedactions.valueAt(i) == null;
        } else {
            return super.isNull(columnIndex);
        }
    }
}
