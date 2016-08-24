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
import android.content.Context;
import android.content.UriPermission;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDiskIOException;
import android.graphics.Point;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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
    private Context mContext;

    /**
     * Provides singleton instance to MtpDocumentsService.
     */
    static MtpDocumentsProvider getInstance() {
        return sSingleton;
    }

    @Override
    public boolean onCreate() {
        sSingleton = this;
        mContext = getContext();
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
        try {
            final int bootCount = Settings.Global.getInt(mResolver, Settings.Global.BOOT_COUNT, -1);
            final int lastBootCount = mDatabase.getLastBootCount();
            if (bootCount != -1 && bootCount != lastBootCount) {
                mDatabase.setLastBootCount(bootCount);
                final List<UriPermission> permissions =
                        mResolver.getOutgoingPersistedUriPermissions();
                final Uri[] uris = new Uri[permissions.size()];
                for (int i = 0; i < permissions.size(); i++) {
                    uris[i] = permissions.get(i).getUri();
                }
                mDatabase.cleanDatabase(uris);
            }
        } catch (SQLiteDiskIOException error) {
            // It can happen due to disk shortage.
            Log.e(TAG, "Failed to clean database.", error);
            return false;
        }

        // TODO: Mount AppFuse on demands.
        try {
            mAppFuse.mount(getContext().getSystemService(StorageManager.class));
        } catch (IOException error) {
            Log.e(TAG, "Failed to start app fuse.", error);
            return false;
        }

        resume();
        return true;
    }

    @VisibleForTesting
    boolean onCreateForTesting(
            Context context,
            Resources resources,
            MtpManager mtpManager,
            ContentResolver resolver,
            MtpDatabase database,
            StorageManager storageManager,
            ServiceIntentSender intentSender) {
        mContext = context;
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
            // Turn off MODE_CREATE because openDocument does not allow to create new files.
            final int modeFlag =
                    ParcelFileDescriptor.parseMode(mode) & ~ParcelFileDescriptor.MODE_CREATE;
            if ((modeFlag & ParcelFileDescriptor.MODE_READ_ONLY) != 0) {
                long fileSize;
                try {
                    fileSize = getFileSize(documentId);
                } catch (UnsupportedOperationException exception) {
                    fileSize = -1;
                }
                if (MtpDeviceRecord.isPartialReadSupported(
                        device.operationsSupported, fileSize)) {
                    return mAppFuse.openFile(Integer.parseInt(documentId), modeFlag);
                } else {
                    // If getPartialObject{|64} are not supported for the device, returns
                    // non-seekable pipe FD instead.
                    return getPipeManager(identifier).readDocument(mMtpManager, identifier);
                }
            } else if ((modeFlag & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
                // TODO: Clear the parent document loader task (if exists) and call notify
                // when writing is completed.
                if (MtpDeviceRecord.isWritingSupported(device.operationsSupported)) {
                    return mAppFuse.openFile(Integer.parseInt(documentId), modeFlag);
                } else {
                    throw new UnsupportedOperationException(
                            "The device does not support writing operation.");
                }
            } else {
                // TODO: Add support for "rw" mode.
                throw new UnsupportedOperationException("The provider does not support 'rw' mode.");
            }
        } catch (FileNotFoundException | RuntimeException error) {
            Log.e(MtpDocumentsProvider.TAG, "openDocument", error);
            throw error;
        } catch (IOException error) {
            Log.e(MtpDocumentsProvider.TAG, "openDocument", error);
            throw new IllegalStateException(error);
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
            getDocumentLoader(parentIdentifier).cancelTask(parentIdentifier);
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
        final Identifier parentId;
        final MtpDeviceRecord record;
        final ParcelFileDescriptor[] pipe;
        try {
            parentId = mDatabase.createIdentifier(parentDocumentId);
            openDevice(parentId.mDeviceId);
            record = getDeviceToolkit(parentId.mDeviceId).mDeviceRecord;
            if (!MtpDeviceRecord.isWritingSupported(record.operationsSupported)) {
                throw new UnsupportedOperationException(
                        "Writing operation is not supported by the device.");
            }
            pipe = ParcelFileDescriptor.createReliablePipe();
            int objectHandle = -1;
            MtpObjectInfo info = null;
            try {
                pipe[0].close();  // 0 bytes for a new document.

                final int formatCode = Document.MIME_TYPE_DIR.equals(mimeType) ?
                        MtpConstants.FORMAT_ASSOCIATION :
                        MediaFile.getFormatCode(displayName, mimeType);
                info = new MtpObjectInfo.Builder()
                        .setStorageId(parentId.mStorageId)
                        .setParent(parentId.mObjectHandle)
                        .setFormat(formatCode)
                        .setName(displayName)
                        .build();

                final String[] parts = FileUtils.splitFileName(mimeType, displayName);
                final String baseName = parts[0];
                final String extension = parts[1];
                for (int i = 0; i <= 32; i++) {
                    final MtpObjectInfo infoUniqueName;
                    if (i == 0) {
                        infoUniqueName = info;
                    } else {
                        String suffixedName = baseName + " (" + i + " )";
                        if (!extension.isEmpty()) {
                            suffixedName += "." + extension;
                        }
                        infoUniqueName =
                                new MtpObjectInfo.Builder(info).setName(suffixedName).build();
                    }
                    try {
                        objectHandle = mMtpManager.createDocument(
                                parentId.mDeviceId, infoUniqueName, pipe[1]);
                        break;
                    } catch (SendObjectInfoFailure exp) {
                        // This can be caused when we have an existing file with the same name.
                        continue;
                    }
                }
            } finally {
                pipe[1].close();
            }
            if (objectHandle == -1) {
                throw new IllegalArgumentException(
                        "The file name \"" + displayName + "\" is conflicted with existing files " +
                        "and the provider failed to find unique name.");
            }
            final MtpObjectInfo infoWithHandle =
                    new MtpObjectInfo.Builder(info).setObjectHandle(objectHandle).build();
            final String documentId = mDatabase.putNewDocument(
                    parentId.mDeviceId, parentDocumentId, record.operationsSupported,
                    infoWithHandle, 0l);
            getDocumentLoader(parentId).cancelTask(parentId);
            notifyChildDocumentsChange(parentDocumentId);
            return documentId;
        } catch (FileNotFoundException | RuntimeException error) {
            Log.e(TAG, "createDocument", error);
            throw error;
        } catch (IOException error) {
            Log.e(TAG, "createDocument", error);
            throw new IllegalStateException(error);
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
            } catch (InterruptedException | IOException | TimeoutException e) {
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
                if (cursor.isNull(0)) {
                    throw new UnsupportedOperationException();
                }
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
        private final Map<Long, MtpFileWriter> mWriters = new HashMap<>();

        @Override
        public long getFileSize(int inode) throws FileNotFoundException {
            return MtpDocumentsProvider.this.getFileSize(String.valueOf(inode));
        }

        @Override
        public long readObjectBytes(
                int inode, long offset, long size, byte[] buffer) throws IOException {
            final Identifier identifier = mDatabase.createIdentifier(Integer.toString(inode));
            final MtpDeviceRecord record = getDeviceToolkit(identifier.mDeviceId).mDeviceRecord;

            if (MtpDeviceRecord.isSupported(
                    record.operationsSupported, MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64)) {
                return mMtpManager.getPartialObject64(
                        identifier.mDeviceId, identifier.mObjectHandle, offset, size, buffer);
            }

            if (0 <= offset && offset <= 0xffffffffL && MtpDeviceRecord.isSupported(
                    record.operationsSupported, MtpConstants.OPERATION_GET_PARTIAL_OBJECT)) {
                return mMtpManager.getPartialObject(
                        identifier.mDeviceId, identifier.mObjectHandle, offset, size, buffer);
            }

            throw new UnsupportedOperationException();
        }

        @Override
        public int writeObjectBytes(
                long fileHandle, int inode, long offset, int size, byte[] bytes)
                throws IOException, ErrnoException {
            final MtpFileWriter writer;
            if (mWriters.containsKey(fileHandle)) {
                writer = mWriters.get(fileHandle);
            } else {
                writer = new MtpFileWriter(mContext, String.valueOf(inode));
                mWriters.put(fileHandle, writer);
            }
            return writer.write(offset, size, bytes);
        }

        @Override
        public void flushFileHandle(long fileHandle) throws IOException, ErrnoException {
            final MtpFileWriter writer = mWriters.get(fileHandle);
            if (writer == null) {
                // File handle for reading.
                return;
            }
            final MtpDeviceRecord device = getDeviceToolkit(
                    mDatabase.createIdentifier(writer.getDocumentId()).mDeviceId).mDeviceRecord;
            writer.flush(mMtpManager, mDatabase, device.operationsSupported);
        }

        @Override
        public void closeFileHandle(long fileHandle) throws IOException, ErrnoException {
            final MtpFileWriter writer = mWriters.get(fileHandle);
            if (writer == null) {
                // File handle for reading.
                return;
            }
            try {
                writer.close();
            } finally {
                mWriters.remove(fileHandle);
            }
        }
    }
}
