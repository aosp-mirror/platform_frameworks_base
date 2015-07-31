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
    int mDeviceId;
    int mStorageId;
    int mObjectHandle;

    static Identifier createFromRootId(String rootId) {
        final String[] components = rootId.split(":");
        return new Identifier(
                Integer.parseInt(components[0]),
                Integer.parseInt(components[1]));
    }

    static Identifier createFromDocumentId(String documentId) {
        final String[] components = documentId.split(":");
        return new Identifier(
                Integer.parseInt(components[0]),
                Integer.parseInt(components[1]),
                Integer.parseInt(components[2]));
    }


    Identifier(int deviceId, int storageId) {
        this(deviceId, storageId, MtpDocument.DUMMY_HANDLE_FOR_ROOT);
    }

    Identifier(int deviceId, int storageId, int objectHandle) {
        mDeviceId = deviceId;
        mStorageId = storageId;
        mObjectHandle = objectHandle;
    }

    // TODO: Make the ID persistent.
    String toRootId() {
        return String.format("%d:%d", mDeviceId, mStorageId);
    }

    // TODO: Make the ID persistent.
    String toDocumentId() {
        return String.format("%d:%d:%d", mDeviceId, mStorageId, mObjectHandle);
    }
}
