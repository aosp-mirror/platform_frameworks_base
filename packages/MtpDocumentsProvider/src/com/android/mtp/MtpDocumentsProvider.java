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
    private static final String TAG = "MtpDocumentsProvider";
    public static final String AUTHORITY = "com.android.mtp.documents";

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

    private MtpManager mDeviceModel;
    private ContentResolver mResolver;

    @Override
    public boolean onCreate() {
        mDeviceModel = new MtpManager(getContext());
        mResolver = getContext().getContentResolver();
        return true;
    }

    @VisibleForTesting
    void onCreateForTesting(MtpManager deviceModel, ContentResolver resolver) {
        this.mDeviceModel = deviceModel;
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

    // TODO: Remove annotation when the method starts to be used.
    @VisibleForTesting
    void openDevice(int deviceId) {
        try {
            mDeviceModel.openDevice(deviceId);
            notifyRootsUpdate();
        } catch (IOException error) {
            Log.d(TAG, "Failed to open the MTP device: " + deviceId);
        }
    }

    // TODO: Remove annotation when the method starts to be used.
    @VisibleForTesting
    void closeDevice(int deviceId) {
        try {
            mDeviceModel.closeDevice(deviceId);
            notifyRootsUpdate();
        } catch (IOException error) {
            Log.d(TAG, "Failed to close the MTP device: " + deviceId);
        }
    }

    // TODO: Remove annotation when the method starts to be used.
    @VisibleForTesting
    void closeAllDevices() {
        boolean closed = false;
        for (int deviceId : mDeviceModel.getOpenedDeviceIds()) {
            try {
                mDeviceModel.closeDevice(deviceId);
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
}
