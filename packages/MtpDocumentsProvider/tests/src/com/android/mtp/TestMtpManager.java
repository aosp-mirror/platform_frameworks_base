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
import android.mtp.MtpObjectInfo;
import android.mtp.MtpObjectInfo.Builder;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestMtpManager extends MtpManager {
    public static final int CREATED_DOCUMENT_HANDLE = 1000;

    protected static String pack(int... args) {
        return Arrays.toString(args);
    }

    private final Set<Integer> mValidDevices = new HashSet<>();
    private final Set<Integer> mOpenedDevices = new TreeSet<>();
    private final Map<Integer, MtpRoot[]> mRoots = new HashMap<>();
    private final Map<String, MtpObjectInfo> mObjectInfos = new HashMap<>();
    private final Map<String, int[]> mObjectHandles = new HashMap<>();
    private final Map<String, byte[]> mThumbnailBytes = new HashMap<>();
    private final Map<String, byte[]> mImportFileBytes = new HashMap<>();

    TestMtpManager(Context context) {
        super(context);
    }

    void addValidDevice(int deviceId) {
        mValidDevices.add(deviceId);
    }

    void setObjectHandles(int deviceId, int storageId, int parentHandle, int[] objectHandles) {
        mObjectHandles.put(pack(deviceId, storageId, parentHandle), objectHandles);
    }

    void setRoots(int deviceId, MtpRoot[] roots) {
        mRoots.put(deviceId, roots);
    }

    void setObjectInfo(int deviceId, MtpObjectInfo objectInfo) {
        mObjectInfos.put(pack(deviceId, objectInfo.getObjectHandle()), objectInfo);
    }

    void setImportFileBytes(int deviceId, int objectHandle, byte[] bytes) {
        mImportFileBytes.put(pack(deviceId, objectHandle), bytes);
    }

    byte[] getImportFileBytes(int deviceId, int objectHandle) {
        return mImportFileBytes.get(pack(deviceId, objectHandle));
    }

    void setThumbnail(int deviceId, int objectHandle, byte[] bytes) {
        mThumbnailBytes.put(pack(deviceId, objectHandle), bytes);
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
    MtpObjectInfo getObjectInfo(int deviceId, int objectHandle) throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (mObjectInfos.containsKey(key)) {
            return mObjectInfos.get(key);
        } else {
            throw new IOException("getObjectInfo error: " + key);
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
    void importFile(int deviceId, int objectHandle, ParcelFileDescriptor target)
            throws IOException {
        final String key = pack(deviceId, objectHandle);
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
    int createDocument(int deviceId, MtpObjectInfo objectInfo) throws IOException {
        final String key = pack(deviceId, CREATED_DOCUMENT_HANDLE);
        if (!mObjectInfos.containsKey(key)) {
            mObjectInfos.put(key, objectInfo);
        } else {
            throw new IOException();
        }
        return CREATED_DOCUMENT_HANDLE;
    }

    @Override
    void sendObject(int deviceId, int objectHandle, int size, ParcelFileDescriptor source)
            throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (!mObjectInfos.containsKey(key)) {
            throw new IOException();
        }

        ParcelFileDescriptor.AutoCloseInputStream inputStream =
                new ParcelFileDescriptor.AutoCloseInputStream(source);
        byte[] buffer = new byte[size];
        if (inputStream.read(buffer, 0, size) != size) {
            throw new IOException();
        }

        mImportFileBytes.put(pack(deviceId, objectHandle), buffer);
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
        if (mObjectInfos.containsKey(key)) {
            mObjectInfos.remove(key);
        } else {
            throw new IOException();
        }
    }

    @Override
    int getParent(int deviceId, int objectHandle) throws IOException {
        final String key = pack(deviceId, objectHandle);
        if (mObjectInfos.containsKey(key)) {
            return mObjectInfos.get(key).getParent();
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
