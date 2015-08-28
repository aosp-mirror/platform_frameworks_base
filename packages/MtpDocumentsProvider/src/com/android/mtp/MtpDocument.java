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

import android.database.MatrixCursor;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.util.Date;

class MtpDocument {
    static final int DUMMY_HANDLE_FOR_ROOT = 0;

    private final int mObjectHandle;
    private final int mFormat;
    private final String mName;
    private final Date mDateModified;
    private final int mSize;
    private final int mThumbSize;
    private final boolean mReadOnly;

    /**
     * Constructor for root document.
     */
    MtpDocument(MtpRoot root) {
        this(DUMMY_HANDLE_FOR_ROOT,
             0x3001,  // Directory.
             root.mDescription,
             null,    // Unknown name.
             (int) Math.min(root.mMaxCapacity - root.mFreeSpace, Integer.MAX_VALUE),
             0,       // Total size.
             true);   // Writable.
    }

    MtpDocument(MtpObjectInfo objectInfo) {
        this(objectInfo.getObjectHandle(),
             objectInfo.getFormat(),
             objectInfo.getName(),
             objectInfo.getDateModified() != 0 ? new Date(objectInfo.getDateModified()) : null,
             objectInfo.getCompressedSize(),
             objectInfo.getThumbCompressedSize(),
             objectInfo.getProtectionStatus() != 0);
    }

    MtpDocument(int objectHandle,
                int format,
                String name,
                Date dateModified,
                int size,
                int thumbSize,
                boolean readOnly) {
        this.mObjectHandle = objectHandle;
        this.mFormat = format;
        this.mName = name;
        this.mDateModified = dateModified;
        this.mSize = size;
        this.mThumbSize = thumbSize;
        this.mReadOnly = readOnly;
    }

    void addToCursor(Identifier rootIdentifier, MatrixCursor.RowBuilder builder) {
        final Identifier identifier = new Identifier(
                rootIdentifier.mDeviceId, rootIdentifier.mStorageId, mObjectHandle);
        final String mimeType = formatTypeToMimeType(mFormat);

        int flag = 0;
        if (mObjectHandle != DUMMY_HANDLE_FOR_ROOT) {
            if (mThumbSize > 0) {
                flag |= DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
            }
            if (!mReadOnly) {
                flag |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            }
        }
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR && !mReadOnly) {
            flag |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        }

        builder.add(Document.COLUMN_DOCUMENT_ID, identifier.toDocumentId());
        builder.add(Document.COLUMN_DISPLAY_NAME, mName);
        builder.add(Document.COLUMN_MIME_TYPE, mimeType); 
        builder.add(
                Document.COLUMN_LAST_MODIFIED,
                mDateModified != null ? mDateModified.getTime() : null);
        builder.add(Document.COLUMN_FLAGS, flag);
        builder.add(Document.COLUMN_SIZE, mSize);
    }

    static String formatTypeToMimeType(int format) {
        // TODO: Add complete list of mime types.
        switch (format) {
            case 0x3001:
                return DocumentsContract.Document.MIME_TYPE_DIR;
            case 0x3009:
                return "audio/mp3";
            case 0x3801:
                return "image/jpeg";
            default:
                return "application/octet-stream";
        }
    }

    static int mimeTypeToFormatType(String mimeType) {
        // TODO: Add complete list of mime types.
        switch (mimeType.toLowerCase()) {
            case Document.MIME_TYPE_DIR:
                return 0x3001;
            case "audio/mp3":
                return 0x3009;
            case "image/jpeg":
                return 0x3801;
            default:
                return 0x3000;  // Undefined object.
        }
    }
}
