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
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class TestMtpManager extends MtpManager {
    public static final int CREATED_DOCUMENT_HANDLE = 1000;

    protected static String pack(int... args) {
        return Arrays.toString(args);
    }

    private final SparseArray<MtpDeviceRecord> mDevices = new SparseArray<>();
    private final Map<String, MtpObjectInfo> mObjectInfos = new HashMap<>();
    private final Map<String, int[]> mObjectHandles = new HashMap<>();
    private final Map<String, byte[]> mThumbnailBytes = new HashMap<>();
    private final Map<String, byte[]> mImportFileBytes = new HashMap<>();
    private final Map<String, Long> mObjectSizeLongs = new HashMap<>();

    TestMtpManager(Context context) {
        super(context);
    }

    void addValidDevice(MtpDeviceRecord device) {
        mDevices.put(device.deviceId, device);
    }

    void setObjectHandles(int deviceId, int storageId, int parentHandle, int[] objectHandles) {
        mObjectHandles.put(pack(deviceId, storageId, parentHandle), objectHandles);
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

    void setObjectSizeLong(int deviceId, int objectHandle, int format, long value) {
        mObjectSizeLongs.put(pack(deviceId, objectHandle, format), value);
    }

    @Override
    MtpDeviceRecord[] getDevices() {
        final MtpDeviceRecord[] result = new MtpDeviceRecord[mDevices.size()];
        for (int i = 0; i < mDevices.size(); i++) {
            final MtpDeviceRecord device = mDevices.valueAt(i);
            if (device.opened) {
                result[i] = device;
            } else {
                result[i] = new MtpDeviceRecord(
                        device.deviceId, device.name, device.deviceKey, device.opened,
                        new MtpRoot[0], null, null);
            }
        }
        return result;
    }

    @Override
    MtpDeviceRecord openDevice(int deviceId) throws IOException {
        final MtpDeviceRecord device = mDevices.get(deviceId);
        if (device == null) {
            throw new IOException();
        }
        final MtpDeviceRecord record = new MtpDeviceRecord(
                device.deviceId, device.name, device.deviceKey, true, device.roots,
                device.operationsSupported, device.eventsSupported);
        mDevices.put(deviceId, record);
        return record;
    }

    @Override
    void closeDevice(int deviceId) throws IOException {
        final MtpDeviceRecord device = mDevices.get(deviceId);
        if (device == null) {
            throw new IOException();
        }
        mDevices.put(
                deviceId,
                new MtpDeviceRecord(device.deviceId, device.name, device.deviceKey, false,
                        device.roots, device.operationsSupported, device.eventsSupported));
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
    int createDocument(int deviceId, MtpObjectInfo objectInfo, ParcelFileDescriptor source)
            throws IOException {
        final String key = pack(deviceId, CREATED_DOCUMENT_HANDLE);
        if (mObjectInfos.containsKey(key)) {
            throw new IOException();
        }
        final MtpObjectInfo newInfo = new MtpObjectInfo.Builder(objectInfo).
                setObjectHandle(CREATED_DOCUMENT_HANDLE).build();
        mObjectInfos.put(key, newInfo);
        if (objectInfo.getFormat() != 0x3001) {
            try (final ParcelFileDescriptor.AutoCloseInputStream inputStream =
                    new ParcelFileDescriptor.AutoCloseInputStream(source)) {
                final byte[] buffer = new byte[objectInfo.getCompressedSize()];
                if (inputStream.read(buffer, 0, objectInfo.getCompressedSize()) !=
                        objectInfo.getCompressedSize()) {
                    throw new IOException();
                }

                mImportFileBytes.put(pack(deviceId, CREATED_DOCUMENT_HANDLE), buffer);
            }
        }
        return CREATED_DOCUMENT_HANDLE;
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
    byte[] getObject(int deviceId, int objectHandle, int expectedSize) throws IOException {
        return mImportFileBytes.get(pack(deviceId, objectHandle));
    }

    @Override
    long getPartialObject(int deviceId, int objectHandle, long offset, long size, byte[] buffer)
            throws IOException {
        final byte[] bytes = mImportFileBytes.get(pack(deviceId, objectHandle));
        int i = 0;
        while (i < size && i + offset < bytes.length) {
            buffer[i] = bytes[(int) (i + offset)];
            i++;
        }
        return i;
    }

    @Override
    long getObjectSizeLong(int deviceId, int objectHandle, int format) throws IOException {
        final String key = pack(deviceId, objectHandle, format);
        if (mObjectSizeLongs.containsKey(key)) {
            return mObjectSizeLongs.get(key);
        } else {
            throw new IOException();
        }
    }
}
