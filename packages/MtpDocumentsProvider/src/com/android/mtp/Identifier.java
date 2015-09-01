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

/**
 * Static utilities for ID.
 */
class Identifier {
    final int mDeviceId;
    final int mStorageId;
    final int mObjectHandle;

    static Identifier createFromRootId(String rootId) {
        final String[] components = rootId.split("_");
        return new Identifier(
                Integer.parseInt(components[0]),
                Integer.parseInt(components[1]));
    }

    static Identifier createFromDocumentId(String documentId) {
        final String[] components = documentId.split("_");
        return new Identifier(
                Integer.parseInt(components[0]),
                Integer.parseInt(components[1]),
                Integer.parseInt(components[2]));
    }


    Identifier(int deviceId, int storageId) {
        this(deviceId, storageId, CursorHelper.DUMMY_HANDLE_FOR_ROOT);
    }

    Identifier(int deviceId, int storageId, int objectHandle) {
        mDeviceId = deviceId;
        mStorageId = storageId;
        mObjectHandle = objectHandle;
    }

    // TODO: Make the ID persistent.
    String toRootId() {
        return String.format("%d_%d", mDeviceId, mStorageId);
    }

    // TODO: Make the ID persistent.
    String toDocumentId() {
        return String.format("%d_%d_%d", mDeviceId, mStorageId, mObjectHandle);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Identifier))
            return false;
        final Identifier other = (Identifier)obj;
        return mDeviceId == other.mDeviceId && mStorageId == other.mStorageId &&
                mObjectHandle == other.mObjectHandle;
    }

    @Override
    public int hashCode() {
        return (mDeviceId << 16) ^ (mStorageId << 8) ^ mObjectHandle;
    }
}
