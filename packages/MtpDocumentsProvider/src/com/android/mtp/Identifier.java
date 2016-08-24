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

import java.util.Objects;
import static com.android.mtp.MtpDatabaseConstants.DocumentType;

/**
 * Static utilities for ID.
 */
class Identifier {
    final int mDeviceId;
    final int mStorageId;
    final int mObjectHandle;
    final String mDocumentId;
    final @DocumentType int mDocumentType;

    Identifier(int deviceId, int storageId, int objectHandle, String documentId,
            @DocumentType int documentType) {
        mDeviceId = deviceId;
        mStorageId = storageId;
        mObjectHandle = objectHandle;
        mDocumentId = documentId;
        mDocumentType = documentType;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Identifier))
            return false;
        final Identifier other = (Identifier) obj;
        return mDeviceId == other.mDeviceId && mStorageId == other.mStorageId &&
                mObjectHandle == other.mObjectHandle && mDocumentId.equals(other.mDocumentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceId, mStorageId, mObjectHandle, mDocumentId);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("Identifier { ");

        builder.append("mDeviceId: ");
        builder.append(mDeviceId);
        builder.append(", ");

        builder.append("mStorageId: ");
        builder.append(mStorageId);
        builder.append(", ");

        builder.append("mObjectHandle: ");
        builder.append(mObjectHandle);
        builder.append(", ");

        builder.append("mDocumentId: ");
        builder.append(mDocumentId);
        builder.append(", ");

        builder.append("mDocumentType: ");
        builder.append(mDocumentType);
        builder.append(" }");
        return builder.toString();
    }
}
