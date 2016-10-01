/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.midi;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import android.util.Log;

/**
 * This class contains information to describe a MIDI device.
 * For now we only have information that can be retrieved easily for USB devices,
 * but we will probably expand this in the future.
 *
 * This class is just an immutable object to encapsulate the MIDI device description.
 * Use the MidiDevice class to actually communicate with devices.
 */
public final class MidiDeviceInfo implements Parcelable {

    private static final String TAG = "MidiDeviceInfo";

    /*
     * Please note that constants and (un)marshalling code need to be kept in sync
     * with the native implementation (MidiDeviceInfo.h|cpp)
     */

    /**
     * Constant representing USB MIDI devices for {@link #getType}
     */
    public static final int TYPE_USB = 1;

    /**
     * Constant representing virtual (software based) MIDI devices for {@link #getType}
     */
    public static final int TYPE_VIRTUAL = 2;

    /**
     * Constant representing Bluetooth MIDI devices for {@link #getType}
     */
    public static final int TYPE_BLUETOOTH = 3;

    /**
     * Bundle key for the device's user visible name property.
     * The value for this property is of type {@link java.lang.String}.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}.
     * For USB devices, this is a concatenation of the manufacturer and product names.
     */
    public static final String PROPERTY_NAME = "name";

    /**
     * Bundle key for the device's manufacturer name property.
     * The value for this property is of type {@link java.lang.String}.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}.
     * Matches the USB device manufacturer name string for USB MIDI devices.
     */
    public static final String PROPERTY_MANUFACTURER = "manufacturer";

    /**
     * Bundle key for the device's product name property.
     * The value for this property is of type {@link java.lang.String}.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     * Matches the USB device product name string for USB MIDI devices.
     */
    public static final String PROPERTY_PRODUCT = "product";

    /**
     * Bundle key for the device's version property.
     * The value for this property is of type {@link java.lang.String}.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     * Matches the USB device version number for USB MIDI devices.
     */
    public static final String PROPERTY_VERSION = "version";

    /**
     * Bundle key for the device's serial number property.
     * The value for this property is of type {@link java.lang.String}.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     * Matches the USB device serial number for USB MIDI devices.
     */
    public static final String PROPERTY_SERIAL_NUMBER = "serial_number";

    /**
     * Bundle key for the device's corresponding USB device.
     * The value for this property is of type {@link android.hardware.usb.UsbDevice}.
     * Only set for USB MIDI devices.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     */
    public static final String PROPERTY_USB_DEVICE = "usb_device";

    /**
     * Bundle key for the device's corresponding Bluetooth device.
     * The value for this property is of type {@link android.bluetooth.BluetoothDevice}.
     * Only set for Bluetooth MIDI devices.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     */
    public static final String PROPERTY_BLUETOOTH_DEVICE = "bluetooth_device";

    /**
     * Bundle key for the device's ALSA card number.
     * The value for this property is an integer.
     * Only set for USB MIDI devices.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     *
     * @hide
     */
    public static final String PROPERTY_ALSA_CARD = "alsa_card";

    /**
     * Bundle key for the device's ALSA device number.
     * The value for this property is an integer.
     * Only set for USB MIDI devices.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     *
     * @hide
     */
    public static final String PROPERTY_ALSA_DEVICE = "alsa_device";

    /**
     * ServiceInfo for the service hosting the device implementation.
     * The value for this property is of type {@link android.content.pm.ServiceInfo}.
     * Only set for Virtual MIDI devices.
     * Used with the {@link android.os.Bundle} returned by {@link #getProperties}
     *
     * @hide
     */
    public static final String PROPERTY_SERVICE_INFO = "service_info";

    /**
     * Contains information about an input or output port.
     */
    public static final class PortInfo {
        /**
         * Port type for input ports
         */
        public static final int TYPE_INPUT = 1;

        /**
         * Port type for output ports
         */
        public static final int TYPE_OUTPUT = 2;

        private final int mPortType;
        private final int mPortNumber;
        private final String mName;

        PortInfo(int type, int portNumber, String name) {
            mPortType = type;
            mPortNumber = portNumber;
            mName = (name == null ? "" : name);
        }

        /**
         * Returns the port type of the port (either {@link #TYPE_INPUT} or {@link #TYPE_OUTPUT})
         * @return the port type
         */
        public int getType() {
            return mPortType;
        }

        /**
         * Returns the port number of the port
         * @return the port number
         */
        public int getPortNumber() {
            return mPortNumber;
        }

        /**
         * Returns the name of the port, or empty string if the port has no name
         * @return the port name
         */
        public String getName() {
            return mName;
        }
    }

    private final int mType;    // USB or virtual
    private final int mId;      // unique ID generated by MidiService
    private final int mInputPortCount;
    private final int mOutputPortCount;
    private final String[] mInputPortNames;
    private final String[] mOutputPortNames;
    private final Bundle mProperties;
    private final boolean mIsPrivate;

