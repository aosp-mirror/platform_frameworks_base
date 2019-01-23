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
import android.content.ContentResolver;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.CancellationSignal;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * Cursor that supports deprecation of {@code _data} like columns which represent raw filepaths,
 * typically by replacing values with fake paths that the OS then offers to redirect to
 * {@link ContentResolver#openFileDescriptor(Uri, String)}, which developers
 * should be using directly.
 *
 * @hide
 */
public class TranslatingCursor extends CrossProcessCursorWrapper {
    public static class Config {
        public final Uri baseUri;
        public final String auxiliaryColumn;
        public final String[] translateColumns;

        public Config(Uri baseUri, String auxiliaryColumn, String... translateColumns) {
            this.baseUri = baseUri;
            this.auxiliaryColumn = auxiliaryColumn;
            this.translateColumns = translateColumns;
        }
    }

    public interface Translator {
        String translate(String data, int auxiliaryColumnIndex,
                String matchingColumn, Cursor cursor);
    }

    private final @NonNull Config mConfig;
    private final @NonNull Translator mTranslator;
    private final boolean mDropLast;

    private final int mAuxiliaryColumnIndex;
    private final int[] mTranslateColumnIndices;

    public TranslatingCursor(@NonNull Cursor cursor, @NonNull Config config,
            @NonNull Translator translator, boolean dropLast) {
        super(cursor);

        mConfig = Objects.requireNonNull(config);
        mTranslator = Objects.requireNonNull(translator);
        mDropLast = dropLast;

        mAuxiliaryColumnIndex = cursor.getColumnIndexOrThrow(config.auxiliaryColumn);
        mTranslateColumnIndices = new int[config.translateColumns.length];
        for (int i = 0; i < mTranslateColumnIndices.length; ++i) {
            mTranslateColumnIndices[i] = cursor.getColumnIndex(config.translateColumns[i]);
        }
    }

    @Override
    public int getColumnCount() {
        if (mDropLast) {
            return super.getColumnCount() - 1;
        } else {
            return super.getColumnCount();
        }
    }

    @Override
    public String[] getColumnNames() {
        if (mDropLast) {
            return Arrays.copyOfRange(super.getColumnNames(), 0, super.getColumnCount() - 1);
        } else {
            return super.getColumnNames();
        }
    }

    public static Cursor query(@NonNull Config config, @NonNull Translator translator,
            SQLiteQueryBuilder qb, SQLiteDatabase db, String[] projectionIn, String selection,
            String[] selectionArgs, String groupBy, String having, String sortOrder, String limit,
            CancellationSignal signal) {
        final boolean requestedAuxiliaryColumn = ArrayUtils.isEmpty(projectionIn)
                || ArrayUtils.contains(projectionIn, config.auxiliaryColumn);
        final boolean requestedTranslateColumns = ArrayUtils.isEmpty(projectionIn)
                || ArrayUtils.containsAny(projectionIn, config.translateColumns);

        // If caller didn't request any columns that need to be translated,
        // we have nothing to redirect
        if (!requestedTranslateColumns) {
            return qb.query(db, projectionIn, selection, selectionArgs,
                    groupBy, having, sortOrder, limit, signal);
        }

        // If caller didn't request auxiliary column, we need to splice it in
        if (!requestedAuxiliaryColumn) {
            projectionIn = ArrayUtils.appendElement(String.class, projectionIn,
                    config.auxiliaryColumn);
        }

        final Cursor c = qb.query(db, projectionIn, selection, selectionArgs,
                groupBy, having, sortOrder);
        return new TranslatingCursor(c, config, translator, !requestedAuxiliaryColumn);
    }

    @Override
    public void fillWindow(int position, CursorWindow window) {
        // Fill window directly to ensure data is rewritten
        DatabaseUtils.cursorFillWindow(this, position, window);
    }

    @Override
    public CursorWindow getWindow() {
        // Returning underlying window risks leaking data
        return null;
    }

    @Override
    public Cursor getWrappedCursor() {
        throw new UnsupportedOperationException(
                "Returning underlying cursor risks leaking data");
    }

    @Override
    public double getDouble(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            return super.getDouble(columnIndex);
        }
    }

    @Override
    public float getFloat(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            return super.getFloat(columnIndex);
        }
    }

    @Override
    public int getInt(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            return super.getInt(columnIndex);
        }
    }

    @Override
    public long getLong(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            return super.getLong(columnIndex);
        }
    }

    @Override
    public short getShort(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            return super.getShort(columnIndex);
        }
    }

    @Override
    public String getString(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            return mTranslator.translate(super.getString(columnIndex),
                    mAuxiliaryColumnIndex, getColumnName(columnIndex), this);
        } else {
            return super.getString(columnIndex);
        }
    }

    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            super.copyStringToBuffer(columnIndex, buffer);
        }
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            throw new IllegalArgumentException();
        } else {
            return super.getBlob(columnIndex);
        }
    }

    @Override
    public int getType(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            return Cursor.FIELD_TYPE_STRING;
        } else {
            return super.getType(columnIndex);
        }
    }

    @Override
    public boolean isNull(int columnIndex) {
        if (ArrayUtils.contains(mTranslateColumnIndices, columnIndex)) {
            return getString(columnIndex) == null;
        } else {
            return super.isNull(columnIndex);
        }
    }
}
