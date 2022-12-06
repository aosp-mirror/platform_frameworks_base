/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.hardware.usb;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.FileDescriptor;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/**
 * This class is used for sending and receiving data and control messages to a USB device.
 * Instances of this class are created by {@link UsbManager#openDevice}.
 */
public class UsbDeviceConnection {

    private static final String TAG = "UsbDeviceConnection";

    private final UsbDevice mDevice;

    private Context mContext;

    // used by the JNI code
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mNativeContext;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final Object mLock = new Object();

    /**
     * UsbDevice should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbDeviceConnection(UsbDevice device) {
        mDevice = device;
    }

    /* package */ boolean open(String name, ParcelFileDescriptor pfd, @NonNull Context context) {
        mContext = context.getApplicationContext();

        synchronized (mLock) {
            boolean wasOpened = native_open(name, pfd.getFileDescriptor());

            if (wasOpened) {
                mCloseGuard.open("UsbDeviceConnection.close");
            }

            return wasOpened;
        }
    }

    /***
     * @return If this connection is currently open and usable.
     */
    boolean isOpen() {
        return mNativeContext != 0;
    }

    /**
     * @return The application context the connection was created for.
     *
     * @hide
     */
    public @Nullable Context getContext() {
        return mContext;
    }

    /**
     * Cancel a request which relates to this connection.
     *
     * @return true if the request was successfully cancelled.
     */
    /* package */ boolean cancelRequest(UsbRequest request) {
        synchronized (mLock) {
            if (!isOpen()) {
                return false;
            }

            return request.cancelIfOpen();
        }
    }

    /**
     * This is meant to be called by UsbRequest's queue() in order to synchronize on
     * UsbDeviceConnection's mLock to prevent the connection being closed while queueing.
     */
    /* package */ boolean queueRequest(UsbRequest request, ByteBuffer buffer, int length) {
        synchronized (mLock) {
            if (!isOpen()) {
                return false;
            }

            return request.queueIfConnectionOpen(buffer, length);
        }
    }

    /**
     * This is meant to be called by UsbRequest's queue() in order to synchronize on
     * UsbDeviceConnection's mLock to prevent the connection being closed while queueing.
     */
    /* package */ boolean queueRequest(UsbRequest request, @Nullable ByteBuffer buffer) {
        synchronized (mLock) {
            if (!isOpen()) {
                return false;
            }

            return request.queueIfConnectionOpen(buffer);
        }
    }

    /**
     * Releases all system resources related to the device.
     * Once the object is closed it cannot be used again.
     * The client must call {@link UsbManager#openDevice} again
     * to retrieve a new instance to reestablish communication with the device.
     */
    public void close() {
        synchronized (mLock) {
            if (isOpen()) {
                native_close();
                mCloseGuard.close();
            }
        }
    }

    /**
     * Returns the native file descriptor for the device, or
     * -1 if the device is not opened.
     * This is intended for passing to native code to access the device.
     *
     * @return the native file descriptor
     */
    public int getFileDescriptor() {
        return native_get_fd();
    }

    /**
     * Returns the raw USB descriptors for the device.
     * This can be used to access descriptors not supported directly
     * via the higher level APIs.
     *
     * @return raw USB descriptors
     */
    public byte[] getRawDescriptors() {
        return native_get_desc();
    }

    /**
     * Claims exclusive access to a {@link android.hardware.usb.UsbInterface}.
     * This must be done before sending or receiving data on any
     * {@link android.hardware.usb.UsbEndpoint}s belonging to the interface.
     *
     * @param intf the interface to claim
     * @param force true to disconnect kernel driver if necessary
     * @return true if the interface was successfully claimed
     */
    public boolean claimInterface(UsbInterface intf, boolean force) {
        return native_claim_interface(intf.getId(), force);
    }

    /**
     * Releases exclusive access to a {@link android.hardware.usb.UsbInterface}.
     *
     * @return true if the interface was successfully released
     */
    public boolean releaseInterface(UsbInterface intf) {
        return native_release_interface(intf.getId());
    }

    /**
     * Sets the current {@link android.hardware.usb.UsbInterface}.
     * Used to select between two interfaces with the same ID but different alternate setting.
     *
     * @return true if the interface was successfully selected
     */
    public boolean setInterface(UsbInterface intf) {
        return native_set_interface(intf.getId(), intf.getAlternateSetting());
    }

