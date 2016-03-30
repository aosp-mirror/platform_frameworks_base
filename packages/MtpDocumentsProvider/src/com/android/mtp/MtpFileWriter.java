/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.Context;
import android.mtp.MtpObjectInfo;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.IOException;

class MtpFileWriter implements AutoCloseable {
    final ParcelFileDescriptor mCacheFd;
    final String mDocumentId;
    boolean mDirty;

    MtpFileWriter(Context context, String documentId) throws IOException {
        mDocumentId = documentId;
        mDirty = false;
        final File tempFile = File.createTempFile("mtp", "tmp", context.getCacheDir());
        mCacheFd = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_READ_WRITE |
                ParcelFileDescriptor.MODE_TRUNCATE |
                ParcelFileDescriptor.MODE_CREATE);
        tempFile.delete();
    }

    String getDocumentId() {
        return mDocumentId;
    }

    int write(long offset, int size, byte[] bytes) throws IOException, ErrnoException {
        Preconditions.checkArgumentNonnegative(offset, "offset");
        Preconditions.checkArgumentNonnegative(size, "size");
        Preconditions.checkArgument(size <= bytes.length);
        if (size == 0) {
            return 0;
        }
        mDirty = true;
        Os.lseek(mCacheFd.getFileDescriptor(), offset, OsConstants.SEEK_SET);
        return Os.write(mCacheFd.getFileDescriptor(), bytes, 0, size);
    }

    void flush(MtpManager manager, MtpDatabase database, int[] operationsSupported)
            throws IOException, ErrnoException {
        // Skip unnecessary flush.
        if (!mDirty) {
            return;
        }

        // Get the placeholder object info.
        final Identifier identifier = database.createIdentifier(mDocumentId);
        final MtpObjectInfo placeholderObjectInfo =
                manager.getObjectInfo(identifier.mDeviceId, identifier.mObjectHandle);

        // Delete the target object info if it already exists (as a placeholder).
        manager.deleteDocument(identifier.mDeviceId, identifier.mObjectHandle);

        // Create the target object info with a correct file size and upload the file.
        final long size = Os.lseek(mCacheFd.getFileDescriptor(), 0, OsConstants.SEEK_END);
        final MtpObjectInfo targetObjectInfo = new MtpObjectInfo.Builder(placeholderObjectInfo)
                .setCompressedSize(size)
                .build();

        Os.lseek(mCacheFd.getFileDescriptor(), 0, OsConstants.SEEK_SET);
        final int newObjectHandle = manager.createDocument(
                identifier.mDeviceId, targetObjectInfo, mCacheFd);

        final MtpObjectInfo newObjectInfo = manager.getObjectInfo(
                identifier.mDeviceId, newObjectHandle);
        final Identifier parentIdentifier =
                database.getParentIdentifier(identifier.mDocumentId);
        database.updateObject(
                identifier.mDocumentId,
                identifier.mDeviceId,
                parentIdentifier.mDocumentId,
                operationsSupported,
                newObjectInfo,
                size);

        mDirty = false;
    }

    @Override
    public void close() throws IOException {
        mCacheFd.close();
    }
}
