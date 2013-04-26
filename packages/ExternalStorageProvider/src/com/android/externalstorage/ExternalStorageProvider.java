/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.externalstorage;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.webkit.MimeTypeMap;

import com.android.internal.annotations.GuardedBy;
import com.google.android.collect.Lists;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;

public class ExternalStorageProvider extends ContentProvider {
    private static final String TAG = "ExternalStorage";

    private static final String AUTHORITY = "com.android.externalstorage";

    // TODO: support searching
    // TODO: support multiple storage devices
    // TODO: persist GUIDs across launches

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_DOCS_ID = 1;
    private static final int URI_DOCS_ID_CONTENTS = 2;
    private static final int URI_SEARCH = 3;

    static {
        sMatcher.addURI(AUTHORITY, "docs/#", URI_DOCS_ID);
        sMatcher.addURI(AUTHORITY, "docs/#/contents", URI_DOCS_ID_CONTENTS);
        sMatcher.addURI(AUTHORITY, "search", URI_SEARCH);
    }

    @GuardedBy("mFiles")
    private ArrayList<File> mFiles = Lists.newArrayList();

    @Override
    public boolean onCreate() {
        mFiles.clear();
        mFiles.add(Environment.getExternalStorageDirectory());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {

        // TODO: support custom projections
        projection = new String[] {
                BaseColumns._ID,
                DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE, DocumentColumns.GUID,
                DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS };

        final MatrixCursor cursor = new MatrixCursor(projection);
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final int id = Integer.parseInt(uri.getPathSegments().get(1));
                synchronized (mFiles) {
                    includeFileLocked(cursor, id);
                }
                break;
            }
            case URI_DOCS_ID_CONTENTS: {
                final int parentId = Integer.parseInt(uri.getPathSegments().get(1));
                synchronized (mFiles) {
                    final File parent = mFiles.get(parentId);
                    for (File file : parent.listFiles()) {
                        final int id = findOrCreateFileLocked(file);
                        includeFileLocked(cursor, id);
                    }
                }
                break;
            }
            default: {
                cursor.close();
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }

        return cursor;
    }

    private int findOrCreateFileLocked(File file) {
        int id = mFiles.indexOf(file);
        if (id == -1) {
            id = mFiles.size();
            mFiles.add(file);
        }
        return id;
    }

    private void includeFileLocked(MatrixCursor cursor, int id) {
        final File file = mFiles.get(id);
        int flags = 0;

        if (file.isDirectory() && file.canWrite()) {
            flags |= DocumentsContract.FLAG_SUPPORTS_CREATE;
        }
        if (file.canWrite()) {
            flags |= DocumentsContract.FLAG_SUPPORTS_RENAME;
        }

        final String mimeType = getTypeLocked(id);
        if (mimeType.startsWith("image/")) {
            flags |= DocumentsContract.FLAG_SUPPORTS_THUMBNAIL;
        }

        cursor.addRow(new Object[] {
                id, file.getName(), file.length(), id, mimeType, file.lastModified(), flags });
    }

    @Override
    public String getType(Uri uri) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final int id = Integer.parseInt(uri.getPathSegments().get(1));
                synchronized (mFiles) {
                    return getTypeLocked(id);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private String getTypeLocked(int id) {
        final File file = mFiles.get(id);

        if (file.isDirectory()) {
            return DocumentsContract.MIME_TYPE_DIRECTORY;
        }

        final int lastDot = file.getName().lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = file.getName().substring(lastDot + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }

        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final int id = Integer.parseInt(uri.getPathSegments().get(1));
                synchronized (mFiles) {
                    final File file = mFiles.get(id);
                    // TODO: turn into thumbnail
                    return ParcelFileDescriptor.open(file, ContentResolver.modeToMode(uri, mode));
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
