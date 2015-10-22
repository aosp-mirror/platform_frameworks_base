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

import android.content.res.Resources;
import android.database.MatrixCursor;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

final class CursorHelper {
    static final int DUMMY_HANDLE_FOR_ROOT = 0;

    private CursorHelper() {
    }

    static void addToCursor(Resources resources, MtpRoot root, MatrixCursor.RowBuilder builder) {
        final Identifier identifier = new Identifier(
                root.mDeviceId, root.mStorageId, DUMMY_HANDLE_FOR_ROOT);
        builder.add(Document.COLUMN_DOCUMENT_ID, identifier.toDocumentId());
        builder.add(Document.COLUMN_DISPLAY_NAME, root.getRootName(resources));
        builder.add(Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        builder.add(Document.COLUMN_LAST_MODIFIED, null);
        builder.add(Document.COLUMN_FLAGS, 0);
        builder.add(Document.COLUMN_SIZE,
                (int) Math.min(root.mMaxCapacity - root.mFreeSpace, Integer.MAX_VALUE));
    }

    static void addToCursor(MtpObjectInfo objectInfo, Identifier rootIdentifier,
            MatrixCursor.RowBuilder builder) {
        final Identifier identifier = new Identifier(
                rootIdentifier.mDeviceId, rootIdentifier.mStorageId, objectInfo.getObjectHandle());
        final String mimeType = formatTypeToMimeType(objectInfo.getFormat());

        int flag = 0;
        if (objectInfo.getProtectionStatus() == 0) {
            flag |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                flag |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        }
        if (objectInfo.getThumbCompressedSize() > 0) {
            flag |= DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        builder.add(Document.COLUMN_DOCUMENT_ID, identifier.toDocumentId());
        builder.add(Document.COLUMN_DISPLAY_NAME, objectInfo.getName());
        builder.add(Document.COLUMN_MIME_TYPE, mimeType);
        builder.add(
                Document.COLUMN_LAST_MODIFIED,
                objectInfo.getDateModified() != 0 ? objectInfo.getDateModified() : null);
        builder.add(Document.COLUMN_FLAGS, flag);
        builder.add(Document.COLUMN_SIZE, objectInfo.getCompressedSize());
    }

    static String formatTypeToMimeType(int format) {
        if (format == MtpConstants.FORMAT_ASSOCIATION) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        } else {
            return MediaFile.getMimeTypeForFormatCode(format);
        }
    }

    static int mimeTypeToFormatType(String fileName, String mimeType) {
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            return MtpConstants.FORMAT_ASSOCIATION;
        } else {
            return MediaFile.getFormatCode(fileName, mimeType);
        }
    }
}