    /**
     * Sets the device's current {@link android.hardware.usb.UsbConfiguration}.
     *
     * @return true if the configuration was successfully set
     */
    public boolean setConfiguration(UsbConfiguration configuration) {
        return native_set_configuration(configuration.getId());
    }

    /**
     * Performs a control transaction on endpoint zero for this device.
     * The direction of the transfer is determined by the request type.
     * If requestType & {@link UsbConstants#USB_ENDPOINT_DIR_MASK} is
     * {@link UsbConstants#USB_DIR_OUT}, then the transfer is a write,
     * and if it is {@link UsbConstants#USB_DIR_IN}, then the transfer
     * is a read.
     * <p>
     * This method transfers data starting from index 0 in the buffer.
     * To specify a different offset, use
     * {@link #controlTransfer(int, int, int, int, byte[], int, int, int)}.
     * </p>
     *
     * @param requestType request type for this transaction
     * @param request request ID for this transaction
     * @param value value field for this transaction
     * @param index index field for this transaction
     * @param buffer buffer for data portion of transaction,
     * or null if no data needs to be sent or received
     * @param length the length of the data to send or receive
     * @param timeout in milliseconds
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int controlTransfer(int requestType, int request, int value,
            int index, byte[] buffer, int length, int timeout) {
        return controlTransfer(requestType, request, value, index, buffer, 0, length, timeout);
    }

    /**
     * Performs a control transaction on endpoint zero for this device.
     * The direction of the transfer is determined by the request type.
     * If requestType & {@link UsbConstants#USB_ENDPOINT_DIR_MASK} is
     * {@link UsbConstants#USB_DIR_OUT}, then the transfer is a write,
     * and if it is {@link UsbConstants#USB_DIR_IN}, then the transfer
     * is a read.
     *
     * @param requestType request type for this transaction
     * @param request request ID for this transaction
     * @param value value field for this transaction
     * @param index index field for this transaction
     * @param buffer buffer for data portion of transaction,
     * or null if no data needs to be sent or received
     * @param offset the index of the first byte in the buffer to send or receive
     * @param length the length of the data to send or receive
     * @param timeout in milliseconds
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int controlTransfer(int requestType, int request, int value, int index,
            byte[] buffer, int offset, int length, int timeout) {
        checkBounds(buffer, offset, length);
        return native_control_request(requestType, request, value, index,
                buffer, offset, length, timeout);
    }

    /**
     * Performs a bulk transaction on the given endpoint.
     * The direction of the transfer is determined by the direction of the endpoint.
     * <p>
     * This method transfers data starting from index 0 in the buffer.
     * To specify a different offset, use
     * {@link #bulkTransfer(UsbEndpoint, byte[], int, int, int)}.
     * </p>
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer buffer for data to send or receive; can be {@code null} to wait for next
     *               transaction without reading data
     * @param length the length of the data to send or receive. Before
     *               {@value Build.VERSION_CODES#P}, a value larger than 16384 bytes
     *               would be truncated down to 16384. In API {@value Build.VERSION_CODES#P}
     *               and after, any value of length is valid.
     * @param timeout in milliseconds, 0 is infinite
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int bulkTransfer(UsbEndpoint endpoint,
            byte[] buffer, int length, int timeout) {
        return bulkTransfer(endpoint, buffer, 0, length, timeout);
    }

    /**
     * Performs a bulk transaction on the given endpoint.
     * The direction of the transfer is determined by the direction of the endpoint.
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer buffer for data to send or receive
     * @param offset the index of the first byte in the buffer to send or receive
     * @param length the length of the data to send or receive. Before
     *               {@value Build.VERSION_CODES#P}, a value larger than 16384 bytes
     *               would be truncated down to 16384. In API {@value Build.VERSION_CODES#P}
     *               and after, any value of length is valid.
     * @param timeout in milliseconds, 0 is infinite
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int bulkTransfer(UsbEndpoint endpoint,
            byte[] buffer, int offset, int length, int timeout) {
        checkBounds(buffer, offset, length);
        if (mContext.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.P
                && length > UsbRequest.MAX_USBFS_BUFFER_SIZE) {
            length = UsbRequest.MAX_USBFS_BUFFER_SIZE;
        }
        return native_bulk_request(endpoint.getAddress(), buffer, offset, length, timeout);
    }

    /**
     * Reset USB port for the connected device.
     *
     * @return true if reset succeeds.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("RequiresPermission")
    public boolean resetDevice() {
        return native_reset_device();
    }

    /**
     * Waits for the result of a {@link android.hardware.usb.UsbRequest#queue} operation
     * <p>Note that this may return requests queued on multiple
     * {@link android.hardware.usb.UsbEndpoint}s. When multiple endpoints are in use,
     * {@link android.hardware.usb.UsbRequest#getEndpoint} and {@link
     * android.hardware.usb.UsbRequest#getClientData} can be useful in determining how to process
     * the result of this function.</p>
     *
     * @return a completed USB request, or null if an error occurred
     *
     * @throws IllegalArgumentException Before API {@value Build.VERSION_CODES#O}: if the number of
     *                                  bytes read or written is more than the limit of the
     *                                  request's buffer. The number of bytes is determined by the
     *                                  {@code length} parameter of
     *                                  {@link UsbRequest#queue(ByteBuffer, int)}
     * @throws BufferOverflowException In API {@value Build.VERSION_CODES#O} and after: if the
     *                                 number of bytes read or written is more than the limit of the
     *                                 request's buffer. The number of bytes is determined by the
     *                                 {@code length} parameter of
     *                                 {@link UsbRequest#queue(ByteBuffer, int)}
     */
    public UsbRequest requestWait() {
        UsbRequest request = null;
        try {
            // -1 is special value indicating infinite wait
            request = native_request_wait(-1);
        } catch (TimeoutException e) {
            // Does not happen, infinite timeout
        }

        if (request != null) {
            request.dequeue(
                    mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O);
        }
        return request;
    }

