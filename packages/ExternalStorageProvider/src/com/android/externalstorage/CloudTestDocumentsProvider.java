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
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsContract.RootColumns;
import android.provider.DocumentsContract.Roots;
import android.util.Log;

import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.List;

public class CloudTestDocumentsProvider extends ContentProvider {
    private static final String TAG = "CloudTest";

    private static final String AUTHORITY = "com.android.externalstorage.cloudtest";

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_ROOTS = 1;
    private static final int URI_ROOTS_ID = 2;
    private static final int URI_DOCS_ID = 3;
    private static final int URI_DOCS_ID_CONTENTS = 4;
    private static final int URI_DOCS_ID_SEARCH = 5;

    static {
        sMatcher.addURI(AUTHORITY, "roots", URI_ROOTS);
        sMatcher.addURI(AUTHORITY, "roots/*", URI_ROOTS_ID);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*", URI_DOCS_ID);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*/contents", URI_DOCS_ID_CONTENTS);
        sMatcher.addURI(AUTHORITY, "roots/*/docs/*/search", URI_DOCS_ID_SEARCH);
    }

    private static final String[] ALL_ROOTS_COLUMNS = new String[] {
            RootColumns.ROOT_ID, RootColumns.ROOT_TYPE, RootColumns.ICON, RootColumns.TITLE,
            RootColumns.SUMMARY, RootColumns.AVAILABLE_BYTES
    };

    private static final String[] ALL_DOCUMENTS_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    private List<String> mKnownDocs = Lists.newArrayList("meow.png", "kittens.pdf");

    private int mPage;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                includeDefaultRoot(result);
                return result;
            }
            case URI_ROOTS_ID: {
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                includeDefaultRoot(result);
                return result;
            }
            case URI_DOCS_ID: {
                final String docId = DocumentsContract.getDocId(uri);
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);
                includeDoc(result, docId);
                return result;
            }
            case URI_DOCS_ID_CONTENTS: {
                final CloudCursor result = new CloudCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS, uri);
                for (String docId : mKnownDocs) {
                    includeDoc(result, docId);
                }
                if (mPage < 3) {
                    result.setHasMore();
                }
                result.setNotificationUri(getContext().getContentResolver(), uri);
                return result;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private void includeDefaultRoot(MatrixCursor result) {
        final RowBuilder row = result.newRow();
        row.offer(RootColumns.ROOT_ID, "testroot");
        row.offer(RootColumns.ROOT_TYPE, Roots.ROOT_TYPE_SERVICE);
        row.offer(RootColumns.TITLE, "_TestTitle");
        row.offer(RootColumns.SUMMARY, "_TestSummary");
    }

    private void includeDoc(MatrixCursor result, String docId) {
        int flags = 0;

        final String mimeType;
        if (Documents.DOC_ID_ROOT.equals(docId)) {
            mimeType = Documents.MIME_TYPE_DIR;
        } else {
            mimeType = "application/octet-stream";
        }

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, docId);
        row.offer(DocumentColumns.MIME_TYPE, mimeType);
        row.offer(DocumentColumns.LAST_MODIFIED, System.currentTimeMillis());
        row.offer(DocumentColumns.FLAGS, flags);
    }

    private class CloudCursor extends MatrixCursor {
        private final Uri mUri;
        private Bundle mExtras = new Bundle();

        public CloudCursor(String[] columnNames, Uri uri) {
            super(columnNames);
            mUri = uri;
        }

        public void setHasMore() {
            mExtras.putBoolean(DocumentsContract.EXTRA_HAS_MORE, true);
        }

        @Override
        public Bundle getExtras() {
            Log.d(TAG, "getExtras() " + mExtras);
            return mExtras;
        }

        @Override
        public Bundle respond(Bundle extras) {
            extras.size();
            Log.d(TAG, "respond() " + extras);
            if (extras.getBoolean(DocumentsContract.EXTRA_REQUEST_MORE, false)) {
                new CloudTask().execute(mUri);
            }
            return Bundle.EMPTY;
        }
    }

    private class CloudTask extends AsyncTask<Uri, Void, Void> {
        @Override
        protected Void doInBackground(Uri... uris) {
            final Uri uri = uris[0];

            SystemClock.sleep(1000);

            // Grab some files from the cloud
            for (int i = 0; i < 5; i++) {
                mKnownDocs.add("cloud-page" + mPage + "-file" + i);
            }
            mPage++;

            Log.d(TAG, "Loaded more; notifying " + uri);
            getContext().getContentResolver().notifyChange(uri, null, false);
            return null;
        }
    }

    private interface TypeQuery {
        final String[] PROJECTION = {
                DocumentColumns.MIME_TYPE };

        final int MIME_TYPE = 0;
    }

    @Override
    public String getType(Uri uri) {
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                return Roots.MIME_TYPE_DIR;
            }
            case URI_ROOTS_ID: {
                return Roots.MIME_TYPE_ITEM;
            }
            case URI_DOCS_ID: {
                final Cursor cursor = query(uri, TypeQuery.PROJECTION, null, null, null);
                try {
                    if (cursor.moveToFirst()) {
                        return cursor.getString(TypeQuery.MIME_TYPE);
                    } else {
                        return null;
                    }
                } finally {
                    IoUtils.closeQuietly(cursor);
                }
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Unsupported Uri " + uri);
    }
}
