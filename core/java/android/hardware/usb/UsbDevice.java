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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.util.Objects;

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
 * <a href="{@docRoot}guide/topics/connectivity/usb/index.html">USB</a> developer guide.</p>
 * </div>
 */
public class UsbDevice implements Parcelable {

    private static final String TAG = "UsbDevice";
    private static final boolean DEBUG = false;

    private final @NonNull String mName;
    private final @Nullable String mManufacturerName;
    private final @Nullable String mProductName;
    private final @NonNull String mVersion;
    private final @NonNull UsbConfiguration[] mConfigurations;
    private final @NonNull IUsbSerialReader mSerialNumberReader;
    private final int mVendorId;
    private final int mProductId;
    private final int mClass;
    private final int mSubclass;
    private final int mProtocol;
    private final boolean mHasAudioPlayback;
    private final boolean mHasAudioCapture;
    private final boolean mHasMidi;
    private final boolean mHasVideoPlayback;
    private final boolean mHasVideoCapture;

    /** All interfaces on the device. Initialized on first call to getInterfaceList */
    @UnsupportedAppUsage
    private @Nullable UsbInterface[] mInterfaces;

    /**
     * Create a new UsbDevice object. Only called by {@link Builder#build(IUsbSerialReader)}
     *
     * @hide
     */
    private UsbDevice(@NonNull String name, int vendorId, int productId, int Class, int subClass,
            int protocol, @Nullable String manufacturerName, @Nullable String productName,
            @NonNull String version, @NonNull UsbConfiguration[] configurations,
            @NonNull IUsbSerialReader serialNumberReader,
            boolean hasAudioPlayback, boolean hasAudioCapture, boolean hasMidi,
            boolean hasVideoPlayback, boolean hasVideoCapture) {
        mName = Objects.requireNonNull(name);
        mVendorId = vendorId;
        mProductId = productId;
        mClass = Class;
        mSubclass = subClass;
        mProtocol = protocol;
        mManufacturerName = manufacturerName;
        mProductName = productName;
        mVersion = Preconditions.checkStringNotEmpty(version);
        mConfigurations = Preconditions.checkArrayElementsNotNull(configurations, "configurations");
        mSerialNumberReader = Objects.requireNonNull(serialNumberReader);
        mHasAudioPlayback = hasAudioPlayback;
        mHasAudioCapture = hasAudioCapture;
        mHasMidi = hasMidi;
        mHasVideoPlayback = hasVideoPlayback;
        mHasVideoCapture = hasVideoCapture;

        // Make sure the binder belongs to the system
        if (ActivityThread.isSystem()) {
            Preconditions.checkArgument(mSerialNumberReader instanceof IUsbSerialReader.Stub);
        }
    }

    /**
     * Returns the name of the device.
     * In the standard implementation, this is the path of the device file
     * for the device in the usbfs file system.
     *
     * @return the device name
     */
    public @NonNull String getDeviceName() {
        return mName;
    }

    /**
     * Returns the manufacturer name of the device.
     *
     * @return the manufacturer name, or {@code null} if the property could not be read
     */
    public @Nullable String getManufacturerName() {
        return mManufacturerName;
    }

    /**
     * Returns the product name of the device.
     *
     * @return the product name, or {@code null} if the property could not be read
     */
    public @Nullable String getProductName() {
        return mProductName;
    }

    /**
     * Returns the version number of the device.
     *
     * @return the device version
     */
    public @NonNull String getVersion() {
        return mVersion;
    }

