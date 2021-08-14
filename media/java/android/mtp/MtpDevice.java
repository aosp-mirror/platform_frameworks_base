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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import android.os.UserManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import dalvik.system.CloseGuard;

import java.io.IOException;

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

    /** Make sure that MTP device is closed properly */
    @GuardedBy("mLock")
    private CloseGuard mCloseGuard = CloseGuard.get();

    /** Current connection to the {@link #mDevice}, or null if device is not connected */
    @GuardedBy("mLock")
    private UsbDeviceConnection mConnection;

    private final Object mLock = new Object();

    /**
     * MtpClient constructor
     *
     * @param device the {@link android.hardware.usb.UsbDevice} for the MTP or PTP device
     */
    public MtpDevice(@NonNull UsbDevice device) {
        Preconditions.checkNotNull(device);
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
    public boolean open(@NonNull UsbDeviceConnection connection) {
        boolean result = false;

        Context context = connection.getContext();

        synchronized (mLock) {
            if (context != null) {
                UserManager userManager = (UserManager) context
                        .getSystemService(Context.USER_SERVICE);

                if (!userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)) {
                    result = native_open(mDevice.getDeviceName(), connection.getFileDescriptor());
                }
            }

            if (!result) {
                connection.close();
            } else {
                mConnection = connection;
                mCloseGuard.open("close");
            }
        }

        return result;
    }

    /**
     * Closes all resources related to the MtpDevice object.
     * After this is called, the object can not be used until {@link #open} is called again
     * with a new {@link android.hardware.usb.UsbDeviceConnection}.
     */
    public void close() {
        synchronized (mLock) {
            if (mConnection != null) {
                mCloseGuard.close();

                native_close();

                mConnection.close();
                mConnection = null;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
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
    public @NonNull String getDeviceName() {
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
    public @NonNull String toString() {
        return mDevice.getDeviceName();
    }

    /**
     * Returns the {@link MtpDeviceInfo} for this device
     *
     * @return the device info, or null if fetching device info fails
     */
    public @Nullable MtpDeviceInfo getDeviceInfo() {
        return native_get_device_info();
    }

    /**
     * Set device property SESSION_INITIATOR_VERSION_INFO
     *
     * @param propertyStr string value for device property SESSION_INITIATOR_VERSION_INFO
     * @return -1 for error, 0 for success
     *
     * {@hide}
     */
    public int setDevicePropertyInitVersion(@NonNull String propertyStr) {
        return native_set_device_property_init_version(propertyStr);
    }

    /**
     * Returns the list of IDs for all storage units on this device
     * Information about each storage unit can be accessed via {@link #getStorageInfo}.
     *
     * @return the list of storage IDs, or null if fetching storage IDs fails
     */
    public @Nullable int[] getStorageIds() {
        return native_get_storage_ids();
    }

    /**
     * Returns the list of object handles for all objects on the given storage unit,
     * with the given format and parent.
     * Information about each object can be accessed via {@link #getObjectInfo}.
     *
     * @param storageId the storage unit to query
     * @param format the format of the object to return, or zero for all formats
     * @param objectHandle the parent object to query, -1 for the storage root,
     *     or zero for all objects
     * @return the object handles, or null if fetching object handles fails
     */
    public @Nullable int[] getObjectHandles(int storageId, int format, int objectHandle) {
        return native_get_object_handles(storageId, format, objectHandle);
    }

    /**
     * Returns the data for an object as a byte array.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices.
     *
     * @param objectHandle handle of the object to read
     * @param objectSize the size of the object (this should match
     *      {@link MtpObjectInfo#getCompressedSize})
     * @return the object's data, or null if reading fails
     */
    public @Nullable byte[] getObject(int objectHandle, int objectSize) {
        Preconditions.checkArgumentNonnegative(objectSize, "objectSize should not be negative");
        return native_get_object(objectHandle, objectSize);
    }

    /**
     * Obtains object bytes in the specified range and writes it to an array.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices.
     *
     * @param objectHandle handle of the object to read
     * @param offset Start index of reading range. It must be a non-negative value at most
     *     0xffffffff.
     * @param size Size of reading range. It must be a non-negative value at most Integer.MAX_VALUE
     *     or 0xffffffff. If 0xffffffff is specified, the method obtains the full bytes of object.
     * @param buffer Array to write data.
     * @return Size of bytes that are actually read.
     */
    public long getPartialObject(int objectHandle, long offset, long size, @NonNull byte[] buffer)
            throws IOException {
        return native_get_partial_object(objectHandle, offset, size, buffer);
    }

    /**
     * Obtains object bytes in the specified range and writes it to an array.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices.
     *
     * This is a vender-extended operation supported by Android that enables us to pass
     * unsigned 64-bit offset. Check if the MTP device supports the operation by using
     * {@link MtpDeviceInfo#getOperationsSupported()}.
     *
     * @param objectHandle handle of the object to read
     * @param offset Start index of reading range. It must be a non-negative value.
     * @param size Size of reading range. It must be a non-negative value at most Integer.MAX_VALUE.
     * @param buffer Array to write data.
     * @return Size of bytes that are actually read.
     * @see MtpConstants#OPERATION_GET_PARTIAL_OBJECT_64
     */
    public long getPartialObject64(int objectHandle, long offset, long size, @NonNull byte[] buffer)
            throws IOException {
        return native_get_partial_object_64(objectHandle, offset, size, buffer);
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
    public @Nullable byte[] getThumbnail(int objectHandle) {
        return native_get_thumbnail(objectHandle);
    }

    /**
     * Retrieves the {@link MtpStorageInfo} for a storage unit.
     *
     * @param storageId the ID of the storage unit
     * @return the MtpStorageInfo, or null if fetching storage info fails
     */
    public @Nullable MtpStorageInfo getStorageInfo(int storageId) {
        return native_get_storage_info(storageId);
    }

    /**
     * Retrieves the {@link MtpObjectInfo} for an object.
     *
     * @param objectHandle the handle of the object
     * @return the MtpObjectInfo, or null if fetching object info fails
     */
    public @Nullable MtpObjectInfo getObjectInfo(int objectHandle) {
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
    public boolean importFile(int objectHandle, @NonNull String destPath) {
        return native_import_file(objectHandle, destPath);
    }

    /**
     * Copies the data for an object to a file descriptor.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices. The file descriptor is not closed
     * on completion, and must be done by the caller.
     *
     * @param objectHandle handle of the object to read
     * @param descriptor file descriptor to write the data to for the file transfer.
     * @return true if the file transfer succeeds
     */
    public boolean importFile(int objectHandle, @NonNull ParcelFileDescriptor descriptor) {
        return native_import_file(objectHandle, descriptor.getFd());
    }

    /**
     * Copies the data for an object from a file descriptor.
     * This call may block for an arbitrary amount of time depending on the size
     * of the data and speed of the devices. The file descriptor is not closed
     * on completion, and must be done by the caller.
     *
     * @param objectHandle handle of the target file
     * @param size size of the file in bytes
     * @param descriptor file descriptor to read the data from.
     * @return true if the file transfer succeeds
     */
    public boolean sendObject(
            int objectHandle, long size, @NonNull ParcelFileDescriptor descriptor) {
        return native_send_object(objectHandle, size, descriptor.getFd());
    }

    /**
     * Uploads an object metadata for a new entry. The {@link MtpObjectInfo} can be
     * created with the {@link MtpObjectInfo.Builder} class.
     *
     * The returned {@link MtpObjectInfo} has the new object handle field filled in.
     *
     * @param info metadata of the entry
     * @return object info of the created entry, or null if sending object info fails
     */
    public @Nullable MtpObjectInfo sendObjectInfo(@NonNull MtpObjectInfo info) {
        return native_send_object_info(info);
    }

    /**
     * Reads an event from the device. It blocks the current thread until it gets an event.
     * It throws OperationCanceledException if it is cancelled by signal.
     *
     * @param signal signal for cancellation
     * @return obtained event
     * @throws IOException
     */
    public @NonNull MtpEvent readEvent(@Nullable CancellationSignal signal) throws IOException {
        final int handle = native_submit_event_request();
        Preconditions.checkState(handle >= 0, "Other thread is reading an event.");

        if (signal != null) {
            signal.setOnCancelListener(new CancellationSignal.OnCancelListener() {
                @Override
                public void onCancel() {
                    native_discard_event_request(handle);
                }
            });
        }

        try {
            return native_reap_event_request(handle);
        } finally {
            if (signal != null) {
                signal.setOnCancelListener(null);
            }
        }
    }

    /**
     * Returns object size in 64-bit integer.
     *
     * Though MtpObjectInfo#getCompressedSize returns the object size in 32-bit unsigned integer,
     * this method returns the object size in 64-bit integer from the object property. Thus it can
     * fetch 4GB+ object size correctly. If the device does not support objectSize property, it
     * throws IOException.
     * @hide
     */
    public long getObjectSizeLong(int handle, int format) throws IOException {
        return native_get_object_size_long(handle, format);
    }

    // used by the JNI code
    private long mNativeContext;

    private native boolean native_open(String deviceName, int fd);
    private native void native_close();
    private native MtpDeviceInfo native_get_device_info();
    private native int native_set_device_property_init_version(String propertyStr);
    private native int[] native_get_storage_ids();
    private native MtpStorageInfo native_get_storage_info(int storageId);
    private native int[] native_get_object_handles(int storageId, int format, int objectHandle);
    private native MtpObjectInfo native_get_object_info(int objectHandle);
    private native byte[] native_get_object(int objectHandle, long objectSize);
    private native long native_get_partial_object(
            int objectHandle, long offset, long objectSize, byte[] buffer) throws IOException;
    private native int native_get_partial_object_64(
            int objectHandle, long offset, long objectSize, byte[] buffer) throws IOException;
    private native byte[] native_get_thumbnail(int objectHandle);
    private native boolean native_delete_object(int objectHandle);
    private native int native_get_parent(int objectHandle);
    private native int native_get_storage_id(int objectHandle);
    private native boolean native_import_file(int objectHandle, String destPath);
    private native boolean native_import_file(int objectHandle, int fd);
    private native boolean native_send_object(int objectHandle, long size, int fd);
    private native MtpObjectInfo native_send_object_info(MtpObjectInfo info);
    private native int native_submit_event_request() throws IOException;
    private native MtpEvent native_reap_event_request(int handle) throws IOException;
    private native void native_discard_event_request(int handle);
    private native long native_get_object_size_long(int handle, int format) throws IOException;
}
