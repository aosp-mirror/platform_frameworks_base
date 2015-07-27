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
import android.database.Cursor;
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
        return true;
    }

    @VisibleForTesting
    void onCreateForTesting(MtpManager mtpManager, ContentResolver resolver) {
        this.mMtpManager = mtpManager;
        this.mResolver = resolver;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId,
            String[] projection, String sortOrder)
            throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    void openDevice(int deviceId) throws IOException {
        mMtpManager.openDevice(deviceId);
        notifyRootsUpdate();
    }

    void closeDevice(int deviceId) throws IOException {
        mMtpManager.closeDevice(deviceId);
        notifyRootsUpdate();
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
            notifyRootsUpdate();
        }
    }

    private void notifyRootsUpdate() {
        mResolver.notifyChange(
                DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY),
                null,
                false);
    }

    boolean hasOpenedDevices() {
        return mMtpManager.getOpenedDeviceIds().length != 0;
    }
}
