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

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StubProvider extends DocumentsProvider {

    public static final String DEFAULT_AUTHORITY = "com.android.documentsui.stubprovider";
    public static final String ROOT_0_ID = "TEST_ROOT_0";
    public static final String ROOT_1_ID = "TEST_ROOT_1";

    private static final String TAG = "StubProvider";
    private static final String EXTRA_SIZE = "com.android.documentsui.stubprovider.SIZE";
    private static final String EXTRA_ROOT = "com.android.documentsui.stubprovider.ROOT";
    private static final String STORAGE_SIZE_KEY = "documentsui.stubprovider.size";
    private static int DEFAULT_ROOT_SIZE = 1024 * 1024 * 100;  // 100 MB.

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private final Map<String, StubDocument> mStorage = new HashMap<>();
    private final Map<String, RootInfo> mRoots = new HashMap<>();
    private final Object mWriteLock = new Object();

    private String mAuthority = DEFAULT_AUTHORITY;
    private SharedPreferences mPrefs;
    private String mSimulateReadErrors;

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
        Log.d(TAG, "Resetting storage.");
        removeChildrenRecursively(getContext().getCacheDir());
        mStorage.clear();

        mPrefs = getContext().getSharedPreferences(
                "com.android.documentsui.stubprovider.preferences", Context.MODE_PRIVATE);
        Collection<String> rootIds = mPrefs.getStringSet("roots", null);
        if (rootIds == null) {
            rootIds = Arrays.asList(new String[] { ROOT_0_ID, ROOT_1_ID });
        }

        mRoots.clear();
        for (String rootId : rootIds) {
            // Make a subdir in the cache dir for each root.
            final File file = new File(getContext().getCacheDir(), rootId);
            if (file.mkdir()) {
                Log.i(TAG, "Created new root directory @ " + file.getPath());
            }
            final RootInfo rootInfo = new RootInfo(file, getSize(rootId));
            mStorage.put(rootInfo.document.documentId, rootInfo.document);
            mRoots.put(rootId, rootInfo);
        }
    }

    /**
     * @return Storage size, in bytes.
     */
    private long getSize(String rootId) {
        final String key = STORAGE_SIZE_KEY + "." + rootId;
        return mPrefs.getLong(key, DEFAULT_ROOT_SIZE);
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
            row.add(Root.COLUMN_DOCUMENT_ID, info.document.documentId);
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
    public String createDocument(String parentId, String mimeType, String displayName)
            throws FileNotFoundException {

        final StubDocument parent = mStorage.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Can't create file " + displayName + " in null parent.");
        }
        if (!parent.file.isDirectory()) {
            throw new IllegalArgumentException(
                    "Can't create file " + displayName + " inside non-directory parent "
                    + parent.file.getName());
        }

        final File file = new File(parent.file, displayName);
        if (file.exists()) {
            throw new FileNotFoundException(
                    "Duplicate file names not supported for " + file);
        }

        if (mimeType.equals(Document.MIME_TYPE_DIR)) {
            if (!file.mkdirs()) {
                throw new FileNotFoundException(
                        "Failed to create directory(s): " + file);
            }
            Log.i(TAG, "Created new directory: " + file);
        } else {
            boolean created = false;
            try {
                created = file.createNewFile();
            } catch (IOException e) {
                // We'll throw an FNF exception later :)
                Log.e(TAG, "createNewFile operation failed for file: " + file, e);
            }
            if (!created) {
                throw new FileNotFoundException(
                        "createNewFile operation failed for: " + file);
            }
            Log.i(TAG, "Created new file: " + file);
        }

        final StubDocument document = StubDocument.createRegularDocument(file, mimeType, parent);
        mStorage.put(document.documentId, document);
        Log.d(TAG, "Created document " + document.documentId);
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
            mStorage.remove(documentId);
        }
        Log.d(TAG, "Document deleted: " + documentId);
        notifyParentChanged(document.parentId);
        getContext().getContentResolver().notifyChange(
                DocumentsContract.buildDocumentUri(mAuthority, document.documentId),
                null, false);
    }

    @Override
    public Cursor queryChildDocumentsForManage(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        return queryChildDocuments(parentDocumentId, projection, sortOrder);
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
        if (document == null || !document.file.isFile()) {
            throw new FileNotFoundException();
        }
        if ((document.flags & Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
            throw new IllegalStateException("Tried to open a virtual file.");
        }

        if ("r".equals(mode)) {
            final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(document.file,
                        ParcelFileDescriptor.MODE_READ_ONLY);
            if (docId.equals(mSimulateReadErrors)) {
                return new ParcelFileDescriptor(pfd) {
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
    public void simulateReadErrorsForFile(Uri uri) {
        mSimulateReadErrors = DocumentsContract.getDocumentId(uri);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    @Override
    public AssetFileDescriptor openTypedDocument(
            String documentId, String mimeTypeFilter, Bundle opts, CancellationSignal signal)
            throws FileNotFoundException {
        final StubDocument document = mStorage.get(documentId);
        if (document == null || !document.file.isFile()) {
            throw new FileNotFoundException();
        }
        if ((document.flags & Document.FLAG_SUPPORTS_TYPED_DOCUMENT) == 0) {
            throw new IllegalStateException("Tried to open a non-typed document as typed.");
        }
        for (final String mimeType : document.streamTypes) {
            // Strict compare won't accept wildcards, but that's OK for tests, as DocumentsUI
            // doesn't use them for openTypedDocument.
            if (mimeType.equals(mimeTypeFilter)) {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                            document.file, ParcelFileDescriptor.MODE_READ_ONLY);
                if (!documentId.equals(mSimulateReadErrors)) {
                    pfd = new ParcelFileDescriptor(pfd) {
                        @Override
                        public void checkError() throws IOException {
                            throw new IOException("Test error");
                        }
                    };
                }
                return new AssetFileDescriptor(pfd, 0, document.file.length());
            }
        }
        throw new IllegalArgumentException("Invalid MIME type filter for openTypedDocument().");
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
                    Log.d(TAG, "Opening write stream on file " + document.documentId);
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
                    Log.d(TAG, "Closing write stream on file " + document.documentId);
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
        String rootName = extras.getString(EXTRA_ROOT, ROOT_0_ID);
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
        row.add(Document.COLUMN_FLAGS, document.flags);
        row.add(Document.COLUMN_LAST_MODIFIED, document.file.lastModified());
    }

    private void removeChildrenRecursively(File file) {
        for (File childFile : file.listFiles()) {
            if (childFile.isDirectory()) {
                removeChildrenRecursively(childFile);
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
        Log.d(TAG, "Creating test file " + rootId + ":" + path);
        StubDocument root = mRoots.get(rootId).document;
        if (root == null) {
            throw new FileNotFoundException("No roots with the ID " + rootId + " were found");
        }
        File file = new File(root.file, path.substring(1));
        StubDocument parent = mStorage.get(getDocumentIdForFile(file.getParentFile()));
        if (parent == null) {
            parent = mStorage.get(createFile(rootId, file.getParentFile().getPath(),
                    DocumentsContract.Document.MIME_TYPE_DIR, null));
            Log.d(TAG, "Created parent " + parent.documentId);
        } else {
            Log.d(TAG, "Found parent " + parent.documentId);
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
        final StubDocument document = StubDocument.createRegularDocument(file, mimeType, parent);
        mStorage.put(document.documentId, document);
        return DocumentsContract.buildDocumentUri(mAuthority,  document.documentId);
    }

    @VisibleForTesting
    public File getFile(String rootId, String path) throws FileNotFoundException {
        StubDocument root = mRoots.get(rootId).document;
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

    final static class RootInfo {
        public final String name;
        public final StubDocument document;
        public long capacity;
        public long size;

        RootInfo(File file, long capacity) {
            this.name = file.getName();
            this.capacity = 1024 * 1024;
            this.document = StubDocument.createRootDocument(file, this);
            this.capacity = capacity;
            this.size = 0;
        }

        public long getRemainingCapacity() {
            return capacity - size;
        }
    }

    final static class StubDocument {
        public final File file;
        public final String documentId;
        public final String mimeType;
        public final List<String> streamTypes;
        public final int flags;
        public final String parentId;
        public final RootInfo rootInfo;

        private StubDocument(
                 File file, String mimeType, List<String> streamTypes, int flags,
                 StubDocument parent) {
            this.file = file;
            this.documentId = getDocumentIdForFile(file);
            this.mimeType = mimeType;
            this.streamTypes = streamTypes;
            this.flags = flags;
            this.parentId = parent.documentId;
            this.rootInfo = parent.rootInfo;
        }

        private StubDocument(File file, RootInfo rootInfo) {
            this.file = file;
            this.documentId = getDocumentIdForFile(file);
            this.mimeType = Document.MIME_TYPE_DIR;
            this.streamTypes = new ArrayList<String>();
            this.flags = Document.FLAG_DIR_SUPPORTS_CREATE;
            this.parentId = null;
            this.rootInfo = rootInfo;
        }

        public static StubDocument createRootDocument(File file, RootInfo rootInfo) {
            return new StubDocument(file, rootInfo);
        }

        public static StubDocument createRegularDocument(
                File file, String mimeType, StubDocument parent) {
            int flags = Document.FLAG_SUPPORTS_DELETE;
            if (file.isDirectory()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            } else {
                flags |= Document.FLAG_SUPPORTS_WRITE;
            }
            return new StubDocument(file, mimeType, new ArrayList<String>(), flags, parent);
        }

        public static StubDocument createVirtualDocument(
                File file, String mimeType, List<String> streamTypes, StubDocument parent) {
            int flags = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE
                    | Document.FLAG_VIRTUAL_DOCUMENT;
            if (streamTypes.size() > 0) {
                flags |= Document.FLAG_SUPPORTS_TYPED_DOCUMENT;
            }
            return new StubDocument(file, mimeType, streamTypes, flags, parent);
        }

        @Override
        public String toString() {
            return "StubDocument{"
                    + "path:" + file.getPath()
                    + ", documentId:" + documentId
                    + ", mimeType:" + mimeType
                    + ", streamTypes:" + streamTypes.toString()
                    + ", flags:" + flags
                    + ", parentId:" + parentId
                    + ", rootInfo:" + rootInfo
                    + "}";
        }
    }

    private static String getDocumentIdForFile(File file) {
        return file.getAbsolutePath();
    }
}
