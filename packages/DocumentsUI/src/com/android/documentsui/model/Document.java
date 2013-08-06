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

package com.android.documentsui.model;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;

import com.android.documentsui.RecentsProvider;

import java.util.Comparator;

/**
 * Representation of a single document.
 */
public class Document {
    public final Uri uri;
    public final String mimeType;
    public final String displayName;
    public final long lastModified;
    public final int flags;

    private Document(Uri uri, String mimeType, String displayName, long lastModified, int flags) {
        this.uri = uri;
        this.mimeType = mimeType;
        this.displayName = displayName;
        this.lastModified = lastModified;
        this.flags = flags;
    }

    public static Document fromRoot(ContentResolver resolver, Root root) {
        if (root.isRecents) {
            final Uri uri = root.uri;
            final String mimeType = DocumentsContract.MIME_TYPE_DIRECTORY;
            final String displayName = root.title;
            final long lastModified = -1;
            final int flags = 0;
            return new Document(uri, mimeType, displayName, lastModified, flags);
        } else {
            return fromUri(resolver, root.uri);
        }
    }

    public static Document fromDirectoryCursor(Uri parent, Cursor cursor) {
        final String authority = parent.getAuthority();
        final String rootId = DocumentsContract.getRootId(parent);
        final String docId = getCursorString(cursor, DocumentColumns.DOC_ID);

        final Uri uri = DocumentsContract.buildDocumentUri(authority, rootId, docId);
        final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
        final String displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
        final long lastModified = getCursorLong(cursor, DocumentColumns.LAST_MODIFIED);
        final int flags = getCursorInt(cursor, DocumentColumns.FLAGS);

        return new Document(uri, mimeType, displayName, lastModified, flags);
    }

    public static Document fromRecentOpenCursor(ContentResolver resolver, Cursor cursor) {
        final Uri uri = Uri.parse(getCursorString(cursor, RecentsProvider.COL_URI));
        final long lastModified = getCursorLong(cursor, RecentsProvider.COL_TIMESTAMP);

        final Cursor itemCursor = resolver.query(uri, null, null, null, null);
        try {
            if (!itemCursor.moveToFirst()) {
                throw new IllegalArgumentException("Missing details for " + uri);
            }
            final String mimeType = getCursorString(itemCursor, DocumentColumns.MIME_TYPE);
            final String displayName = getCursorString(itemCursor, DocumentColumns.DISPLAY_NAME);
            final int flags = getCursorInt(itemCursor, DocumentColumns.FLAGS)
                    & DocumentsContract.FLAG_SUPPORTS_THUMBNAIL;

            return new Document(uri, mimeType, displayName, lastModified, flags);
        } finally {
            itemCursor.close();
        }
    }

    public static Document fromUri(ContentResolver resolver, Uri uri) {
        final Cursor cursor = resolver.query(uri, null, null, null, null);
        try {
            if (!cursor.moveToFirst()) {
                throw new IllegalArgumentException("Missing details for " + uri);
            }
            final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            final String displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            final long lastModified = getCursorLong(cursor, DocumentColumns.LAST_MODIFIED);
            final int flags = getCursorInt(cursor, DocumentColumns.FLAGS);

            return new Document(uri, mimeType, displayName, lastModified, flags);
        } finally {
            cursor.close();
        }
    }

    public static Document fromSearch(Uri relatedUri, String query) {
        final Uri uri = DocumentsContract.buildSearchUri(relatedUri, query);
        final String mimeType = DocumentsContract.MIME_TYPE_DIRECTORY;
        final String displayName = query;
        final long lastModified = System.currentTimeMillis();
        final int flags = 0;
        return new Document(uri, mimeType, displayName, lastModified, flags);
    }

    @Override
    public String toString() {
        return "Document{name=" + displayName + ", uri=" + uri + "}";
    }

    public boolean isCreateSupported() {
        return (flags & DocumentsContract.FLAG_SUPPORTS_CREATE) != 0;
    }

    public boolean isSearchSupported() {
        return (flags & DocumentsContract.FLAG_SUPPORTS_SEARCH) != 0;
    }

    public boolean isThumbnailSupported() {
        return (flags & DocumentsContract.FLAG_SUPPORTS_THUMBNAIL) != 0;
    }

    private static String getCursorString(Cursor cursor, String columnName) {
        return cursor.getString(cursor.getColumnIndexOrThrow(columnName));
    }

    private static long getCursorLong(Cursor cursor, String columnName) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(columnName));
    }

    private static int getCursorInt(Cursor cursor, String columnName) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(columnName));
    }

    public static class NameComparator implements Comparator<Document> {
        @Override
        public int compare(Document lhs, Document rhs) {
            final boolean leftDir = DocumentsContract.MIME_TYPE_DIRECTORY.equals(lhs.mimeType);
            final boolean rightDir = DocumentsContract.MIME_TYPE_DIRECTORY.equals(rhs.mimeType);

            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            } else {
                return lhs.displayName.compareToIgnoreCase(rhs.displayName);
            }
        }
    }

    public static class DateComparator implements Comparator<Document> {
        @Override
        public int compare(Document lhs, Document rhs) {
            return Long.compare(rhs.lastModified, lhs.lastModified);
        }
    }
}
