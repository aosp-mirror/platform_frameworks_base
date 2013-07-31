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
import android.provider.DocumentsContract.RootColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.google.android.collect.Maps;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;

public class ExternalStorageProvider extends ContentProvider {
    private static final String TAG = "ExternalStorage";

    private static final String AUTHORITY = "com.android.externalstorage";

    // TODO: support searching
    // TODO: support multiple storage devices

    private static final UriMatcher sMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URI_ROOTS = 1;
    private static final int URI_DOCS_ID = 2;
    private static final int URI_DOCS_ID_CONTENTS = 3;
    private static final int URI_DOCS_ID_SEARCH = 4;

    private HashMap<String, Root> mRoots = Maps.newHashMap();

    private static class Root {
        public int rootType;
        public String name;
        public int icon = 0;
        public String title = null;
        public String summary = null;
        public File path;
    }

    static {
        sMatcher.addURI(AUTHORITY, "roots", URI_ROOTS);
        sMatcher.addURI(AUTHORITY, "docs/*", URI_DOCS_ID);
        sMatcher.addURI(AUTHORITY, "docs/*/contents", URI_DOCS_ID_CONTENTS);
        sMatcher.addURI(AUTHORITY, "docs/*/search", URI_DOCS_ID_SEARCH);
    }

    @Override
    public boolean onCreate() {
        mRoots.clear();

        final Root root = new Root();
        root.rootType = DocumentsContract.ROOT_TYPE_DEVICE_ADVANCED;
        root.name = "internal";
        root.title = getContext().getString(R.string.root_internal_storage);
        root.path = Environment.getExternalStorageDirectory();
        mRoots.put(root.name, root);

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final int match = sMatcher.match(uri);
        if (match == URI_ROOTS) {
            // TODO: support custom projections
            projection = new String[] {
                    RootColumns.ROOT_TYPE, RootColumns.GUID, RootColumns.ICON, RootColumns.TITLE,
                    RootColumns.SUMMARY, RootColumns.AVAILABLE_BYTES };

            final MatrixCursor cursor = new MatrixCursor(projection);
            for (Root root : mRoots.values()) {
                final String guid = fileToGuid(root.path);
                cursor.addRow(new Object[] {
                        root.rootType, guid, root.icon, root.title, root.summary,
                        root.path.getFreeSpace() });
            }
            return cursor;
        }

        // TODO: support custom projections
        projection = new String[] {
                BaseColumns._ID,
                DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE, DocumentColumns.GUID,
                DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS };

        final MatrixCursor cursor = new MatrixCursor(projection);
        switch (match) {
            case URI_DOCS_ID: {
                final String guid = uri.getPathSegments().get(1);
                includeFile(cursor, guid);
                break;
            }
            case URI_DOCS_ID_CONTENTS: {
                final String guid = uri.getPathSegments().get(1);
                final File parent = guidToFile(guid);
                for (File file : parent.listFiles()) {
                    includeFile(cursor, fileToGuid(file));
                }
                break;
            }
            case URI_DOCS_ID_SEARCH: {
                final String guid = uri.getPathSegments().get(1);
                final File parent = guidToFile(guid);
                final String query = uri.getQueryParameter(DocumentsContract.PARAM_QUERY).toLowerCase();

                final LinkedList<File> pending = new LinkedList<File>();
                pending.add(parent);
                while (!pending.isEmpty() && cursor.getCount() < 20) {
                    final File file = pending.removeFirst();
                    if (file.isDirectory()) {
                        for (File child : file.listFiles()) {
                            pending.add(child);
                        }
                    } else {
                        if (file.getName().toLowerCase().contains(query)) {
                            includeFile(cursor, fileToGuid(file));
                        }
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

    private String fileToGuid(File file) {
        final String path = file.getAbsolutePath();
        for (Root root : mRoots.values()) {
            final String rootPath = root.path.getAbsolutePath();
            if (path.startsWith(rootPath)) {
                return root.name + ':' + Uri.encode(path.substring(rootPath.length()));
            }
        }

        throw new IllegalArgumentException("Failed to find root for " + file);
    }

    private File guidToFile(String guid) {
        final int split = guid.indexOf(':');
        final String name = guid.substring(0, split);
        final Root root = mRoots.get(name);
        if (root != null) {
            final String path = Uri.decode(guid.substring(split + 1));
            return new File(root.path, path);
        }

        throw new IllegalArgumentException("Failed to find root for " + guid);
    }

    private void includeFile(MatrixCursor cursor, String guid) {
        int flags = 0;

        final File file = guidToFile(guid);
        if (file.isDirectory()) {
            flags |= DocumentsContract.FLAG_SUPPORTS_SEARCH;
        }
        if (file.isDirectory() && file.canWrite()) {
            flags |= DocumentsContract.FLAG_SUPPORTS_CREATE;
        }
        if (file.canWrite()) {
            flags |= DocumentsContract.FLAG_SUPPORTS_RENAME;
            flags |= DocumentsContract.FLAG_SUPPORTS_DELETE;
        }

        final String mimeType = getTypeForFile(file);
        if (mimeType.startsWith("image/")) {
            flags |= DocumentsContract.FLAG_SUPPORTS_THUMBNAIL;
        }

        final long id = guid.hashCode();
        cursor.addRow(new Object[] {
                id, file.getName(), file.length(), guid, mimeType, file.lastModified(), flags });
    }

    @Override
    public String getType(Uri uri) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String guid = uri.getPathSegments().get(1);
                return getTypeForFile(guidToFile(guid));
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return DocumentsContract.MIME_TYPE_DIRECTORY;
        } else {
            return getTypeForName(file.getName());
        }
    }

    private String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
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
                final String guid = uri.getPathSegments().get(1);
                final File file = guidToFile(guid);

                // TODO: offer as thumbnail
                return ParcelFileDescriptor.open(file, ContentResolver.modeToMode(uri, mode));
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String guid = uri.getPathSegments().get(1);
                final File parent = guidToFile(guid);

                final String mimeType = values.getAsString(DocumentColumns.MIME_TYPE);
                final String name = validateDisplayName(
                        values.getAsString(DocumentColumns.DISPLAY_NAME), mimeType);

                final File file = new File(parent, name);
                if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
                    if (!file.mkdir()) {
                        return null;
                    }

                } else {
                    try {
                        if (!file.createNewFile()) {
                            return null;
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to create file", e);
                        return null;
                    }
                }

                return DocumentsContract.buildDocumentUri(AUTHORITY, fileToGuid(file));
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String guid = uri.getPathSegments().get(1);
                final File file = guidToFile(guid);
                final File newFile = new File(
                        file.getParentFile(), values.getAsString(DocumentColumns.DISPLAY_NAME));
                return file.renameTo(newFile) ? 1 : 0;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final String guid = uri.getPathSegments().get(1);
                final File file = guidToFile(guid);
                return file.delete() ? 1 : 0;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private String validateDisplayName(String displayName, String mimeType) {
        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
            return displayName;
        } else {
            // Try appending meaningful extension if needed
            if (!mimeType.equals(getTypeForName(displayName))) {
                final String extension = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mimeType);
                if (extension != null) {
                    displayName += "." + extension;
                }
            }

            return displayName;
        }
    }
}
