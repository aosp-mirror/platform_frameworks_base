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

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestMtpManager extends MtpManager {
    protected static String pack(int... args) {
        return Arrays.toString(args);
    }

    private final Set<Integer> mValidDevices = new HashSet<>();
    private final Set<Integer> mOpenedDevices = new TreeSet<>();
    private final Map<Integer, MtpRoot[]> mRoots = new HashMap<>();
    private final Map<String, MtpDocument> mDocuments = new HashMap<>();
    private final Map<String, int[]> mObjectHandles = new HashMap<>();
    private final Map<String, byte[]> mThumbnailBytes = new HashMap<>();
    private final Map<String, Integer> mParents = new HashMap<>();
    private final Map<String, byte[]> mImportFileBytes = new HashMap<>();

    TestMtpManager(Context context) {
        super(context);
    }

    void addValidDevice(int deviceId) {
        mValidDevices.add(deviceId);
    }

    void setObjectHandles(int deviceId, int storageId, int objectHandle, int[] documents) {
        mObjectHandles.put(pack(deviceId, storageId, objectHandle), documents);
    }

    void setRoots(int deviceId, MtpRoot[] roots) {
        mRoots.put(deviceId, roots);
    }

    void setDocument(int deviceId, int objectHandle, MtpDocument document) {
        mDocuments.put(pack(deviceId, objectHandle), document);
    }

    void setImportFileBytes(int deviceId, int objectHandle, byte[] bytes) {
        mImportFileBytes.put(pack(deviceId, objectHandle), bytes);
    }

    void setThumbnail(int deviceId, int objectHandle, byte[] bytes) {
        mThumbnailBytes.put(pack(deviceId, objectHandle), bytes);
    }

    void setParent(int deviceId, int objectHandle, int parentObjectHandle) {
        mParents.put(pack(deviceId, objectHandle), parentObjectHandle);
    }

    @Override
    void openDevice(int deviceId) throws IOException {
        if (!mValidDevices.contains(deviceId) || mOpenedDevices.contains(deviceId)) {
            throw new IOException();
        }
        mOpenedDevices.add(deviceId);
    }

    @Override
    void closeDevice(int deviceId) throws IOException {
        if (!mValidDevices.contains(deviceId) || !mOpenedDevices.contains(deviceId)) {
            throw new IOException();
        }
        mOpenedDevices.remove(deviceId);
    }

    @Override
    MtpRoot[] getRoots(int deviceId) throws IOException {
        if (mRoots.containsKey(deviceId)) {
            return mRoots.get(deviceId);
        } else {
            throw new IOException("getRoots error");
        }
    }

    @Override
    MtpDocument getDocument(int deviceId, int objectHandle) throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (mDocuments.containsKey(key)) {
            return mDocuments.get(key);
        } else {
            throw new IOException("getDocument error: " + key);
        }
    }

    @Override
    int[] getObjectHandles(int deviceId, int storageId, int parentObjectHandle) throws IOException {
        final String key = pack(deviceId, storageId, parentObjectHandle);
        if (mObjectHandles.containsKey(key)) {
            return mObjectHandles.get(key);
        } else {
            throw new IOException("getObjectHandles error: " + key);
        }
    }

    @Override
    void importFile(int deviceId, int storageId, ParcelFileDescriptor target) throws IOException {
        final String key = pack(deviceId, storageId);
        if (mImportFileBytes.containsKey(key)) {
            try (final ParcelFileDescriptor.AutoCloseOutputStream outputStream =
                    new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
                outputStream.write(mImportFileBytes.get(key));
            }
        } else {
            throw new IOException("importFile error: " + key);
        }
    }

    @Override
    byte[] getThumbnail(int deviceId, int objectHandle) throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (mThumbnailBytes.containsKey(key)) {
            return mThumbnailBytes.get(key);
        } else {
            throw new IOException("getThumbnail error: " + key);
        }
    }

    @Override
    void deleteDocument(int deviceId, int objectHandle) throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (mDocuments.containsKey(key)) {
            mDocuments.remove(key);
        } else {
            throw new IOException();
        }
    }

    @Override
    synchronized int getParent(int deviceId, int objectHandle) throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (mParents.containsKey(key)) {
            return mParents.get(key);
        } else {
            throw new IOException();
        }
    }

    @Override
    int[] getOpenedDeviceIds() {
        int i = 0;
        final int[] result = new int[mOpenedDevices.size()];
        for (int deviceId : mOpenedDevices) {
            result[i++] = deviceId;
        }
        return result;
    }
}
