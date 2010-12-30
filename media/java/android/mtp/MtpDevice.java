/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.mtp;

import android.hardware.UsbDevice;
import android.hardware.UsbManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * This class represents an MTP device connected on the USB host bus.
 *
 * {@hide}
 */
public final class MtpDevice {

    private static final String TAG = "MtpDevice";

    private final UsbDevice mDevice;

    static {
        System.loadLibrary("media_jni");
    }

    public MtpDevice(UsbDevice device) {
        mDevice = device;
    }

    public boolean open(UsbManager manager) {
        if (manager.openDevice(mDevice)) {
            return native_open(mDevice.getDeviceName(), mDevice.getFileDescriptor());
        } else {
            return false;
        }
    }

    public void close() {
        Log.d(TAG, "close");
        native_close();
    }

    @Override
    protected void finalize() throws Throwable {
        Log.d(TAG, "finalize");
        try {
            native_close();
        } finally {
            super.finalize();
        }
    }

    public String getDeviceName() {
        return mDevice.getDeviceName();
    }

    public int getDeviceId() {
        return mDevice.getDeviceId();
    }

    @Override
    public String toString() {
        return mDevice.getDeviceName();
    }

    public MtpDeviceInfo getDeviceInfo() {
        return native_get_device_info();
    }

    public int[] getStorageIds() {
        return native_get_storage_ids();
    }

    public int[] getObjectHandles(int storageId, int format, int objectHandle) {
        return native_get_object_handles(storageId, format, objectHandle);
    }

    public byte[] getObject(int objectHandle, int objectSize) {
        return native_get_object(objectHandle, objectSize);
    }

    public byte[] getThumbnail(int objectHandle) {
        return native_get_thumbnail(objectHandle);
    }

    public MtpStorageInfo getStorageInfo(int storageId) {
        return native_get_storage_info(storageId);
    }

    public MtpObjectInfo getObjectInfo(int objectHandle) {
        return native_get_object_info(objectHandle);
    }

    public boolean deleteObject(int objectHandle) {
        return native_delete_object(objectHandle);
    }

    public long getParent(int objectHandle) {
        return native_get_parent(objectHandle);
    }

    public long getStorageID(int objectHandle) {
        return native_get_storage_id(objectHandle);
    }

    // Reads a file from device to host to the specified destination.
    // Returns true if the transfer succeeds.
    public boolean importFile(int objectHandle, String destPath) {
        return native_import_file(objectHandle, destPath);
    }

    // used by the JNI code
    private int mNativeContext;

    private native boolean native_open(String deviceName, int fd);
    private native void native_close();
    private native MtpDeviceInfo native_get_device_info();
    private native int[] native_get_storage_ids();
    private native MtpStorageInfo native_get_storage_info(int storageId);
    private native int[] native_get_object_handles(int storageId, int format, int objectHandle);
    private native MtpObjectInfo native_get_object_info(int objectHandle);
    private native byte[] native_get_object(int objectHandle, int objectSize);
    private native byte[] native_get_thumbnail(int objectHandle);
    private native boolean native_delete_object(int objectHandle);
    private native long native_get_parent(int objectHandle);
    private native long native_get_storage_id(int objectHandle);
    private native boolean native_import_file(int objectHandle, String destPath);
}
