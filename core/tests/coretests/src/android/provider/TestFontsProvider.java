/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.provider;

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Provides a test Content Provider implementing {@link FontsContract}.
 */
public class TestFontsProvider extends ContentProvider {
    static final String AUTHORITY = "android.provider.TestFontsProvider";
    static final int TTC_INDEX = 2;
    static final String VARIATION_SETTINGS = "'wdth' 1";
    static final int NORMAL_WEIGHT = 400;
    static final boolean ITALIC = false;

    private ParcelFileDescriptor mPfd;
    private boolean mReturnAllFields = true;
    private int mResultCode = FontsContract.Columns.RESULT_CODE_OK;
    private MatrixCursor mCustomCursor = null;

    /**
     * Used by tests to modify the result code that should be returned.
     */
    void setResultCode(int resultCode) {
        mResultCode = resultCode;
    }

    /**
     * Used by tests to switch whether all fields should be returned or not.
     */
    void setReturnAllFields(boolean returnAllFields) {
        mReturnAllFields = returnAllFields;
    }

    /**
     * Used by tests to control what values are returned.
     */
    void setCustomCursor(MatrixCursor cursor) {
        mCustomCursor = cursor;
    }

    @Override
    public boolean onCreate() {
        mPfd = createFontFile();
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        if (mCustomCursor != null) {
            return mCustomCursor;
        }
        MatrixCursor cursor;
        if (mReturnAllFields) {
            cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID,
                    FontsContract.Columns.TTC_INDEX, FontsContract.Columns.VARIATION_SETTINGS,
                    FontsContract.Columns.WEIGHT, FontsContract.Columns.ITALIC,
                    FontsContract.Columns.RESULT_CODE });
            cursor.addRow(new Object[] { 1, TTC_INDEX, VARIATION_SETTINGS, 400, 0, mResultCode });
        } else {
            cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID });
            cursor.addRow(new Object[] { 1 });
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        try {
            return mPfd.dup();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        return "application/x-font-ttf";
    }

    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        return 0;
    }

    private ParcelFileDescriptor createFontFile() {
        try {
            final File file = new File(getContext().getCacheDir(), "font.ttf");
            file.getParentFile().mkdirs();
            file.createNewFile();
            return ParcelFileDescriptor.open(file, MODE_READ_ONLY);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
