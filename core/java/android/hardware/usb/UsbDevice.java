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

package android.hardware.usb;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;


/**
 * A class representing a USB device.
 */
public final class UsbDevice implements Parcelable {

    private static final String TAG = "UsbDevice";

    private String mName;
    private int mVendorId;
    private int mProductId;
    private int mClass;
    private int mSubclass;
    private int mProtocol;
    private Parcelable[] mInterfaces;

    // used by the JNI code
    private int mNativeContext;

    private UsbDevice() {
    }


    /**
     * UsbDevice should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbDevice(String name, int vendorId, int productId,
            int Class, int subClass, int protocol, Parcelable[] interfaces) {
        mName = name;
        mVendorId = vendorId;
        mProductId = productId;
        mClass = Class;
        mSubclass = subClass;
        mProtocol = protocol;
        mInterfaces = interfaces;
    }

    /**
     * Returns the name of the device.
     * In the standard implementation, this is the path of the device file
     * for the device in the usbfs file system.
     *
     * @return the device name
     */
    public String getDeviceName() {
        return mName;
    }

    /**
     * Returns a unique integer ID for the device.
     * This is a convenience for clients that want to use an integer to represent
     * the device, rather than the device name.
     * IDs are not persistent across USB disconnects.
     *
     * @return the device ID
     */
    public int getDeviceId() {
        return getDeviceId(mName);
    }

    /**
     * Returns a vendor ID for the device.
     *
     * @return the device vendor ID
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * Returns a product ID for the device.
     *
     * @return the device product ID
     */
    public int getProductId() {
        return mProductId;
    }

    /**
     * Returns the devices's class field.
     * Some useful constants for USB device classes can be found in
     * {@link android.hardware.usb.UsbConstants}
     *
     * @return the devices's class
     */
    public int getDeviceClass() {
        return mClass;
    }

    /**
     * Returns the device's subclass field.
     *
     * @return the device's subclass
     */
    public int getDeviceSubclass() {
        return mSubclass;
    }

    /**
     * Returns the device's subclass field.
     *
     * @return the device's protocol
     */
    public int getDeviceProtocol() {
        return mProtocol;
    }

    /**
     * Returns the number of {@link android.hardware.usb.UsbInterface}s this device contains.
     *
     * @return the number of interfaces
     */
    public int getInterfaceCount() {
        return mInterfaces.length;
    }

    /**
     * Returns the {@link android.hardware.usb.UsbInterface} at the given index.
     *
     * @return the interface
     */
    public UsbInterface getInterface(int index) {
        return (UsbInterface)mInterfaces[index];
    }

    /* package */ boolean open(ParcelFileDescriptor pfd) {
        return native_open(mName, pfd.getFileDescriptor());
    }

    /**
     * Releases all system resources related to the device.
     */
    public void close() {
        native_close();
    }

    /**
     * Returns an integer file descriptor for the device, or
     * -1 if the device is not opened.
     * This is intended for passing to native code to access the device
     */
    public int getFileDescriptor() {
        return native_get_fd();
    }

    /**
     * Claims exclusive access to a {@link android.hardware.usb.UsbInterface}.
     * This must be done before sending or receiving data on any
     * {@link android.hardware.usb.UsbEndpoint}s belonging to the interface
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
     * @param length the length of the data to send or receive
     * @param timeout in milliseconds
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int controlTransfer(int requestType, int request, int value,
            int index, byte[] buffer, int length, int timeout) {
        return native_control_request(requestType, request, value, index, buffer, length, timeout);
    }

    /**
     * Performs a bulk transaction on the given endpoint.
     * The direction of the transfer is determined by the direction of the endpoint
     *
     * @param endpoint the endpoint for this transaction
     * @param buffer buffer for data to send or receive,
     * @param length the length of the data to send or receive
     * @param timeout in milliseconds
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        return native_bulk_request(endpoint.getAddress(), buffer, length, timeout);
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

    @Override
    public boolean equals(Object o) {
        if (o instanceof UsbDevice) {
            return ((UsbDevice)o).mName.equals(mName);
        } else if (o instanceof String) {
            return ((String)o).equals(mName);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return mName.hashCode();
    }

    @Override
    public String toString() {
        return "UsbDevice[mName=" + mName + ",mVendorId=" + mVendorId +
                ",mProductId=" + mProductId + ",mClass=" + mClass +
                ",mSubclass=" + mSubclass + ",mProtocol=" + mProtocol +
                ",mInterfaces=" + mInterfaces + "]";
    }

    public static final Parcelable.Creator<UsbDevice> CREATOR =
        new Parcelable.Creator<UsbDevice>() {
        public UsbDevice createFromParcel(Parcel in) {
            String name = in.readString();
            int vendorId = in.readInt();
            int productId = in.readInt();
            int clasz = in.readInt();
            int subClass = in.readInt();
            int protocol = in.readInt();
            Parcelable[] interfaces = in.readParcelableArray(UsbInterface.class.getClassLoader());
            UsbDevice result = new UsbDevice(name, vendorId, productId, clasz, subClass, protocol, interfaces);
            for (int i = 0; i < interfaces.length; i++) {
                ((UsbInterface)interfaces[i]).setDevice(result);
            }
            return result;
        }

        public UsbDevice[] newArray(int size) {
            return new UsbDevice[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mName);
        parcel.writeInt(mVendorId);
        parcel.writeInt(mProductId);
        parcel.writeInt(mClass);
        parcel.writeInt(mSubclass);
        parcel.writeInt(mProtocol);
        parcel.writeParcelableArray(mInterfaces, 0);
   }

    public static int getDeviceId(String name) {
        return native_get_device_id(name);
    }

    public static String getDeviceName(int id) {
        return native_get_device_name(id);
    }

    private native boolean native_open(String deviceName, FileDescriptor pfd);
    private native void native_close();
    private native int native_get_fd();
    private native boolean native_claim_interface(int interfaceID, boolean force);
    private native boolean native_release_interface(int interfaceID);
    private native int native_control_request(int requestType, int request, int value,
            int index, byte[] buffer, int length, int timeout);
    private native int native_bulk_request(int endpoint, byte[] buffer, int length, int timeout);
    private native UsbRequest native_request_wait();
    private native String native_get_serial();

    private static native int native_get_device_id(String name);
    private static native String native_get_device_name(int id);
}
