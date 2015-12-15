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
import android.graphics.Point;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
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

    private final Object mDeviceListLock = new Object();

    private static MtpDocumentsProvider sSingleton;

    private MtpManager mMtpManager;
    private ContentResolver mResolver;
    @GuardedBy("mDeviceListLock")
    private Map<Integer, DeviceToolkit> mDeviceToolkits;
    private RootScanner mRootScanner;
    private Resources mResources;
    private MtpDatabase mDatabase;

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
        mDatabase = new MtpDatabase(getContext(), MtpDatabaseConstants.FLAG_DATABASE_IN_FILE);
        mRootScanner = new RootScanner(mResolver, mResources, mMtpManager, mDatabase);
        resume();
        return true;
    }

    @VisibleForTesting
    void onCreateForTesting(
            Resources resources,
            MtpManager mtpManager,
            ContentResolver resolver,
            MtpDatabase database) {
        mResources = resources;
        mMtpManager = mtpManager;
        mResolver = resolver;
        mDeviceToolkits = new HashMap<Integer, DeviceToolkit>();
        mDatabase = database;
        mRootScanner = new RootScanner(mResolver, mResources, mMtpManager, mDatabase);
        resume();
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_ROOT_PROJECTION;
        }
        final Cursor cursor = mDatabase.queryRoots(projection);
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
        return mDatabase.queryDocument(documentId, projection);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId,
            String[] projection, String sortOrder) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION;
        }
        final Identifier parentIdentifier = mDatabase.createIdentifier(parentDocumentId);
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
        final Identifier identifier = mDatabase.createIdentifier(documentId);
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
        final Identifier identifier = mDatabase.createIdentifier(documentId);
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
            final Identifier identifier = mDatabase.createIdentifier(documentId);
            final Identifier parentIdentifier =
                    mDatabase.createIdentifier(mDatabase.getParentId(documentId));
            mMtpManager.deleteDocument(identifier.mDeviceId, identifier.mObjectHandle);
            mDatabase.deleteDocument(documentId);
            getDocumentLoader(parentIdentifier).clearTask(parentIdentifier);
            notifyChildDocumentsChange(parentIdentifier.mDocumentId);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public void onTrimMemory(int level) {
        synchronized (mDeviceListLock) {
            for (final DeviceToolkit toolkit : mDeviceToolkits.values()) {
                toolkit.mDocumentLoader.clearCompletedTasks();
            }
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        try {
            final Identifier parentId = mDatabase.createIdentifier(parentDocumentId);
            final ParcelFileDescriptor pipe[] = ParcelFileDescriptor.createReliablePipe();
            pipe[0].close();  // 0 bytes for a new document.
            final int formatCode = Document.MIME_TYPE_DIR.equals(mimeType) ?
                    MtpConstants.FORMAT_ASSOCIATION :
                    MediaFile.getFormatCode(displayName, mimeType);
            final MtpObjectInfo info = new MtpObjectInfo.Builder()
                    .setStorageId(parentId.mStorageId)
                    .setParent(parentId.mObjectHandle)
                    .setFormat(formatCode)
                    .setName(displayName)
                    .build();
            final int objectHandle = mMtpManager.createDocument(parentId.mDeviceId, info, pipe[1]);
            final MtpObjectInfo infoWithHandle =
                    new MtpObjectInfo.Builder(info).setObjectHandle(objectHandle).build();
            final String documentId = mDatabase.putNewDocument(
                    parentId.mDeviceId, parentDocumentId, infoWithHandle);
            getDocumentLoader(parentId).clearTask(parentId);
            notifyChildDocumentsChange(parentDocumentId);
            return documentId;
        } catch (IOException error) {
            Log.e(TAG, error.getMessage());
            throw new FileNotFoundException(error.getMessage());
        }
    }

    void openDevice(int deviceId) throws IOException {
        synchronized (mDeviceListLock) {
            mMtpManager.openDevice(deviceId);
            mDeviceToolkits.put(
                    deviceId, new DeviceToolkit(mMtpManager, mResolver, mDatabase));
        }
        mRootScanner.resume();
    }

    void closeDevice(int deviceId) throws IOException, InterruptedException {
        synchronized (mDeviceListLock) {
            closeDeviceInternal(deviceId);
            mDatabase.removeDeviceRows(deviceId);
        }
        mRootScanner.notifyChange();
    }

    int[] getOpenedDeviceIds() {
        synchronized (mDeviceListLock) {
            return mMtpManager.getOpenedDeviceIds();
        }
    }

    String getDeviceName(int deviceId) throws IOException {
        synchronized (mDeviceListLock) {
            return mMtpManager.getDeviceName(deviceId);
        }
    }

    /**
     * Finalize the content provider for unit tests.
     */
    @Override
    public void shutdown() {
        synchronized (mDeviceListLock) {
            try {
                for (final int id : mMtpManager.getOpenedDeviceIds()) {
                    closeDeviceInternal(id);
                }
            } catch (InterruptedException|IOException e) {
                // It should fail unit tests by throwing runtime exception.
                throw new RuntimeException(e);
            } finally {
                mDatabase.close();
                super.shutdown();
            }
        }
    }

    private void notifyChildDocumentsChange(String parentDocumentId) {
        mResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
                null,
                false);
    }

    /**
     * Clears MTP identifier in the database.
     */
    private void resume() {
        synchronized (mDeviceListLock) {
            mDatabase.getMapper().clearMapping();
        }
    }

    private void closeDeviceInternal(int deviceId) throws IOException, InterruptedException {
        // TODO: Flush the device before closing (if not closed externally).
        getDeviceToolkit(deviceId).mDocumentLoader.clearTasks();
        mDeviceToolkits.remove(deviceId);
        mMtpManager.closeDevice(deviceId);
        if (getOpenedDeviceIds().length == 0) {
            mRootScanner.pause();
        }
    }

    private DeviceToolkit getDeviceToolkit(int deviceId) throws FileNotFoundException {
        synchronized (mDeviceListLock) {
            final DeviceToolkit toolkit = mDeviceToolkits.get(deviceId);
            if (toolkit == null) {
                throw new FileNotFoundException();
            }
            return toolkit;
        }
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

        public DeviceToolkit(MtpManager manager, ContentResolver resolver, MtpDatabase database) {
            mPipeManager = new PipeManager();
            mDocumentLoader = new DocumentLoader(manager, resolver, database);
        }
    }
}
