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

import android.os.Parcel;
import android.os.Parcelable;

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
    private final String mManufacturerName;
    private final String mProductName;
    private final String mSerialNumber;
    private final int mVendorId;
    private final int mProductId;
    private final int mClass;
    private final int mSubclass;
    private final int mProtocol;
    private Parcelable[] mConfigurations;

    // list of all interfaces on the device
    private UsbInterface[] mInterfaces;

    /**
     * UsbDevice should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbDevice(String name, int vendorId, int productId,
            int Class, int subClass, int protocol,
            String manufacturerName, String productName, String serialNumber) {
        mName = name;
        mVendorId = vendorId;
        mProductId = productId;
        mClass = Class;
        mSubclass = subClass;
        mProtocol = protocol;
        mManufacturerName = manufacturerName;
        mProductName = productName;
        mSerialNumber = serialNumber;
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
     * Returns the manufacturer name of the device.
     *
     * @return the manufacturer name
     */
    public String getManufacturerName() {
        return mManufacturerName;
    }

    /**
     * Returns the product name of the device.
     *
     * @return the product name
     */
    public String getProductName() {
        return mProductName;
    }

    /**
     * Returns the serial number of the device.
     *
     * @return the serial number name
     */
    public String getSerialNumber() {
        return mSerialNumber;
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
     * Returns the number of {@link UsbConfiguration}s this device contains.
     *
     * @return the number of configurations
     */
    public int getConfigurationCount() {
        return mConfigurations.length;
    }

    /**
     * Returns the {@link UsbConfiguration} at the given index.
     *
     * @return the configuration
     */
    public UsbConfiguration getConfiguration(int index) {
        return (UsbConfiguration)mConfigurations[index];
    }

    private UsbInterface[] getInterfaceList() {
        if (mInterfaces == null) {
            int configurationCount = mConfigurations.length;
            int interfaceCount = 0;
            for (int i = 0; i < configurationCount; i++) {
                UsbConfiguration configuration = (UsbConfiguration)mConfigurations[i];
                interfaceCount += configuration.getInterfaceCount();
            }

            mInterfaces = new UsbInterface[interfaceCount];
            int offset = 0;
            for (int i = 0; i < configurationCount; i++) {
                UsbConfiguration configuration = (UsbConfiguration)mConfigurations[i];
                interfaceCount = configuration.getInterfaceCount();
                for (int j = 0; j < interfaceCount; j++) {
                    mInterfaces[offset++] = configuration.getInterface(j);
                }
            }
        }

        return mInterfaces;
    }

    /**
     * Returns the number of {@link UsbInterface}s this device contains.
     * For devices with multiple configurations, you will probably want to use
     * {@link UsbConfiguration#getInterfaceCount} instead.
     *
     * @return the number of interfaces
     */
    public int getInterfaceCount() {
        return getInterfaceList().length;
    }

    /**
     * Returns the {@link UsbInterface} at the given index.
     * For devices with multiple configurations, you will probably want to use
     * {@link UsbConfiguration#getInterface} instead.
     *
     * @return the interface
     */
    public UsbInterface getInterface(int index) {
        return getInterfaceList()[index];
    }

    /**
     * Only used by UsbService implementation
     * @hide
     */
    public void setConfigurations(Parcelable[] configuration) {
        mConfigurations = configuration;
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
        StringBuilder builder = new StringBuilder("UsbDevice[mName=" + mName +
                ",mVendorId=" + mVendorId + ",mProductId=" + mProductId +
                ",mClass=" + mClass + ",mSubclass=" + mSubclass + ",mProtocol=" + mProtocol +
                ",mManufacturerName=" + mManufacturerName + ",mProductName=" + mProductName +
                ",mSerialNumber=" + mSerialNumber + ",mConfigurations=[");
        for (int i = 0; i < mConfigurations.length; i++) {
            builder.append("\n");
            builder.append(mConfigurations[i].toString());
        }
        builder.append("]");
        return builder.toString();
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
            String manufacturerName = in.readString();
            String productName = in.readString();
            String serialNumber = in.readString();
            Parcelable[] configurations = in.readParcelableArray(UsbInterface.class.getClassLoader());
            UsbDevice device = new UsbDevice(name, vendorId, productId, clasz, subClass, protocol,
                                 manufacturerName, productName, serialNumber);
            device.setConfigurations(configurations);
            return device;
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
        parcel.writeString(mManufacturerName);
        parcel.writeString(mProductName);
        parcel.writeString(mSerialNumber);
        parcel.writeParcelableArray(mConfigurations, 0);
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
