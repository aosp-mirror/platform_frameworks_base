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
import android.util.Log;

import java.io.FileDescriptor;

/**
 * This class represents a USB device attached to the android device with the android device
 * acting as the USB host.
 * Each device contains one or more {@link UsbInterface}s, each of which contains a number of
 * {@link UsbEndpoint}s (the channels via which data is transmitted over USB).
 *
 * <p> This class contains information (along with {@link UsbInterface} and {@link UsbEndpoint})
 * that describes the capabilities of the USB device.
 * To communicate with the device, you open a {@link UsbDeviceConnection} for the device
 * and use {@link UsbRequest} to send and receive data on an endpoint.
 * {@link UsbDeviceConnection#controlTransfer} is used for control requests on endpoint zero.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about communicating with USB hardware, read the
 * <a href="{@docRoot}guide/topics/usb/index.html">USB</a> developer guide.</p>
 * </div>
 */
public class UsbDevice implements Parcelable {

    private static final String TAG = "UsbDevice";

    private final String mName;
    private final int mVendorId;
    private final int mProductId;
    private final int mClass;
    private final int mSubclass;
    private final int mProtocol;
    private final Parcelable[] mInterfaces;

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
     * Some useful constants for USB device classes can be found in {@link UsbConstants}.
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
     * Returns the device's protocol field.
     *
     * @return the device's protocol
     */
    public int getDeviceProtocol() {
        return mProtocol;
    }

    /**
     * Returns the number of {@link UsbInterface}s this device contains.
     *
     * @return the number of interfaces
     */
    public int getInterfaceCount() {
        return mInterfaces.length;
    }

    /**
     * Returns the {@link UsbInterface} at the given index.
     *
     * @return the interface
     */
    public UsbInterface getInterface(int index) {
        return (UsbInterface)mInterfaces[index];
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
            return new UsbDevice(name, vendorId, productId, clasz, subClass, protocol, interfaces);
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

    private static native int native_get_device_id(String name);
    private static native String native_get_device_name(int id);
}
