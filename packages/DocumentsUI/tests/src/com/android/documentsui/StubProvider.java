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
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private HashMap<String, StubDocument> mStorage = new HashMap<String, StubDocument>();
    private int mStorageUsedBytes;
    private Object mWriteLock = new Object();
    private String mAuthority;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        final File cacheDir = getContext().getCacheDir();
        removeRecursively(cacheDir);
        final StubDocument document = new StubDocument(cacheDir, Document.MIME_TYPE_DIR, null);
        mRootDocumentId = document.documentId;
        mStorage.put(mRootDocumentId, document);
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, MY_ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, "Foobar SD 4GB");
        row.add(Root.COLUMN_DOCUMENT_ID, mRootDocumentId);
        row.add(Root.COLUMN_AVAILABLE_BYTES, STORAGE_SIZE - mStorageUsedBytes);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final StubDocument file = mStorage.get(documentId);
        if (file == null) {
            throw new FileNotFoundException();
        }
        includeDocument(result, file);
        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocId, String docId) {
        final StubDocument parentDocument = mStorage.get(parentDocId);
        final StubDocument childDocument = mStorage.get(docId);
        return FileUtils.contains(parentDocument.file, childDocument.file);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        final StubDocument parentDocument = mStorage.get(parentDocumentId);
        if (parentDocument == null || !parentDocument.file.isDirectory()) {
            throw new FileNotFoundException();
        }
        final File file = new File(parentDocument.file, displayName);
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

        final StubDocument document = new StubDocument(file, mimeType, parentDocument);
        mStorage.put(document.documentId, document);
        notifyParentChanged(document.parentId);
        return document.documentId;
    }

    @Override
    public void deleteDocument(String documentId)
            throws FileNotFoundException {
        final StubDocument document = mStorage.get(documentId);
        final long fileSize = document.file.length();
        if (document == null || !document.file.delete())
            throw new FileNotFoundException();
        synchronized (mWriteLock) {
            mStorageUsedBytes -= fileSize;
        }
        notifyParentChanged(document.parentId);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final StubDocument parentDocument = mStorage.get(parentDocumentId);
        if (parentDocument == null || parentDocument.file.isFile()) {
            throw new FileNotFoundException();
        }
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        result.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(mAuthority, parentDocumentId));
        StubDocument document;
        for (File file : parentDocument.file.listFiles()) {
            document = mStorage.get(StubDocument.getDocumentIdForFile(file));
            if (document != null) {
                includeDocument(result, document);
            }
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
        final StubDocument document = mStorage.get(docId);
        if (document == null || !document.file.isFile())
            throw new FileNotFoundException();

        if ("r".equals(mode)) {
            return ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if ("w".equals(mode)) {
            return startWrite(document);
        }

        throw new FileNotFoundException();
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    private ParcelFileDescriptor startWrite(final StubDocument document)
            throws FileNotFoundException {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        }
        catch (IOException exception) {
            throw new FileNotFoundException();
        }
        final ParcelFileDescriptor readPipe = pipe[0];
        final ParcelFileDescriptor writePipe = pipe[1];

        new Thread() {
            @Override
            public void run() {
                try {
                    final FileInputStream inputStream = new FileInputStream(readPipe.getFileDescriptor());
                    final FileOutputStream outputStream = new FileOutputStream(document.file);
                    byte[] buffer = new byte[32 * 1024];
                    int bytesToRead;
                    int bytesRead = 0;
                    while (bytesRead != -1) {
                        synchronized (mWriteLock) {
                            bytesToRead = Math.min(STORAGE_SIZE - mStorageUsedBytes, buffer.length);
                            if (bytesToRead == 0) {
                                closePipeWithErrorSilently(readPipe, "Not enough space.");
                                break;
                            }
                            bytesRead = inputStream.read(buffer, 0, bytesToRead);
                            if (bytesRead == -1) {
                                break;
                            }
                            outputStream.write(buffer, 0, bytesRead);
                            mStorageUsedBytes += bytesRead;
                        }
                    }
                }
                catch (IOException e) {
                    closePipeWithErrorSilently(readPipe, e.getMessage());
                }
                finally {
                    closePipeSilently(readPipe);
                    notifyParentChanged(document.parentId);
                }
            }
        }.start();

        return writePipe;
    }

    private void closePipeWithErrorSilently(ParcelFileDescriptor pipe, String error) {
        try {
            pipe.closeWithError(error);
        }
        catch (IOException ignore) {
        }
    }

    private void closePipeSilently(ParcelFileDescriptor pipe) {
        try {
            pipe.close();
        }
        catch (IOException ignore) {
        }
    }

    private void notifyParentChanged(String parentId) {
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(mAuthority, parentId), null, false);
        // Notify also about possible change in remaining space on the root.
        getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(mAuthority), null, false);
    }

    private void includeDocument(MatrixCursor result, StubDocument document) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, document.documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, document.file.getName());
        row.add(Document.COLUMN_SIZE, document.file.length());
        row.add(Document.COLUMN_MIME_TYPE, document.mimeType);
        int flags = Document.FLAG_SUPPORTS_DELETE;
        // TODO: Add support for renaming.
        if (document.file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_LAST_MODIFIED, document.file.lastModified());
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

class StubDocument {
    public final File file;
    public final String mimeType;
    public final String documentId;
    public final String parentId;

    StubDocument(File file, String mimeType, StubDocument parent) {
        this.file = file;
        this.mimeType = mimeType;
        this.documentId = getDocumentIdForFile(file);
        this.parentId = parent != null ? parent.documentId : null;
    }

    public static String getDocumentIdForFile(File file) {
        return file.getAbsolutePath();
    }
}