    /**
     * MidiDeviceInfo should only be instantiated by MidiService implementation
     * @hide
     */
    public MidiDeviceInfo(int type, int id, int numInputPorts, int numOutputPorts,
            String[] inputPortNames, String[] outputPortNames, Bundle properties,
            boolean isPrivate) {
        mType = type;
        mId = id;
        mInputPortCount = numInputPorts;
        mOutputPortCount = numOutputPorts;
        if (inputPortNames == null) {
            mInputPortNames = new String[numInputPorts];
        } else {
            mInputPortNames = inputPortNames;
        }
        if (outputPortNames == null) {
            mOutputPortNames = new String[numOutputPorts];
        } else {
            mOutputPortNames = outputPortNames;
        }
        mProperties = properties;
        mIsPrivate = isPrivate;
    }

    /**
     * Returns the type of the device.
     *
     * @return the device's type
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns the ID of the device.
     * This ID is generated by the MIDI service and is not persistent across device unplugs.
     *
     * @return the device's ID
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the device's number of input ports.
     *
     * @return the number of input ports
     */
    public int getInputPortCount() {
        return mInputPortCount;
    }

    /**
     * Returns the device's number of output ports.
     *
     * @return the number of output ports
     */
    public int getOutputPortCount() {
        return mOutputPortCount;
    }

    /**
     * Returns information about the device's ports.
     * The ports are in unspecified order.
     *
     * @return array of {@link PortInfo}
     */
    public PortInfo[] getPorts() {
        PortInfo[] ports = new PortInfo[mInputPortCount + mOutputPortCount];

        int index = 0;
        for (int i = 0; i < mInputPortCount; i++) {
            ports[index++] = new PortInfo(PortInfo.TYPE_INPUT, i, mInputPortNames[i]);
        }
        for (int i = 0; i < mOutputPortCount; i++) {
            ports[index++] = new PortInfo(PortInfo.TYPE_OUTPUT, i, mOutputPortNames[i]);
        }

        return ports;
    }

    /**
     * Returns the {@link android.os.Bundle} containing the device's properties.
     *
     * @return the device's properties
     */
    public Bundle getProperties() {
        return mProperties;
    }

    /**
     * Returns true if the device is private.  Private devices are only visible and accessible
     * to clients with the same UID as the application that is hosting the device.
     *
     * @return true if the device is private
     */
    public boolean isPrivate() {
        return mIsPrivate;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof MidiDeviceInfo) {
            return (((MidiDeviceInfo)o).mId == mId);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return mId;
    }

    @Override
    public String toString() {
        // This is a hack to force the mProperties Bundle to unparcel so we can
        // print all the names and values.
        mProperties.getString(PROPERTY_NAME);
        return ("MidiDeviceInfo[mType=" + mType +
                ",mInputPortCount=" + mInputPortCount +
                ",mOutputPortCount=" + mOutputPortCount +
                ",mProperties=" + mProperties +
                ",mIsPrivate=" + mIsPrivate);
    }

    public static final Parcelable.Creator<MidiDeviceInfo> CREATOR =
        new Parcelable.Creator<MidiDeviceInfo>() {
        public MidiDeviceInfo createFromParcel(Parcel in) {
            // Needs to be kept in sync with code in MidiDeviceInfo.cpp
            int type = in.readInt();
            int id = in.readInt();
            int inputPortCount = in.readInt();
            int outputPortCount = in.readInt();
            String[] inputPortNames = in.createStringArray();
            String[] outputPortNames = in.createStringArray();
            boolean isPrivate = (in.readInt() == 1);
            Bundle basicPropertiesIgnored = in.readBundle();
            Bundle properties = in.readBundle();
            return new MidiDeviceInfo(type, id, inputPortCount, outputPortCount,
                    inputPortNames, outputPortNames, properties, isPrivate);
        }

        public MidiDeviceInfo[] newArray(int size) {
            return new MidiDeviceInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    private Bundle getBasicProperties(String[] keys) {
        Bundle basicProperties = new Bundle();
        for (String key : keys) {
            Object val = mProperties.get(key);
            if (val != null) {
                if (val instanceof String) {
                    basicProperties.putString(key, (String) val);
                } else if (val instanceof Integer) {
                    basicProperties.putInt(key, (Integer) val);
                } else {
                    Log.w(TAG, "Unsupported property type: " + val.getClass().getName());
                }
            }
        }
        return basicProperties;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        // Needs to be kept in sync with code in MidiDeviceInfo.cpp
        parcel.writeInt(mType);
        parcel.writeInt(mId);
        parcel.writeInt(mInputPortCount);
        parcel.writeInt(mOutputPortCount);
        parcel.writeStringArray(mInputPortNames);
        parcel.writeStringArray(mOutputPortNames);
        parcel.writeInt(mIsPrivate ? 1 : 0);
        // "Basic" properties only contain properties of primitive types
        // and thus can be read back by native code. "Extra" properties is
        // a superset that contains all properties.
        parcel.writeBundle(getBasicProperties(new String[] {
            PROPERTY_NAME, PROPERTY_MANUFACTURER, PROPERTY_PRODUCT, PROPERTY_VERSION,
            PROPERTY_SERIAL_NUMBER, PROPERTY_ALSA_CARD, PROPERTY_ALSA_DEVICE
        }));
        // Must be serialized last so native code can safely ignore it.
        parcel.writeBundle(mProperties);
   }
}
