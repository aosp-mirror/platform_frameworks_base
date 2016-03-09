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
import android.content.UriPermission;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.mtp.exceptions.BusyDeviceException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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

    static final boolean DEBUG = false;

    private final Object mDeviceListLock = new Object();

    private static MtpDocumentsProvider sSingleton;

    private MtpManager mMtpManager;
    private ContentResolver mResolver;
    @GuardedBy("mDeviceListLock")
    private Map<Integer, DeviceToolkit> mDeviceToolkits;
    private RootScanner mRootScanner;
    private Resources mResources;
    private MtpDatabase mDatabase;
    private AppFuse mAppFuse;
    private ServiceIntentSender mIntentSender;

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
        mRootScanner = new RootScanner(mResolver, mMtpManager, mDatabase);
        mAppFuse = new AppFuse(TAG, new AppFuseCallback());
        mIntentSender = new ServiceIntentSender(getContext());

        // Check boot count and cleans database if it's first time to launch MtpDocumentsProvider
        // after booting.
        final int bootCount = Settings.Global.getInt(mResolver, Settings.Global.BOOT_COUNT, -1);
        final int lastBootCount = mDatabase.getLastBootCount();
        if (bootCount != -1 && bootCount != lastBootCount) {
            mDatabase.setLastBootCount(bootCount);
            final List<UriPermission> permissions = mResolver.getOutgoingPersistedUriPermissions();
            final Uri[] uris = new Uri[permissions.size()];
            for (int i = 0; i < permissions.size(); i++) {
                uris[i] = permissions.get(i).getUri();
            }
            mDatabase.cleanDatabase(uris);
        }

        // TODO: Mount AppFuse on demands.
        try {
            mAppFuse.mount(getContext().getSystemService(StorageManager.class));
        } catch (IOException e) {
            Log.e(TAG, "Failed to start app fuse.", e);
            return false;
        }
        resume();
        return true;
    }

    @VisibleForTesting
    boolean onCreateForTesting(
            Resources resources,
            MtpManager mtpManager,
            ContentResolver resolver,
            MtpDatabase database,
            StorageManager storageManager,
            ServiceIntentSender intentSender) {
        mResources = resources;
        mMtpManager = mtpManager;
        mResolver = resolver;
        mDeviceToolkits = new HashMap<Integer, DeviceToolkit>();
        mDatabase = database;
        mRootScanner = new RootScanner(mResolver, mMtpManager, mDatabase);
        mAppFuse = new AppFuse(TAG, new AppFuseCallback());
        mIntentSender = intentSender;

        // TODO: Mount AppFuse on demands.
        try {
            mAppFuse.mount(storageManager);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start app fuse.", e);
            return false;
        }
        resume();
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_ROOT_PROJECTION;
        }
        final Cursor cursor = mDatabase.queryRoots(mResources, projection);
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
        if (DEBUG) {
            Log.d(TAG, "queryChildDocuments: " + parentDocumentId);
        }
        if (projection == null) {
            projection = MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION;
        }
        Identifier parentIdentifier = mDatabase.createIdentifier(parentDocumentId);
        try {
            openDevice(parentIdentifier.mDeviceId);
            if (parentIdentifier.mDocumentType == MtpDatabaseConstants.DOCUMENT_TYPE_DEVICE) {
                final String[] storageDocIds = mDatabase.getStorageDocumentIds(parentDocumentId);
                if (storageDocIds.length == 0) {
                    // Remote device does not provide storages. Maybe it is locked.
                    return createErrorCursor(projection, R.string.error_locked_device);
                } else if (storageDocIds.length > 1) {
                    // Returns storage list from database.
                    return mDatabase.queryChildDocuments(projection, parentDocumentId);
                }

                // Exact one storage is found. Skip storage and returns object in the single
                // storage.
                parentIdentifier = mDatabase.createIdentifier(storageDocIds[0]);
            }

            // Returns object list from document loader.
            return getDocumentLoader(parentIdentifier).queryChildDocuments(
                    projection, parentIdentifier);
        } catch (BusyDeviceException exception) {
            return createErrorCursor(projection, R.string.error_busy_device);
        } catch (IOException exception) {
            Log.e(MtpDocumentsProvider.TAG, "queryChildDocuments", exception);
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
                    throws FileNotFoundException {
        if (DEBUG) {
            Log.d(TAG, "openDocument: " + documentId);
        }
        final Identifier identifier = mDatabase.createIdentifier(documentId);
        try {
            openDevice(identifier.mDeviceId);
            final MtpDeviceRecord device = getDeviceToolkit(identifier.mDeviceId).mDeviceRecord;
            switch (mode) {
                case "r":
                    final long fileSize = getFileSize(documentId);
                    // MTP getPartialObject operation does not support files that are larger than
                    // 4GB. Fallback to non-seekable file descriptor.
                    // TODO: Use getPartialObject64 for MTP devices that support Android vendor
                    // extension.
                    if (MtpDeviceRecord.isPartialReadSupported(
                            device.operationsSupported, fileSize)) {
                        return mAppFuse.openFile(Integer.parseInt(documentId));
                    } else {
                        return getPipeManager(identifier).readDocument(mMtpManager, identifier);
                    }
                case "w":
                    // TODO: Clear the parent document loader task (if exists) and call notify
                    // when writing is completed.
                    if (MtpDeviceRecord.isWritingSupported(device.operationsSupported)) {
                        return getPipeManager(identifier).writeDocument(
                                getContext(), mMtpManager, identifier, device.operationsSupported);
                    } else {
                        throw new UnsupportedOperationException(
                                "The device does not support writing operation.");
                    }
                case "rw":
                    // TODO: Add support for "rw" mode.
                    throw new UnsupportedOperationException(
                            "The provider does not support 'rw' mode.");
                default:
                    throw new IllegalArgumentException("Unknown mode for openDocument: " + mode);
            }
        } catch (IOException error) {
            Log.e(MtpDocumentsProvider.TAG, "openDocument", error);
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
            openDevice(identifier.mDeviceId);
            return new AssetFileDescriptor(
                    getPipeManager(identifier).readThumbnail(mMtpManager, identifier),
                    0,  // Start offset.
                    AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (IOException error) {
            Log.e(MtpDocumentsProvider.TAG, "openDocumentThumbnail", error);
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        try {
            final Identifier identifier = mDatabase.createIdentifier(documentId);
            openDevice(identifier.mDeviceId);
            final Identifier parentIdentifier = mDatabase.getParentIdentifier(documentId);
            mMtpManager.deleteDocument(identifier.mDeviceId, identifier.mObjectHandle);
            mDatabase.deleteDocument(documentId);
            getDocumentLoader(parentIdentifier).clearTask(parentIdentifier);
            notifyChildDocumentsChange(parentIdentifier.mDocumentId);
            if (parentIdentifier.mDocumentType == MtpDatabaseConstants.DOCUMENT_TYPE_STORAGE) {
                // If the parent is storage, the object might be appeared as child of device because
                // we skip storage when the device has only one storage.
                final Identifier deviceIdentifier = mDatabase.getParentIdentifier(
                        parentIdentifier.mDocumentId);
                notifyChildDocumentsChange(deviceIdentifier.mDocumentId);
            }
        } catch (IOException error) {
            Log.e(MtpDocumentsProvider.TAG, "deleteDocument", error);
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
        if (DEBUG) {
            Log.d(TAG, "createDocument: " + displayName);
        }
        try {
            final Identifier parentId = mDatabase.createIdentifier(parentDocumentId);
            openDevice(parentId.mDeviceId);
            final MtpDeviceRecord record = getDeviceToolkit(parentId.mDeviceId).mDeviceRecord;
            if (!MtpDeviceRecord.isWritingSupported(record.operationsSupported)) {
                throw new UnsupportedOperationException();
            }
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
                    parentId.mDeviceId, parentDocumentId, record.operationsSupported,
                    infoWithHandle);
            getDocumentLoader(parentId).clearTask(parentId);
            notifyChildDocumentsChange(parentDocumentId);
            return documentId;
        } catch (IOException error) {
            Log.e(TAG, "createDocument", error);
            throw new FileNotFoundException(error.getMessage());
        }
    }

    void openDevice(int deviceId) throws IOException {
        synchronized (mDeviceListLock) {
            if (mDeviceToolkits.containsKey(deviceId)) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Open device " + deviceId);
            }
            final MtpDeviceRecord device = mMtpManager.openDevice(deviceId);
            final DeviceToolkit toolkit =
                    new DeviceToolkit(mMtpManager, mResolver, mDatabase, device);
            mDeviceToolkits.put(deviceId, toolkit);
            mIntentSender.sendUpdateNotificationIntent();
            try {
                mRootScanner.resume().await();
            } catch (InterruptedException error) {
                Log.e(TAG, "openDevice", error);
            }
            // Resume document loader to remap disconnected document ID. Must be invoked after the
            // root scanner resumes.
            toolkit.mDocumentLoader.resume();
        }
    }

    void closeDevice(int deviceId) throws IOException, InterruptedException {
        synchronized (mDeviceListLock) {
            closeDeviceInternal(deviceId);
        }
        mRootScanner.resume();
        mIntentSender.sendUpdateNotificationIntent();
    }

    MtpDeviceRecord[] getOpenedDeviceRecordsCache() {
        synchronized (mDeviceListLock) {
            final MtpDeviceRecord[] records = new MtpDeviceRecord[mDeviceToolkits.size()];
            int i = 0;
            for (final DeviceToolkit toolkit : mDeviceToolkits.values()) {
                records[i] = toolkit.mDeviceRecord;
                i++;
            }
            return records;
        }
    }

    /**
     * Obtains document ID for the given device ID.
     * @param deviceId
     * @return document ID
     * @throws FileNotFoundException device ID has not been build.
     */
    public String getDeviceDocumentId(int deviceId) throws FileNotFoundException {
        return mDatabase.getDeviceDocumentId(deviceId);
    }

    /**
     * Resumes root scanner to handle the update of device list.
     */
    void resumeRootScanner() {
        if (DEBUG) {
            Log.d(MtpDocumentsProvider.TAG, "resumeRootScanner");
        }
        mRootScanner.resume();
    }

    /**
     * Finalize the content provider for unit tests.
     */
    @Override
    public void shutdown() {
        synchronized (mDeviceListLock) {
            try {
                // Copy the opened key set because it will be modified when closing devices.
                final Integer[] keySet =
                        mDeviceToolkits.keySet().toArray(new Integer[mDeviceToolkits.size()]);
                for (final int id : keySet) {
                    closeDeviceInternal(id);
                }
                mRootScanner.pause();
            } catch (InterruptedException|IOException e) {
                // It should fail unit tests by throwing runtime exception.
                throw new RuntimeException(e);
            } finally {
                mDatabase.close();
                mAppFuse.close();
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
        if (!mDeviceToolkits.containsKey(deviceId)) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Close device " + deviceId);
        }
        getDeviceToolkit(deviceId).close();
        mDeviceToolkits.remove(deviceId);
        mMtpManager.closeDevice(deviceId);
        if (mDeviceToolkits.size() == 0) {
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

    private long getFileSize(String documentId) throws FileNotFoundException {
        final Cursor cursor = mDatabase.queryDocument(
                documentId,
                MtpDatabase.strings(Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME));
        try {
            if (cursor.moveToNext()) {
                return cursor.getLong(0);
            } else {
                throw new FileNotFoundException();
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Creates empty cursor with specific error message.
     *
     * @param projection Column names.
     * @param stringResId String resource ID of error message.
     * @return Empty cursor with error message.
     */
    private Cursor createErrorCursor(String[] projection, int stringResId) {
        final Bundle bundle = new Bundle();
        bundle.putString(DocumentsContract.EXTRA_ERROR, mResources.getString(stringResId));
        final Cursor cursor = new MatrixCursor(projection);
        cursor.setExtras(bundle);
        return cursor;
    }

    private static class DeviceToolkit implements AutoCloseable {
        public final PipeManager mPipeManager;
        public final DocumentLoader mDocumentLoader;
        public final MtpDeviceRecord mDeviceRecord;

        public DeviceToolkit(MtpManager manager,
                             ContentResolver resolver,
                             MtpDatabase database,
                             MtpDeviceRecord record) {
            mPipeManager = new PipeManager(database);
            mDocumentLoader = new DocumentLoader(record, manager, resolver, database);
            mDeviceRecord = record;
        }

        @Override
        public void close() throws InterruptedException {
            mPipeManager.close();
            mDocumentLoader.close();
        }
    }

    private class AppFuseCallback implements AppFuse.Callback {
        @Override
        public long readObjectBytes(
                int inode, long offset, long size, byte[] buffer) throws IOException {
            final Identifier identifier = mDatabase.createIdentifier(Integer.toString(inode));
            final MtpDeviceRecord record = getDeviceToolkit(identifier.mDeviceId).mDeviceRecord;
            if (MtpDeviceRecord.isPartialReadSupported(record.operationsSupported, offset)) {
                return mMtpManager.getPartialObject(
                        identifier.mDeviceId, identifier.mObjectHandle, offset, size, buffer);
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public long getFileSize(int inode) throws FileNotFoundException {
            return MtpDocumentsProvider.this.getFileSize(String.valueOf(inode));
        }
    }
}
