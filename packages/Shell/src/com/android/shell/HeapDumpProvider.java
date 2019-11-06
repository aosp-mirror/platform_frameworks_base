/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.shell;

import android.annotation.NonNull;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;

/** ContentProvider to write and access heap dumps. */
public class HeapDumpProvider extends ContentProvider {
    private static final String FILENAME_SUFFIX = "_javaheap.bin";
    private static final Object sLock = new Object();

    private File mRoot;

    @Override
    public boolean onCreate() {
        synchronized (sLock) {
            mRoot = new File(getContext().createCredentialProtectedStorageContext().getFilesDir(),
                    "heapdumps");
            return mRoot.mkdir();
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert not allowed.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        String path = sanitizePath(uri.getEncodedPath());
        String tag = Uri.decode(path);
        return (new File(mRoot, tag)).delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not allowed.");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = sanitizePath(uri.getEncodedPath());
        String tag = Uri.decode(path);
        final int pMode;
        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            pMode = ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE
                    | ParcelFileDescriptor.MODE_WRITE_ONLY;
        } else {
            pMode = ParcelFileDescriptor.MODE_READ_ONLY;
        }

        synchronized (sLock) {
            return ParcelFileDescriptor.open(new File(mRoot, tag), pMode);
        }
    }

    @NonNull
    static Uri makeUri(@NonNull String procName) {
        return Uri.parse("content://com.android.shell.heapdump/" + procName + FILENAME_SUFFIX);
    }

    private String sanitizePath(String path) {
        return path.replaceAll("[^a-zA-Z0-9_.]", "");
    }
}
