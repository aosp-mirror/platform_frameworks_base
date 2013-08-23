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
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.provider.DocumentsContract.Documents;
import android.provider.DocumentsContract.RootColumns;
import android.provider.DocumentsContract.Roots;
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

    private static final String AUTHORITY = "com.android.externalstorage.documents";

    // TODO: support multiple storage devices

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

    private HashMap<String, Root> mRoots = Maps.newHashMap();

    private static class Root {
        public int rootType;
        public String name;
        public int icon = 0;
        public String title = null;
        public String summary = null;
        public File path;
    }

    private static final String[] ALL_ROOTS_COLUMNS = new String[] {
            RootColumns.ROOT_ID, RootColumns.ROOT_TYPE, RootColumns.ICON, RootColumns.TITLE,
            RootColumns.SUMMARY, RootColumns.AVAILABLE_BYTES
    };

    private static final String[] ALL_DOCUMENTS_COLUMNS = new String[] {
            DocumentColumns.DOC_ID, DocumentColumns.DISPLAY_NAME, DocumentColumns.SIZE,
            DocumentColumns.MIME_TYPE, DocumentColumns.LAST_MODIFIED, DocumentColumns.FLAGS
    };

    @Override
    public boolean onCreate() {
        mRoots.clear();

        final Root root = new Root();
        root.rootType = Roots.ROOT_TYPE_DEVICE_ADVANCED;
        root.name = "primary";
        root.title = getContext().getString(R.string.root_internal_storage);
        root.path = Environment.getExternalStorageDirectory();
        mRoots.put(root.name, root);

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        switch (sMatcher.match(uri)) {
            case URI_ROOTS: {
                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                for (Root root : mRoots.values()) {
                    includeRoot(result, root);
                }
                return result;
            }
            case URI_ROOTS_ID: {
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));

                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_ROOTS_COLUMNS);
                includeRoot(result, root);
                return result;
            }
            case URI_DOCS_ID: {
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);
                final File file = docIdToFile(root, docId);
                includeFile(result, root, file);
                return result;
            }
            case URI_DOCS_ID_CONTENTS: {
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);
                final File parent = docIdToFile(root, docId);

                for (File file : parent.listFiles()) {
                    includeFile(result, root, file);
                }

                return result;
            }
            case URI_DOCS_ID_SEARCH: {
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);
                final String query = DocumentsContract.getSearchQuery(uri).toLowerCase();

                final MatrixCursor result = new MatrixCursor(
                        projection != null ? projection : ALL_DOCUMENTS_COLUMNS);
                final File parent = docIdToFile(root, docId);

                final LinkedList<File> pending = new LinkedList<File>();
                pending.add(parent);
                while (!pending.isEmpty() && result.getCount() < 20) {
                    final File file = pending.removeFirst();
                    if (file.isDirectory()) {
                        for (File child : file.listFiles()) {
                            pending.add(child);
                        }
                    } else {
                        if (file.getName().toLowerCase().contains(query)) {
                            includeFile(result, root, file);
                        }
                    }
                }

                return result;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private String fileToDocId(Root root, File file) {
        String rootPath = root.path.getAbsolutePath();
        final String path = file.getAbsolutePath();
        if (path.equals(rootPath)) {
            return Documents.DOC_ID_ROOT;
        }

        if (!rootPath.endsWith("/")) {
            rootPath += "/";
        }
        if (!path.startsWith(rootPath)) {
            throw new IllegalArgumentException("File " + path + " outside root " + root.path);
        } else {
            return path.substring(rootPath.length());
        }
    }

    private File docIdToFile(Root root, String docId) {
        if (Documents.DOC_ID_ROOT.equals(docId)) {
            return root.path;
        } else {
            return new File(root.path, docId);
        }
    }

    private void includeRoot(MatrixCursor result, Root root) {
        final RowBuilder row = result.newRow();
        row.offer(RootColumns.ROOT_ID, root.name);
        row.offer(RootColumns.ROOT_TYPE, root.rootType);
        row.offer(RootColumns.ICON, root.icon);
        row.offer(RootColumns.TITLE, root.title);
        row.offer(RootColumns.SUMMARY, root.summary);
        row.offer(RootColumns.AVAILABLE_BYTES, root.path.getFreeSpace());
    }

    private void includeFile(MatrixCursor result, Root root, File file) {
        int flags = 0;

        if (file.isDirectory()) {
            flags |= Documents.FLAG_SUPPORTS_SEARCH;
        }
        if (file.isDirectory() && file.canWrite()) {
            flags |= Documents.FLAG_SUPPORTS_CREATE;
        }
        if (file.canWrite()) {
            flags |= Documents.FLAG_SUPPORTS_WRITE;
            flags |= Documents.FLAG_SUPPORTS_RENAME;
            flags |= Documents.FLAG_SUPPORTS_DELETE;
        }

        final String mimeType = getTypeForFile(file);
        if (mimeType.startsWith("image/")) {
            flags |= Documents.FLAG_SUPPORTS_THUMBNAIL;
        }

        final String docId = fileToDocId(root, file);
        final String displayName;
        if (Documents.DOC_ID_ROOT.equals(docId)) {
            displayName = root.title;
        } else {
            displayName = file.getName();
        }

        final RowBuilder row = result.newRow();
        row.offer(DocumentColumns.DOC_ID, docId);
        row.offer(DocumentColumns.DISPLAY_NAME, displayName);
        row.offer(DocumentColumns.SIZE, file.length());
        row.offer(DocumentColumns.MIME_TYPE, mimeType);
        row.offer(DocumentColumns.LAST_MODIFIED, file.lastModified());
        row.offer(DocumentColumns.FLAGS, flags);
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
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);
                return getTypeForFile(docIdToFile(root, docId));
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Documents.MIME_TYPE_DIR;
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
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final File file = docIdToFile(root, docId);
                return ParcelFileDescriptor.open(file, ContentResolver.modeToMode(uri, mode));
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        if (opts == null || !opts.containsKey(DocumentsContract.EXTRA_THUMBNAIL_SIZE)) {
            return super.openTypedAssetFile(uri, mimeTypeFilter, opts);
        }

        switch (sMatcher.match(uri)) {
            case URI_DOCS_ID: {
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final File file = docIdToFile(root, docId);
                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        file, ParcelFileDescriptor.MODE_READ_ONLY);

                try {
                    final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                    final long[] thumb = exif.getThumbnailRange();
                    if (thumb != null) {
                        return new AssetFileDescriptor(pfd, thumb[0], thumb[1]);
                    }
                } catch (IOException e) {
                }

                return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
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
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final File parent = docIdToFile(root, docId);

                final String mimeType = values.getAsString(DocumentColumns.MIME_TYPE);
                final String name = validateDisplayName(
                        values.getAsString(DocumentColumns.DISPLAY_NAME), mimeType);

                final File file = new File(parent, name);
                if (Documents.MIME_TYPE_DIR.equals(mimeType)) {
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

                final String newDocId = fileToDocId(root, file);
                return DocumentsContract.buildDocumentUri(AUTHORITY, root.name, newDocId);
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
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final File file = docIdToFile(root, docId);
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
                final Root root = mRoots.get(DocumentsContract.getRootId(uri));
                final String docId = DocumentsContract.getDocId(uri);

                final File file = docIdToFile(root, docId);
                return file.delete() ? 1 : 0;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported Uri " + uri);
            }
        }
    }

    private String validateDisplayName(String displayName, String mimeType) {
        if (Documents.MIME_TYPE_DIR.equals(mimeType)) {
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
