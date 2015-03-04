/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.am;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class DumpHeapProvider extends ContentProvider {
    static final Object sLock = new Object();
    static File sHeapDumpJavaFile;

    static public File getJavaFile() {
        synchronized (sLock) {
            return sHeapDumpJavaFile;
        }
    }

    @Override
    public boolean onCreate() {
        synchronized (sLock) {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            File heapdumpDir = new File(systemDir, "heapdump");
            heapdumpDir.mkdir();
            sHeapDumpJavaFile = new File(heapdumpDir, "javaheap.bin");
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        synchronized (sLock) {
            String path = uri.getEncodedPath();
            final String tag = Uri.decode(path);
            if (tag.equals("/java")) {
                return ParcelFileDescriptor.open(sHeapDumpJavaFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
            } else {
                throw new FileNotFoundException("Invalid path for " + uri);
            }
        }
    }
}
