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

package android.hardware.usb;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing a configuration on a {@link UsbDevice}.
 * A USB configuration can have one or more interfaces, each one providing a different
 * piece of functionality, separate from the other interfaces.
 * An interface will have one or more {@link UsbEndpoint}s, which are the
 * channels by which the host transfers data with the device.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about communicating with USB hardware, read the
 * <a href="{@docRoot}guide/topics/usb/index.html">USB</a> developer guide.</p>
 * </div>
 */
public class UsbConfiguration implements Parcelable {

    private final int mId;
    private final String mName;
    private final int mAttributes;
    private final int mMaxPower;
    private Parcelable[] mInterfaces;

    /**
     * Mask for "self-powered" bit in the configuration's attributes.
     * @see #getAttributes
     */
    private static final int ATTR_SELF_POWERED = 1 << 6;

    /**
     * Mask for "remote wakeup" bit in the configuration's attributes.
     * @see #getAttributes
     */
    private static final int ATTR_REMOTE_WAKEUP = 1 << 5;

    /**
     * UsbConfiguration should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbConfiguration(int id, String name, int attributes, int maxPower) {
        mId = id;
        mName = name;
        mAttributes = attributes;
        mMaxPower = maxPower;
    }

    /**
     * Returns the configuration's ID field.
     * This is an integer that uniquely identifies the configuration on the device.
     *
     * @return the configuration's ID
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the configuration's name.
     *
     * @return the configuration's name
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the self-powered attribute value configuration's attributes field.
     * This attribute indicates that the device has a power source other than the USB connection.
     *
     * @return the configuration's self-powered attribute
     */
    public boolean isSelfPowered() {
        return (mAttributes & ATTR_SELF_POWERED) != 0;
    }

    /**
     * Returns the remote-wakeup attribute value configuration's attributes field.
     * This attributes that the device may signal the host to wake from suspend.
     *
     * @return the configuration's remote-wakeup attribute
     */
    public boolean isRemoteWakeup() {
        return (mAttributes & ATTR_REMOTE_WAKEUP) != 0;
    }

    /**
     * Returns the configuration's max power consumption, in milliamps.
     *
     * @return the configuration's max power
     */
    public int getMaxPower() {
        return mMaxPower * 2;
    }

    /**
     * Returns the number of {@link UsbInterface}s this configuration contains.
     *
     * @return the number of endpoints
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

    /**
     * Only used by UsbService implementation
     * @hide
     */
    public void setInterfaces(Parcelable[] interfaces) {
        mInterfaces = interfaces;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("UsbConfiguration[mId=" + mId +
                ",mName=" + mName + ",mAttributes=" + mAttributes +
                ",mMaxPower=" + mMaxPower + ",mInterfaces=[");
        for (int i = 0; i < mInterfaces.length; i++) {
            builder.append("\n");
            builder.append(mInterfaces[i].toString());
        }
        builder.append("]");
        return builder.toString();
    }

    public static final Parcelable.Creator<UsbConfiguration> CREATOR =
        new Parcelable.Creator<UsbConfiguration>() {
        public UsbConfiguration createFromParcel(Parcel in) {
            int id = in.readInt();
            String name = in.readString();
            int attributes = in.readInt();
            int maxPower = in.readInt();
            Parcelable[] interfaces = in.readParcelableArray(UsbInterface.class.getClassLoader());
            UsbConfiguration configuration = new UsbConfiguration(id, name, attributes, maxPower);
            configuration.setInterfaces(interfaces);
            return configuration;
        }

        public UsbConfiguration[] newArray(int size) {
            return new UsbConfiguration[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mId);
        parcel.writeString(mName);
        parcel.writeInt(mAttributes);
        parcel.writeInt(mMaxPower);
        parcel.writeParcelableArray(mInterfaces, 0);
   }
}
