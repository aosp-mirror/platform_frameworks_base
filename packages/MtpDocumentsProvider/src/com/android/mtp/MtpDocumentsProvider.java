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

package com.android.mtp;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * DocumentsProvider for MTP devices.
 */
public class MtpDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "com.android.mtp.documents";
    static final String TAG = "MtpDocumentsProvider";
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static MtpDocumentsProvider sSingleton;

    private MtpManager mMtpManager;
    private ContentResolver mResolver;
    private PipeManager mPipeManager;

    /**
     * Provides singleton instance to MtpDocumentsService.
     */
    static MtpDocumentsProvider getInstance() {
        return sSingleton;
    }

    @Override
    public boolean onCreate() {
        sSingleton = this;
        mMtpManager = new MtpManager(getContext());
        mResolver = getContext().getContentResolver();
        mPipeManager = new PipeManager();

        return true;
    }

    @VisibleForTesting
    void onCreateForTesting(MtpManager mtpManager, ContentResolver resolver) {
        this.mMtpManager = mtpManager;
        this.mResolver = resolver;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_ROOT_PROJECTION;
        }
        final MatrixCursor cursor = new MatrixCursor(projection);
        for (final int deviceId : mMtpManager.getOpenedDeviceIds()) {
            try {
                final MtpRoot[] roots = mMtpManager.getRoots(deviceId);
                // TODO: Add retry logic here.

                for (final MtpRoot root : roots) {
                    final Identifier rootIdentifier = new Identifier(deviceId, root.mStorageId);
                    final MatrixCursor.RowBuilder builder = cursor.newRow();
                    builder.add(Root.COLUMN_ROOT_ID, rootIdentifier.toRootId());
                    builder.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
                    builder.add(Root.COLUMN_TITLE, root.mDescription);
                    builder.add(
                            Root.COLUMN_DOCUMENT_ID,
                            rootIdentifier.toDocumentId());
                    builder.add(Root.COLUMN_AVAILABLE_BYTES , root.mFreeSpace);
                }
            } catch (IOException error) {
                Log.d(TAG, error.getMessage());
            }
        }
        cursor.setNotificationUri(
                mResolver, DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY));
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION;
        }
        final Identifier identifier = Identifier.createFromDocumentId(documentId);

        MtpDocument document = null;
        if (identifier.mObjectHandle != MtpDocument.DUMMY_HANDLE_FOR_ROOT) {
            try {
                document = mMtpManager.getDocument(identifier.mDeviceId, identifier.mObjectHandle);
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        } else {
            MtpRoot[] roots;
            try {
                roots = mMtpManager.getRoots(identifier.mDeviceId);
                if (roots != null) {
                    for (final MtpRoot root : roots) {
                        if (identifier.mStorageId == root.mStorageId) {
                            document = new MtpDocument(root);
                            break;
                        }
                    }
                }
                if (document == null) {
                    throw new FileNotFoundException();
                }
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }

        final MatrixCursor cursor = new MatrixCursor(projection);
        document.addToCursor(
                new Identifier(identifier.mDeviceId, identifier.mStorageId), cursor.newRow());
        return cursor;
    }

    // TODO: Support background loading for large number of files.
    @Override
    public Cursor queryChildDocuments(String parentDocumentId,
            String[] projection, String sortOrder) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION;
        }
        final Identifier parentIdentifier = Identifier.createFromDocumentId(parentDocumentId);
        int parentHandle = parentIdentifier.mObjectHandle;
        // Need to pass the special value MtpManager.OBJECT_HANDLE_ROOT_CHILDREN to
        // getObjectHandles if we would like to obtain children under the root.
        if (parentHandle == MtpDocument.DUMMY_HANDLE_FOR_ROOT) {
            parentHandle = MtpManager.OBJECT_HANDLE_ROOT_CHILDREN;
        }
        try {
            final MatrixCursor cursor = new MatrixCursor(projection);
            final Identifier rootIdentifier = new Identifier(
                    parentIdentifier.mDeviceId, parentIdentifier.mStorageId);
            final int[] objectHandles = mMtpManager.getObjectHandles(
                    parentIdentifier.mDeviceId, parentIdentifier.mStorageId, parentHandle);
            for (int i = 0; i < objectHandles.length; i++) {
                try {
                    final MtpDocument document = mMtpManager.getDocument(
                            parentIdentifier.mDeviceId,  objectHandles[i]);
                    document.addToCursor(rootIdentifier, cursor.newRow());
                } catch (IOException error) {
                    cursor.close();
                    throw new FileNotFoundException(error.getMessage());
                }
            }
            return cursor;
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
                    throws FileNotFoundException {
        if (!"r".equals(mode) && !"w".equals(mode)) {
            // TODO: Support seekable file.
            throw new UnsupportedOperationException("The provider does not support seekable file.");
        }
        final Identifier identifier = Identifier.createFromDocumentId(documentId);
        try {
            return mPipeManager.readDocument(mMtpManager, identifier);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId,
            Point sizeHint,
            CancellationSignal signal) throws FileNotFoundException {
        final Identifier identifier = Identifier.createFromDocumentId(documentId);
        try {
            return new AssetFileDescriptor(
                    mPipeManager.readThumbnail(mMtpManager, identifier),
                    0,  // Start offset.
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        try {
            final Identifier identifier = Identifier.createFromDocumentId(documentId);
            final int parentHandle =
                    mMtpManager.getParent(identifier.mDeviceId, identifier.mObjectHandle);
            mMtpManager.deleteDocument(identifier.mDeviceId, identifier.mObjectHandle);
            notifyChildDocumentsChange(new Identifier(
                    identifier.mDeviceId, identifier.mStorageId, parentHandle).toDocumentId());
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    void openDevice(int deviceId) throws IOException {
        mMtpManager.openDevice(deviceId);
        notifyRootsChange();
    }

    void closeDevice(int deviceId) throws IOException {
        mMtpManager.closeDevice(deviceId);
        notifyRootsChange();
    }

    void closeAllDevices() {
        boolean closed = false;
        for (int deviceId : mMtpManager.getOpenedDeviceIds()) {
            try {
                mMtpManager.closeDevice(deviceId);
                closed = true;
            } catch (IOException d) {
                Log.d(TAG, "Failed to close the MTP device: " + deviceId);
            }
        }
        if (closed) {
            notifyRootsChange();
        }
    }

    boolean hasOpenedDevices() {
        return mMtpManager.getOpenedDeviceIds().length != 0;
    }

    private void notifyRootsChange() {
        mResolver.notifyChange(
                DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY), null, false);
    }

    private void notifyChildDocumentsChange(String parentDocumentId) {
        mResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
                null,
                false);
    }
}
