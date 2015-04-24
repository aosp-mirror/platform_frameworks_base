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
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.google.android.collect.Maps;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class StubProvider extends DocumentsProvider {
    private static final String EXTRA_SIZE = "com.android.documentsui.stubprovider.SIZE";
    private static final String EXTRA_ROOT = "com.android.documentsui.stubprovider.ROOT";
    private static final String STORAGE_SIZE_KEY = "documentsui.stubprovider.size";
    private static int DEFAULT_SIZE = 1024 * 1024; // 1 MB.
    private static final String TAG = "StubProvider";
    private static final String MY_ROOT_ID = "sd0";
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private HashMap<String, StubDocument> mStorage = new HashMap<String, StubDocument>();
    private Object mWriteLock = new Object();
    private String mAuthority;
    private SharedPreferences mPrefs;
    private Map<String, RootInfo> mRoots;
    private boolean mSimulateReadErrors;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        clearCacheAndBuildRoots();
        return true;
    }

    @VisibleForTesting
    public void clearCacheAndBuildRoots() {
        final File cacheDir = getContext().getCacheDir();
        removeRecursively(cacheDir);
        mStorage.clear();

        mPrefs = getContext().getSharedPreferences(
                "com.android.documentsui.stubprovider.preferences", Context.MODE_PRIVATE);
        Collection<String> rootIds = mPrefs.getStringSet("roots", null);
        if (rootIds == null) {
            rootIds = Arrays.asList(new String[] {
                    "sd0", "sd1"
            });
        }
        // Create new roots.
        mRoots = Maps.newHashMap();
        for (String rootId : rootIds) {
            final RootInfo rootInfo = new RootInfo(rootId, getSize(rootId));
            mRoots.put(rootId, rootInfo);
        }
    }

    /**
     * @return Storage size, in bytes.
     */
    private long getSize(String rootId) {
        final String key = STORAGE_SIZE_KEY + "." + rootId;
        return mPrefs.getLong(key, DEFAULT_SIZE);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection
                : DEFAULT_ROOT_PROJECTION);
        for (Map.Entry<String, RootInfo> entry : mRoots.entrySet()) {
            final String id = entry.getKey();
            final RootInfo info = entry.getValue();
            final RowBuilder row = result.newRow();
            row.add(Root.COLUMN_ROOT_ID, id);
            row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
            row.add(Root.COLUMN_TITLE, id);
            row.add(Root.COLUMN_DOCUMENT_ID, info.rootDocument.documentId);
            row.add(Root.COLUMN_AVAILABLE_BYTES, info.getRemainingCapacity());
        }
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection
                : DEFAULT_DOCUMENT_PROJECTION);
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
                    throw new IllegalStateException("The file " + file.getPath() + " already exists");
                }
            } catch (IOException e) {
                throw new FileNotFoundException();
            }
        }

        final StubDocument document = new StubDocument(file, mimeType, parentDocument);
        notifyParentChanged(document.parentId);
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildDocumentUri(mAuthority, document.documentId),
                null, false);

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
            document.rootInfo.size -= fileSize;
        }
        notifyParentChanged(document.parentId);
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildDocumentUri(mAuthority, document.documentId),
                null, false);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final StubDocument parentDocument = mStorage.get(parentDocumentId);
        if (parentDocument == null || parentDocument.file.isFile()) {
            throw new FileNotFoundException();
        }
        final MatrixCursor result = new MatrixCursor(projection != null ? projection
                : DEFAULT_DOCUMENT_PROJECTION);
        result.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(mAuthority, parentDocumentId));
        StubDocument document;
        for (File file : parentDocument.file.listFiles()) {
            document = mStorage.get(getDocumentIdForFile(file));
            if (document != null) {
                includeDocument(result, document);
            }
        }
        return result;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection
                : DEFAULT_DOCUMENT_PROJECTION);
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final StubDocument document = mStorage.get(docId);
        if (document == null || !document.file.isFile())
            throw new FileNotFoundException();

        if ("r".equals(mode)) {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(document.file,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            if (mSimulateReadErrors) {
                pfd = new ParcelFileDescriptor(pfd) {
                    @Override
                    public void checkError() throws IOException {
                        throw new IOException("Test error");
                    }
                };
            }
            return pfd;
        }
        if ("w".equals(mode)) {
            return startWrite(document);
        }

        throw new FileNotFoundException();
    }

    @VisibleForTesting
    public void simulateReadErrors(boolean b) {
        mSimulateReadErrors = b;
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
        } catch (IOException exception) {
            throw new FileNotFoundException();
        }
        final ParcelFileDescriptor readPipe = pipe[0];
        final ParcelFileDescriptor writePipe = pipe[1];

        new Thread() {
            @Override
            public void run() {
                InputStream inputStream = null;
                OutputStream outputStream = null;
                try {
                    inputStream = new ParcelFileDescriptor.AutoCloseInputStream(readPipe);
                    outputStream = new FileOutputStream(document.file);
                    byte[] buffer = new byte[32 * 1024];
                    int bytesToRead;
                    int bytesRead = 0;
                    while (bytesRead != -1) {
                        synchronized (mWriteLock) {
                            // This cast is safe because the max possible value is buffer.length.
                            bytesToRead = (int) Math.min(document.rootInfo.getRemainingCapacity(),
                                    buffer.length);
                            if (bytesToRead == 0) {
                                closePipeWithErrorSilently(readPipe, "Not enough space.");
                                break;
                            }
                            bytesRead = inputStream.read(buffer, 0, bytesToRead);
                            if (bytesRead == -1) {
                                break;
                            }
                            outputStream.write(buffer, 0, bytesRead);
                            document.rootInfo.size += bytesRead;
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error on close", e);
                    closePipeWithErrorSilently(readPipe, e.getMessage());
                } finally {
                    IoUtils.closeQuietly(inputStream);
                    IoUtils.closeQuietly(outputStream);
                    notifyParentChanged(document.parentId);
                    getContext().getContentResolver().notifyChange(
                            DocumentsContract.buildDocumentUri(mAuthority, document.documentId),
                            null, false);
                }
            }
        }.start();

        return writePipe;
    }

    private void closePipeWithErrorSilently(ParcelFileDescriptor pipe, String error) {
        try {
            pipe.closeWithError(error);
        } catch (IOException ignore) {
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "clear":
                clearCacheAndBuildRoots();
                return null;
            case "configure":
                configure(arg, extras);
                return null;
            default:
                return super.call(method, arg, extras);
        }
    }

    private void configure(String arg, Bundle extras) {
        Log.d(TAG, "Configure " + arg);
        String rootName = extras.getString(EXTRA_ROOT, MY_ROOT_ID);
        long rootSize = extras.getLong(EXTRA_SIZE, 1) * 1024 * 1024;
        setSize(rootName, rootSize);
    }

    private void notifyParentChanged(String parentId) {
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(mAuthority, parentId), null, false);
        // Notify also about possible change in remaining space on the root.
        getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(mAuthority),
                null, false);
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

    public void setSize(String rootId, long rootSize) {
        RootInfo root = mRoots.get(rootId);
        if (root != null) {
            final String key = STORAGE_SIZE_KEY + "." + rootId;
            Log.d(TAG, "Set size of " + key + " : " + rootSize);

            // Persist the size.
            SharedPreferences.Editor editor = mPrefs.edit();
            editor.putLong(key, rootSize);
            editor.apply();
            // Apply the size in the current instance of this provider.
            root.capacity = rootSize;
            getContext().getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri(mAuthority),
                    null, false);
        } else {
            Log.e(TAG, "Attempt to configure non-existent root: " + rootId);
        }
    }

    @VisibleForTesting
    public Uri createFile(String rootId, String path, String mimeType, byte[] content)
            throws FileNotFoundException, IOException {
        StubDocument root = mRoots.get(rootId).rootDocument;
        if (root == null) {
            throw new FileNotFoundException("No roots with the ID " + rootId + " were found");
        }
        File file = new File(root.file, path.substring(1));
        StubDocument parent = mStorage.get(getDocumentIdForFile(file.getParentFile()));
        if (parent == null) {
            parent = mStorage.get(createFile(rootId, file.getParentFile().getPath(),
                    DocumentsContract.Document.MIME_TYPE_DIR, null));
        }

        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!file.mkdirs()) {
                throw new FileNotFoundException("Couldn't create directory " + file.getPath());
            }
        } else {
            if (!file.createNewFile()) {
                throw new FileNotFoundException("Couldn't create file " + file.getPath());
            }
            // Add content to the file.
            FileOutputStream fout = new FileOutputStream(file);
            fout.write(content);
            fout.close();
        }
        final StubDocument document = new StubDocument(file, mimeType, parent);
        return DocumentsContract.buildDocumentUri(mAuthority,  document.documentId);
    }

    @VisibleForTesting
    public File getFile(String rootId, String path) throws FileNotFoundException {
        StubDocument root = mRoots.get(rootId).rootDocument;
        if (root == null) {
            throw new FileNotFoundException("No roots with the ID " + rootId + " were found");
        }
        // Convert the path string into a path that's relative to the root.
        File needle = new File(root.file, path.substring(1));

        StubDocument found = mStorage.get(getDocumentIdForFile(needle));
        if (found == null) {
            return null;
        }
        return found.file;
    }

    final class RootInfo {
        public final String name;
        public final StubDocument rootDocument;
        public long capacity;
        public long size;

        RootInfo(String name, long capacity) {
            this.name = name;
            this.capacity = 1024 * 1024;
            // Make a subdir in the cache dir for each root.
            File rootDir = new File(getContext().getCacheDir(), name);
            rootDir.mkdir();
            this.rootDocument = new StubDocument(rootDir, Document.MIME_TYPE_DIR, this);
            this.capacity = capacity;
            this.size = 0;
        }

        public long getRemainingCapacity() {
            return capacity - size;
        }
    }

    final class StubDocument {
        public final File file;
        public final String mimeType;
        public final String documentId;
        public final String parentId;
        public final RootInfo rootInfo;

        StubDocument(File file, String mimeType, StubDocument parent) {
            this.file = file;
            this.mimeType = mimeType;
            this.documentId = getDocumentIdForFile(file);
            this.parentId = parent.documentId;
            this.rootInfo = parent.rootInfo;
            mStorage.put(this.documentId, this);
        }

        StubDocument(File file, String mimeType, RootInfo rootInfo) {
            this.file = file;
            this.mimeType = mimeType;
            this.documentId = getDocumentIdForFile(file);
            this.parentId = null;
            this.rootInfo = rootInfo;
            mStorage.put(this.documentId, this);
        }
    }

    private static String getDocumentIdForFile(File file) {
        return file.getAbsolutePath();
    }
}
