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

package com.android.documentsui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class StubProvider extends DocumentsProvider {
    private static int STORAGE_SIZE = 1024 * 1024;  // 1 MB.
    private static final String TAG = "StubProvider";
    private static final String MY_ROOT_ID = "myRoot";
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private String mRootDocumentId;
    private HashMap<String, File> mStorage = new HashMap<String, File>();
    private int mStorageUsedBytes;

    @Override
    public boolean onCreate() {
        final File cacheDir = getContext().getCacheDir();
        removeRecursively(cacheDir);
        mRootDocumentId = getDocumentIdForFile(cacheDir);
        mStorage.put(mRootDocumentId, cacheDir);
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, MY_ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        row.add(Root.COLUMN_TITLE, "Foobar SD 4GB");
        row.add(Root.COLUMN_DOCUMENT_ID, mRootDocumentId);
        row.add(Root.COLUMN_AVAILABLE_BYTES, STORAGE_SIZE - mStorageUsedBytes);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File file = mStorage.get(documentId);
        if (file == null) {
            throw new FileNotFoundException();
        }
        includeFile(result, file);
        return result;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        final File parentFile = mStorage.get(parentDocumentId);
        if (parentFile == null || !parentFile.isDirectory()) {
            throw new FileNotFoundException();
        }
        final File file = new File(parentFile, displayName);
        if (mimeType.equals(Document.MIME_TYPE_DIR)) {
            if (!file.mkdirs()) {
                throw new FileNotFoundException();
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    throw new FileNotFoundException();
                }
            }
            catch (IOException e) {
                throw new FileNotFoundException();
            }
        }

        final String documentId = getDocumentIdForFile(file);
        mStorage.put(documentId, file);
        return documentId;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parentFile = mStorage.get(parentDocumentId);
        if (parentFile == null || parentFile.isFile()) {
            throw new FileNotFoundException();
        }
        for (File file : parentFile.listFiles()) {
            includeFile(result, file);
        }
        return result;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = mStorage.get(docId);
        if (file == null || !file.isFile())
            throw new FileNotFoundException();
        // TODO: Simulate running out of storage.
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    private void includeFile(MatrixCursor result, File file) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, getDocumentIdForFile(file));
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_SIZE, file.length());
        // TODO: Provide real mime type for files.
        row.add(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        int flags = 0;
        // TODO: Add support for renaming and deleting.
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private String getDocumentIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private void removeRecursively(File file) {
        for (File childFile : file.listFiles()) {
            if (childFile.isDirectory()) {
                removeRecursively(childFile);
            }
            childFile.delete();
        }
    }
}
