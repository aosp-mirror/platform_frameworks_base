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
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.mtp.MtpObjectInfo;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * DocumentsProvider for MTP devices.
 */
public class MtpDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "com.android.mtp.documents";
    static final String TAG = "MtpDocumentsProvider";
    static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
    };
    static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static MtpDocumentsProvider sSingleton;

    private MtpManager mMtpManager;
    private ContentResolver mResolver;
    private Map<Integer, DeviceToolkit> mDeviceToolkits;
    private RootScanner mRootScanner;
    private Resources mResources;

    /**
     * Provides singleton instance to MtpDocumentsService.
     */
    static MtpDocumentsProvider getInstance() {
        return sSingleton;
    }

    @Override
    public boolean onCreate() {
        sSingleton = this;
        mResources = getContext().getResources();
        mMtpManager = new MtpManager(getContext());
        mResolver = getContext().getContentResolver();
        mDeviceToolkits = new HashMap<Integer, DeviceToolkit>();
        mRootScanner = new RootScanner(mResolver, mMtpManager);
        return true;
    }

    @VisibleForTesting
    void onCreateForTesting(Resources resources, MtpManager mtpManager, ContentResolver resolver) {
        mResources = resources;
        mMtpManager = mtpManager;
        mResolver = resolver;
        mDeviceToolkits = new HashMap<Integer, DeviceToolkit>();
        mRootScanner = new RootScanner(mResolver, mMtpManager);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_ROOT_PROJECTION;
        }
        final MatrixCursor cursor = new MatrixCursor(projection);
        final MtpRoot[] roots = mRootScanner.getRoots();
        for (final MtpRoot root : roots) {
            final Identifier rootIdentifier = new Identifier(root.mDeviceId, root.mStorageId);
            final MatrixCursor.RowBuilder builder = cursor.newRow();
            builder.add(Root.COLUMN_ROOT_ID, rootIdentifier.toRootId());
            builder.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE);
            builder.add(Root.COLUMN_TITLE, root.getRootName(mResources));
            builder.add(Root.COLUMN_DOCUMENT_ID, rootIdentifier.toDocumentId());
            builder.add(Root.COLUMN_AVAILABLE_BYTES , root.mFreeSpace);
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

        if (identifier.mObjectHandle != CursorHelper.DUMMY_HANDLE_FOR_ROOT) {
            MtpObjectInfo objectInfo;
            try {
                objectInfo = mMtpManager.getObjectInfo(
                        identifier.mDeviceId, identifier.mObjectHandle);
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
            final MatrixCursor cursor = new MatrixCursor(projection);
            CursorHelper.addToCursor(
                    objectInfo,
                    new Identifier(identifier.mDeviceId, identifier.mStorageId),
                    cursor.newRow());
            return cursor;
        } else {
            MtpRoot[] roots;
            try {
                roots = mMtpManager.getRoots(identifier.mDeviceId);
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
            for (final MtpRoot root : roots) {
                if (identifier.mStorageId != root.mStorageId)
                    continue;
                final MatrixCursor cursor = new MatrixCursor(projection);
                CursorHelper.addToCursor(mResources, root, cursor.newRow());
                return cursor;
            }
        }

        throw new FileNotFoundException();
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId,
            String[] projection, String sortOrder) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION;
        }
        final Identifier parentIdentifier = Identifier.createFromDocumentId(parentDocumentId);
        try {
            return getDocumentLoader(parentIdentifier).queryChildDocuments(
                    projection, parentIdentifier);
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
                    throws FileNotFoundException {
        final Identifier identifier = Identifier.createFromDocumentId(documentId);
        try {
            switch (mode) {
                case "r":
                    return getPipeManager(identifier).readDocument(mMtpManager, identifier);
                case "w":
                    // TODO: Clear the parent document loader task (if exists) and call notify
                    // when writing is completed.
                    return getPipeManager(identifier).writeDocument(
                            getContext(), mMtpManager, identifier);
                default:
                    // TODO: Add support for seekable files.
                    throw new UnsupportedOperationException(
                            "The provider does not support seekable file.");
            }
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
                    getPipeManager(identifier).readThumbnail(mMtpManager, identifier),
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
            final Identifier parentIdentifier = new Identifier(
                    identifier.mDeviceId, identifier.mStorageId, parentHandle);
            getDocumentLoader(parentIdentifier).clearTask(parentIdentifier);
            notifyChildDocumentsChange(parentIdentifier.toDocumentId());
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public void onTrimMemory(int level) {
      for (final DeviceToolkit toolkit : mDeviceToolkits.values()) {
          toolkit.mDocumentLoader.clearCompletedTasks();
      }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        try {
            final Identifier parentId = Identifier.createFromDocumentId(parentDocumentId);
            final ParcelFileDescriptor pipe[] = ParcelFileDescriptor.createReliablePipe();
            pipe[0].close();  // 0 bytes for a new document.
            final int objectHandle = mMtpManager.createDocument(
                    parentId.mDeviceId,
                    new MtpObjectInfo.Builder()
                            .setStorageId(parentId.mStorageId)
                            .setParent(parentId.mObjectHandle)
                            .setFormat(CursorHelper.mimeTypeToFormatType(displayName, mimeType))
                            .setName(displayName)
                            .build(), pipe[1]);
            final String documentId = new Identifier(parentId.mDeviceId, parentId.mStorageId,
                   objectHandle).toDocumentId();
            getDocumentLoader(parentId).clearTask(parentId);
            notifyChildDocumentsChange(parentDocumentId);
            return documentId;
        } catch (IOException error) {
            Log.e(TAG, error.getMessage());
            throw new FileNotFoundException(error.getMessage());
        }
    }

    void openDevice(int deviceId) throws IOException {
        mMtpManager.openDevice(deviceId);
        mDeviceToolkits.put(deviceId, new DeviceToolkit(mMtpManager, mResolver));
        mRootScanner.scanNow();
    }

    void closeDevice(int deviceId) throws IOException {
        // TODO: Flush the device before closing (if not closed externally).
        getDeviceToolkit(deviceId).mDocumentLoader.clearTasks();
        mDeviceToolkits.remove(deviceId);
        mMtpManager.closeDevice(deviceId);
        mRootScanner.scanNow();
    }

    void closeAllDevices() {
        boolean closed = false;
        for (int deviceId : mMtpManager.getOpenedDeviceIds()) {
            try {
                mMtpManager.closeDevice(deviceId);
                getDeviceToolkit(deviceId).mDocumentLoader.clearTasks();
                closed = true;
            } catch (IOException d) {
                Log.d(TAG, "Failed to close the MTP device: " + deviceId);
            }
        }
        if (closed) {
            mRootScanner.scanNow();
        }
    }

    boolean hasOpenedDevices() {
        return mMtpManager.getOpenedDeviceIds().length != 0;
    }

    private void notifyChildDocumentsChange(String parentDocumentId) {
        mResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
                null,
                false);
    }

    private DeviceToolkit getDeviceToolkit(int deviceId) throws FileNotFoundException {
        final DeviceToolkit toolkit = mDeviceToolkits.get(deviceId);
        if (toolkit == null) {
            throw new FileNotFoundException();
        }
        return toolkit;
    }

    private PipeManager getPipeManager(Identifier identifier) throws FileNotFoundException {
        return getDeviceToolkit(identifier.mDeviceId).mPipeManager;
    }

    private DocumentLoader getDocumentLoader(Identifier identifier) throws FileNotFoundException {
        return getDeviceToolkit(identifier.mDeviceId).mDocumentLoader;
    }

    private static class DeviceToolkit {
        public final PipeManager mPipeManager;
        public final DocumentLoader mDocumentLoader;

        public DeviceToolkit(MtpManager manager, ContentResolver resolver) {
            mPipeManager = new PipeManager();
            mDocumentLoader = new DocumentLoader(manager, resolver);
        }
    }
}