    /**
     * Waits for the result of a {@link android.hardware.usb.UsbRequest#queue} operation
     * <p>Note that this may return requests queued on multiple
     * {@link android.hardware.usb.UsbEndpoint}s. When multiple endpoints are in use,
     * {@link android.hardware.usb.UsbRequest#getEndpoint} and {@link
     * android.hardware.usb.UsbRequest#getClientData} can be useful in determining how to process
     * the result of this function.</p>
     * <p>Android processes {@link UsbRequest UsbRequests} asynchronously. Hence it is not
     * guaranteed that {@link #requestWait(long) requestWait(0)} returns a request that has been
     * queued right before even if the request could have been processed immediately.</p>
     *
     * @param timeout timeout in milliseconds. If 0 this method does not wait.
     *
     * @return a completed USB request, or {@code null} if an error occurred
     *
     * @throws BufferOverflowException if the number of bytes read or written is more than the
     *                                 limit of the request's buffer. The number of bytes is
     *                                 determined by the {@code length} parameter of
     *                                 {@link UsbRequest#queue(ByteBuffer, int)}
     * @throws TimeoutException if no request was received in {@code timeout} milliseconds.
     */
    public UsbRequest requestWait(long timeout) throws TimeoutException {
        timeout = Preconditions.checkArgumentNonnegative(timeout, "timeout");

        UsbRequest request = native_request_wait(timeout);
        if (request != null) {
            request.dequeue(true);
        }
        return request;
    }

    /**
     * Returns the serial number for the device.
     * This will return null if the device has not been opened.
     *
     * @return the device serial number
     */
    public String getSerial() {
        return native_get_serial();
    }

    private static void checkBounds(byte[] buffer, int start, int length) {
        final int bufferLength = (buffer != null ? buffer.length : 0);
        if (length < 0 || start < 0 || start + length > bufferLength) {
            throw new IllegalArgumentException("Buffer start or length out of bounds.");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
        } finally {
            super.finalize();
        }
    }

    private native boolean native_open(String deviceName, FileDescriptor pfd);
    private native void native_close();
    private native int native_get_fd();
    private native byte[] native_get_desc();
    private native boolean native_claim_interface(int interfaceID, boolean force);
    private native boolean native_release_interface(int interfaceID);
    private native boolean native_set_interface(int interfaceID, int alternateSetting);
    private native boolean native_set_configuration(int configurationID);
    private native int native_control_request(int requestType, int request, int value,
            int index, byte[] buffer, int offset, int length, int timeout);
    private native int native_bulk_request(int endpoint, byte[] buffer,
            int offset, int length, int timeout);
    private native UsbRequest native_request_wait(long timeout) throws TimeoutException;
    private native String native_get_serial();
    private native boolean native_reset_device();
}
