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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import com.android.documentsui.RootCursorWrapper;

import libcore.io.IoUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Comparator;

/**
 * Representation of a {@link Document}.
 */
public class DocumentInfo implements Durable {
    private static final int VERSION_INIT = 1;

    public Uri uri;
    public String mimeType;
    public String displayName;
    public long lastModified;
    public int flags;
    public String summary;
    public long size;
    public int icon;

    public DocumentInfo() {
        reset();
    }

    @Override
    public void reset() {
        uri = null;
        mimeType = null;
        displayName = null;
        lastModified = -1;
        flags = 0;
        summary = null;
        size = -1;
        icon = 0;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_INIT:
                final String rawUri = DurableUtils.readNullableString(in);
                uri = rawUri != null ? Uri.parse(rawUri) : null;
                mimeType = DurableUtils.readNullableString(in);
                displayName = DurableUtils.readNullableString(in);
                lastModified = in.readLong();
                flags = in.readInt();
                summary = DurableUtils.readNullableString(in);
                size = in.readLong();
                icon = in.readInt();
                break;
            default:
                throw new ProtocolException("Unknown version " + version);
        }
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_INIT);
        DurableUtils.writeNullableString(out, uri.toString());
        DurableUtils.writeNullableString(out, mimeType);
        DurableUtils.writeNullableString(out, displayName);
        out.writeLong(lastModified);
        out.writeInt(flags);
        DurableUtils.writeNullableString(out, summary);
        out.writeLong(size);
        out.writeInt(icon);
    }

    public static DocumentInfo fromDirectoryCursor(Cursor cursor) {
        final DocumentInfo doc = new DocumentInfo();
        final String authority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
        final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
        doc.uri = DocumentsContract.buildDocumentUri(authority, docId);
        doc.mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        doc.displayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
        doc.lastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
        doc.flags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        doc.summary = getCursorString(cursor, Document.COLUMN_SUMMARY);
        doc.size = getCursorLong(cursor, Document.COLUMN_SIZE);
        doc.icon = getCursorInt(cursor, Document.COLUMN_ICON);
        return doc;
    }

    public static DocumentInfo fromUri(ContentResolver resolver, Uri uri) throws FileNotFoundException {
        final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                uri.getAuthority());
        Cursor cursor = null;
        try {
            cursor = client.query(uri, null, null, null, null);
            if (!cursor.moveToFirst()) {
                throw new FileNotFoundException("Missing details for " + uri);
            }
            final DocumentInfo doc = new DocumentInfo();
            doc.uri = uri;
            doc.mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            doc.displayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
            doc.lastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
            doc.flags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            doc.summary = getCursorString(cursor, Document.COLUMN_SUMMARY);
            doc.size = getCursorLong(cursor, Document.COLUMN_SIZE);
            doc.icon = getCursorInt(cursor, Document.COLUMN_ICON);
            return doc;
        } catch (Throwable t) {
            throw asFileNotFoundException(t);
        } finally {
            IoUtils.closeQuietly(cursor);
            ContentProviderClient.closeQuietly(client);
        }
    }

    @Override
    public String toString() {
        return "Document{name=" + displayName + ", uri=" + uri + "}";
    }

    public boolean isCreateSupported() {
        return (flags & Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
    }

    public boolean isSearchSupported() {
        return (flags & Document.FLAG_DIR_SUPPORTS_SEARCH) != 0;
    }

    public boolean isThumbnailSupported() {
        return (flags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
    }

    public boolean isDirectory() {
        return Document.MIME_TYPE_DIR.equals(mimeType);
    }

    public boolean isGridPreferred() {
        return (flags & Document.FLAG_DIR_PREFERS_GRID) != 0;
    }

    public boolean isDeleteSupported() {
        return (flags & Document.FLAG_SUPPORTS_DELETE) != 0;
    }

    public Drawable loadIcon(Context context) {
        return loadIcon(context, uri.getAuthority(), icon);
    }

    public static Drawable loadIcon(Context context, String authority, int icon) {
        if (icon != 0) {
            if (authority != null) {
                final PackageManager pm = context.getPackageManager();
                final ProviderInfo info = pm.resolveContentProvider(authority, 0);
                if (info != null) {
                    return pm.getDrawable(info.packageName, icon, info.applicationInfo);
                }
            } else {
                return context.getResources().getDrawable(icon);
            }
        }
        return null;
    }

    public static String getCursorString(Cursor cursor, String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getString(index) : null;
    }

    /**
     * Missing or null values are returned as -1.
     */
    public static long getCursorLong(Cursor cursor, String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        if (index == -1) return -1;
        final String value = cursor.getString(index);
        if (value == null) return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static int getCursorInt(Cursor cursor, String columnName) {
        final int index = cursor.getColumnIndex(columnName);
        return (index != -1) ? cursor.getInt(index) : 0;
    }

    @Deprecated
    public static class DisplayNameComparator implements Comparator<DocumentInfo> {
        @Override
        public int compare(DocumentInfo lhs, DocumentInfo rhs) {
            final boolean leftDir = lhs.isDirectory();
            final boolean rightDir = rhs.isDirectory();

            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            } else {
                return compareToIgnoreCaseNullable(lhs.displayName, rhs.displayName);
            }
        }
    }

    @Deprecated
    public static class LastModifiedComparator implements Comparator<DocumentInfo> {
        @Override
        public int compare(DocumentInfo lhs, DocumentInfo rhs) {
            return Long.compare(rhs.lastModified, lhs.lastModified);
        }
    }

    @Deprecated
    public static class SizeComparator implements Comparator<DocumentInfo> {
        @Override
        public int compare(DocumentInfo lhs, DocumentInfo rhs) {
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

    public static int compareToIgnoreCaseNullable(String lhs, String rhs) {
        if (lhs == null) return -1;
        if (rhs == null) return 1;
        return lhs.compareToIgnoreCase(rhs);
    }
}
