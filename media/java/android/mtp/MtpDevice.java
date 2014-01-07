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

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

/**
 * This class represents an MTP or PTP device connected on the USB host bus. An application can
 * instantiate an object of this type, by referencing an attached {@link
 * android.hardware.usb.UsbDevice} and then use methods in this class to get information about the
 * device and objects stored on it, as well as open the connection and transfer data.
 */
public final class MtpDevice {

    private static final String TAG = "MtpDevice";

    private final UsbDevice mDevice;

    static {
        System.loadLibrary("media_jni");
    }

    /**
     * MtpClient constructor
     *
     * @param device the {@link android.hardware.usb.UsbDevice} for the MTP or PTP device
     */
    public MtpDevice(UsbDevice device) {
        mDevice = device;
    }

    /**
     * Opens the MTP device.  Once the device is open it takes ownership of the
     * {@link android.hardware.usb.UsbDeviceConnection}.  
     * The connection will be closed when you call {@link #close()}
     * The connection will also be closed if this method fails.
     *
     * @param connection an open {@link android.hardware.usb.UsbDeviceConnection} for the device
     * @return true if the device was successfully opened.
     */
    public boolean open(UsbDeviceConnection connection) {
        boolean result = native_open(mDevice.getDeviceName(), connection.getFileDescriptor());
        if (!result) {
            connection.close();
        }
        return result;
    }

    /**
     * Closes all resources related to the MtpDevice object.
     * After this is called, the object can not be used until {@link #open} is called again
     * with a new {@link android.hardware.usb.UsbDeviceConnection}.
     */
    public void close() {
        native_close();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            native_close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Returns the name of the USB device
     * This returns the same value as {@link android.hardware.usb.UsbDevice#getDeviceName}
     * for the device's {@link android.hardware.usb.UsbDevice}
     *
     * @return the device name
     */
    public String getDeviceName() {
        return mDevice.getDeviceName();
    }

    /**
     * Returns the USB ID of the USB device.
     * This returns the same value as {@link android.hardware.usb.UsbDevice#getDeviceId}
     * for the device's {@link android.hardware.usb.UsbDevice}
     *
     * @return the device ID
     */
    public int getDeviceId() {
        return mDevice.getDeviceId();
    }

    @Override
    public String toString() {
        return mDevice.getDeviceName();
    }

    /**
     * Returns the {@link MtpDeviceInfo} for this device
     *
     * @return the device info
     */
    public MtpDeviceInfo getDeviceInfo() {
        return native_get_device_info();
    }

    /**
     * Returns the list of IDs for all storage units on this device
     * Information about each storage unit can be accessed via {@link #getStorageInfo}.
     *
     * @return the list of storage IDs
     */
    public int[] getStorageIds() {
        return native_get_storage_ids();
    }

    /**
     * Returns the list of object handles for all objects on the given storage unit,
     * with the given format and parent.
     * Information about each object can be accessed via {@link #getObjectInfo}.
     *
     * @param storageId the storage unit to query
     * @param format the format of the object to return, or zero for all formats
     * @param objectHandle the parent object to query, or zero for the storage root
     * @return the object handles
     */
    public int[] getObjectHandles(int storageId, int format, int objectHandle) {
        return native_get_object_handles(storageId, format, objectHandle);
    }

    /**
     * Returns the data for an object as a byte array.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices.
     *
     * @param objectHandle handle of the object to read
     * @param objectSize the size of the object (this should match
     *      {@link MtpObjectInfo#getCompressedSize}
     * @return the object's data, or null if reading fails
     */
    public byte[] getObject(int objectHandle, int objectSize) {
        return native_get_object(objectHandle, objectSize);
    }

    /**
     * Returns the thumbnail data for an object as a byte array.
     * The size and format of the thumbnail data can be determined via
     * {@link MtpObjectInfo#getThumbCompressedSize} and
     * {@link MtpObjectInfo#getThumbFormat}.
     * For typical devices the format is JPEG.
     *
     * @param objectHandle handle of the object to read
     * @return the object's thumbnail, or null if reading fails
     */
    public byte[] getThumbnail(int objectHandle) {
        return native_get_thumbnail(objectHandle);
    }

    /**
     * Retrieves the {@link MtpStorageInfo} for a storage unit.
     *
     * @param storageId the ID of the storage unit
     * @return the MtpStorageInfo
     */
    public MtpStorageInfo getStorageInfo(int storageId) {
        return native_get_storage_info(storageId);
    }

    /**
     * Retrieves the {@link MtpObjectInfo} for an object.
     *
     * @param objectHandle the handle of the object
     * @return the MtpObjectInfo
     */
    public MtpObjectInfo getObjectInfo(int objectHandle) {
        return native_get_object_info(objectHandle);
    }

    /**
     * Deletes an object on the device.  This call may block, since
     * deleting a directory containing many files may take a long time
     * on some devices.
     *
     * @param objectHandle handle of the object to delete
     * @return true if the deletion succeeds
     */
    public boolean deleteObject(int objectHandle) {
        return native_delete_object(objectHandle);
    }

    /**
     * Retrieves the object handle for the parent of an object on the device.
     *
     * @param objectHandle handle of the object to query
     * @return the parent's handle, or zero if it is in the root of the storage
     */
    public long getParent(int objectHandle) {
        return native_get_parent(objectHandle);
    }

    /**
     * Retrieves the ID of the storage unit containing the given object on the device.
     *
     * @param objectHandle handle of the object to query
     * @return the object's storage unit ID
     */
    public long getStorageId(int objectHandle) {
        return native_get_storage_id(objectHandle);
    }

    /**
     * Copies the data for an object to a file in external storage.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices.
     *
     * @param objectHandle handle of the object to read
     * @param destPath path to destination for the file transfer.
     *      This path should be in the external storage as defined by
     *      {@link android.os.Environment#getExternalStorageDirectory}
     * @return true if the file transfer succeeds
     */
    public boolean importFile(int objectHandle, String destPath) {
        return native_import_file(objectHandle, destPath);
    }

    // used by the JNI code
    private long mNativeContext;

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
