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
import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;


/**
 * This class is used for sending and receiving data and control messages to a USB device.
 * Instances of this class are created by {@link UsbManager#openDevice}.
 */
public class UsbDeviceConnection {

    private static final String TAG = "UsbDeviceConnection";

    private final UsbDevice mDevice;

    private Context mContext;

    // used by the JNI code
    private long mNativeContext;

    /**
     * UsbDevice should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbDeviceConnection(UsbDevice device) {
        mDevice = device;
    }

    /* package */ boolean open(String name, ParcelFileDescriptor pfd,  @NonNull Context context) {
        mContext = context.getApplicationContext();

        return native_open(name, pfd.getFileDescriptor());
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
     * Releases all system resources related to the device.
     * Once the object is closed it cannot be used again.
     * The client must call {@link UsbManager#openDevice} again
     * to retrieve a new instance to reestablish communication with the device.
     */
    public void close() {
        native_close();
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
     * @param buffer buffer for data to send or receive
     * @param length the length of the data to send or receive
     * @param timeout in milliseconds
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
     * @param length the length of the data to send or receive
     * @param timeout in milliseconds
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int bulkTransfer(UsbEndpoint endpoint,
            byte[] buffer, int offset, int length, int timeout) {
        checkBounds(buffer, offset, length);
        return native_bulk_request(endpoint.getAddress(), buffer, offset, length, timeout);
    }

    /**
     * Waits for the result of a {@link android.hardware.usb.UsbRequest#queue} operation
     * Note that this may return requests queued on multiple 
     * {@link android.hardware.usb.UsbEndpoint}s.
     * When multiple endpoints are in use, {@link android.hardware.usb.UsbRequest#getEndpoint} and
     * {@link android.hardware.usb.UsbRequest#getClientData} can be useful in determining
     * how to process the result of this function.
     *
     * @return a completed USB request, or null if an error occurred
     */
    public UsbRequest requestWait() {
        UsbRequest request = native_request_wait();
        if (request != null) {
            request.dequeue();
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
        if (start < 0 || start + length > bufferLength) {
            throw new IllegalArgumentException("Buffer start or length out of bounds.");
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
    private native UsbRequest native_request_wait();
    private native String native_get_serial();
}
