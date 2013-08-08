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

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
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
    public final String summary;
    public final long size;

    private Document(Uri uri, String mimeType, String displayName, long lastModified, int flags,
            String summary, long size) {
        this.uri = uri;
        this.mimeType = mimeType;
        this.displayName = displayName;
        this.lastModified = lastModified;
        this.flags = flags;
        this.summary = summary;
        this.size = size;
    }

    public static Document fromRoot(ContentResolver resolver, Root root)
            throws FileNotFoundException {
        return fromUri(resolver, root.uri);
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
        final String summary = getCursorString(cursor, DocumentColumns.SUMMARY);
        final long size = getCursorLong(cursor, DocumentColumns.SIZE);

        return new Document(uri, mimeType, displayName, lastModified, flags, summary, size);
    }

    public static Document fromRecentOpenCursor(ContentResolver resolver, Cursor recentCursor)
            throws FileNotFoundException {
        final Uri uri = Uri.parse(getCursorString(recentCursor, RecentsProvider.COL_URI));
        final long lastModified = getCursorLong(recentCursor, RecentsProvider.COL_TIMESTAMP);

        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (!cursor.moveToFirst()) {
                throw new FileNotFoundException("Missing details for " + uri);
            }
            final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            final String displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            final int flags = getCursorInt(cursor, DocumentColumns.FLAGS)
                    & DocumentsContract.FLAG_SUPPORTS_THUMBNAIL;
            final String summary = getCursorString(cursor, DocumentColumns.SUMMARY);
            final long size = getCursorLong(cursor, DocumentColumns.SIZE);

            return new Document(uri, mimeType, displayName, lastModified, flags, summary, size);
        } catch (Throwable t) {
            throw asFileNotFoundException(t);
        } finally {
            IoUtils.closeQuietly(cursor);
        }
    }

    public static Document fromUri(ContentResolver resolver, Uri uri) throws FileNotFoundException {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (!cursor.moveToFirst()) {
                throw new FileNotFoundException("Missing details for " + uri);
            }
            final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            final String displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            final long lastModified = getCursorLong(cursor, DocumentColumns.LAST_MODIFIED);
            final int flags = getCursorInt(cursor, DocumentColumns.FLAGS);
            final String summary = getCursorString(cursor, DocumentColumns.SUMMARY);
            final long size = getCursorLong(cursor, DocumentColumns.SIZE);

            return new Document(uri, mimeType, displayName, lastModified, flags, summary, size);
        } catch (Throwable t) {
            throw asFileNotFoundException(t);
        } finally {
            IoUtils.closeQuietly(cursor);
        }
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

    public boolean isDirectory() {
        return DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType);
    }

    private static String getCursorString(Cursor cursor, String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getString(index) : null;
    }

    private static long getCursorLong(Cursor cursor, String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getLong(index) : 0;
    }

    private static int getCursorInt(Cursor cursor, String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getInt(index) : 0;
    }

    public static class NameComparator implements Comparator<Document> {
        @Override
        public int compare(Document lhs, Document rhs) {
            final boolean leftDir = lhs.isDirectory();
            final boolean rightDir = rhs.isDirectory();

            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            } else {
                return Root.compareToIgnoreCaseNullable(lhs.displayName, rhs.displayName);
            }
        }
    }

    public static class DateComparator implements Comparator<Document> {
        @Override
        public int compare(Document lhs, Document rhs) {
            return Long.compare(rhs.lastModified, lhs.lastModified);
        }
    }

    public static class SizeComparator implements Comparator<Document> {
        @Override
        public int compare(Document lhs, Document rhs) {
            return Long.compare(rhs.size, lhs.size);
        }
    }

    public static FileNotFoundException asFileNotFoundException(Throwable t)
            throws FileNotFoundException {
        if (t instanceof FileNotFoundException) {
            throw (FileNotFoundException) t;
        }
        final FileNotFoundException fnfe = new FileNotFoundException(t.getMessage());
        fnfe.initCause(t);
        throw fnfe;
    }
}