    /**
     * Returns the serial number of the device.
     *
     * @return the serial number name, or {@code null} if the property could not be read
     *
     * @throws SecurityException if the app targets SDK >= {@value android.os.Build.VERSION_CODES#Q}
     *                           and the app does not have permission to read from the device.
     */
    public @Nullable String getSerialNumber() {
        try {
            return mSerialNumberReader.getSerial(ActivityThread.currentPackageName());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
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

    /** @hide */
    public boolean getHasAudioPlayback() {
        return mHasAudioPlayback;
    }

    /** @hide */
    public boolean getHasAudioCapture() {
        return mHasAudioCapture;
    }

    /** @hide */
    public boolean getHasMidi() {
        return mHasMidi;
    }

    /** @hide */
    public boolean getHasVideoPlayback() {
        return mHasVideoPlayback;
    }

    /** @hide */
    public boolean getHasVideoCapture() {
        return mHasVideoCapture;
    }

    /**
     * Returns the {@link UsbConfiguration} at the given index.
     *
     * @return the configuration
     */
    public @NonNull UsbConfiguration getConfiguration(int index) {
        return mConfigurations[index];
    }

    private @Nullable UsbInterface[] getInterfaceList() {
        if (mInterfaces == null) {
            int configurationCount = mConfigurations.length;
            int interfaceCount = 0;
            for (int i = 0; i < configurationCount; i++) {
                UsbConfiguration configuration = mConfigurations[i];
                interfaceCount += configuration.getInterfaceCount();
            }

            mInterfaces = new UsbInterface[interfaceCount];
            int offset = 0;
            for (int i = 0; i < configurationCount; i++) {
                UsbConfiguration configuration = mConfigurations[i];
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
    public @NonNull UsbInterface getInterface(int index) {
        return getInterfaceList()[index];
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
        StringBuilder builder = new StringBuilder("UsbDevice[mName=" + mName
                + ",mVendorId=" + mVendorId + ",mProductId=" + mProductId
                + ",mClass=" + mClass + ",mSubclass=" + mSubclass + ",mProtocol=" + mProtocol
                + ",mManufacturerName=" + mManufacturerName + ",mProductName=" + mProductName
                + ",mVersion=" + mVersion + ",mSerialNumberReader=" + mSerialNumberReader
                + ", mHasAudioPlayback=" + mHasAudioPlayback
                + ", mHasAudioCapture=" + mHasAudioCapture
                + ", mHasMidi=" + mHasMidi
                + ", mHasVideoCapture=" + mHasVideoCapture
                + ", mHasVideoPlayback=" + mHasVideoPlayback
                + ", mConfigurations=[");
        for (int i = 0; i < mConfigurations.length; i++) {
            builder.append("\n");
            builder.append(mConfigurations[i].toString());
        }
        builder.append("]");
        return builder.toString();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<UsbDevice> CREATOR =
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
            String version = in.readString();
            IUsbSerialReader serialNumberReader =
                    IUsbSerialReader.Stub.asInterface(in.readStrongBinder());
            UsbConfiguration[] configurations = in.readParcelableArray(
                    UsbConfiguration.class.getClassLoader(), UsbConfiguration.class);
            // Capabilities
            boolean hasAudioPlayback = in.readInt() == 1;
            boolean hasAudioCapture = in.readInt() == 1;
            boolean hasMidi = in.readInt() == 1;
            boolean hasVideoPlayback = in.readInt() == 1;
            boolean hasVideoCapture = in.readInt() == 1;
            UsbDevice device = new UsbDevice(name, vendorId, productId, clasz, subClass, protocol,
                    manufacturerName, productName, version, configurations, serialNumberReader,
                    hasAudioPlayback, hasAudioCapture, hasMidi,
                    hasVideoPlayback, hasVideoCapture);

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
        parcel.writeString(mVersion);
        parcel.writeStrongBinder(mSerialNumberReader.asBinder());
        parcel.writeParcelableArray(mConfigurations, 0);
        parcel.writeInt(mHasAudioPlayback ? 1 : 0);
        parcel.writeInt(mHasAudioCapture ? 1 : 0);
        parcel.writeInt(mHasMidi ? 1 : 0);
        parcel.writeInt(mHasVideoPlayback ? 1 : 0);
        parcel.writeInt(mHasVideoCapture ? 1 : 0);
    }

    public static int getDeviceId(String name) {
        return native_get_device_id(name);
    }

    public static String getDeviceName(int id) {
        return native_get_device_name(id);
    }

    private static native int native_get_device_id(String name);
    private static native String native_get_device_name(int id);

    /**
     * @hide
     */
    public static class Builder {
        private final @NonNull String mName;
        private final int mVendorId;
        private final int mProductId;
        private final int mClass;
        private final int mSubclass;
        private final int mProtocol;
        private final @Nullable String mManufacturerName;
        private final @Nullable String mProductName;
        private final @NonNull String mVersion;
        private final @NonNull UsbConfiguration[] mConfigurations;
        private final boolean mHasAudioPlayback;
        private final boolean mHasAudioCapture;
        private final boolean mHasMidi;
        private final boolean mHasVideoPlayback;
        private final boolean mHasVideoCapture;

        // Temporary storage for serial number. Serial number reader need to be wrapped in a
        // IUsbSerialReader as they might be used as PII.
        public final @Nullable String serialNumber;

        public Builder(@NonNull String name, int vendorId, int productId, int Class, int subClass,
                int protocol, @Nullable String manufacturerName, @Nullable String productName,
                @NonNull String version, @NonNull UsbConfiguration[] configurations,
                @Nullable String serialNumber,
                boolean hasAudioPlayback, boolean hasAudioCapture, boolean hasMidi,
                boolean hasVideoPlayback, boolean hasVideoCapture) {
            mName = Objects.requireNonNull(name);
            mVendorId = vendorId;
            mProductId = productId;
            mClass = Class;
            mSubclass = subClass;
            mProtocol = protocol;
            mManufacturerName = manufacturerName;
            mProductName = productName;
            mVersion = Preconditions.checkStringNotEmpty(version);
            mConfigurations = configurations;
            this.serialNumber = serialNumber;
            mHasAudioPlayback = hasAudioPlayback;
            mHasAudioCapture = hasAudioCapture;
            mHasMidi = hasMidi;
            mHasVideoPlayback = hasVideoPlayback;
            mHasVideoCapture = hasVideoCapture;
        }

        /**
         * Create a new {@link UsbDevice}
         *
         * @param serialReader The method to read the serial number.
         *
         * @return The usb device
         */
        public UsbDevice build(@NonNull IUsbSerialReader serialReader) {
            return new UsbDevice(mName, mVendorId, mProductId, mClass, mSubclass, mProtocol,
                    mManufacturerName, mProductName, mVersion, mConfigurations, serialReader,
                    mHasAudioPlayback, mHasAudioCapture, mHasMidi,
                    mHasVideoPlayback, mHasVideoCapture);
        }
    }
}
