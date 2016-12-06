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


package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * The Bluetooth Health Application Configuration that is used in conjunction with
 * the {@link BluetoothHealth} class. This class represents an application configuration
 * that the Bluetooth Health third party application will register to communicate with the
 * remote Bluetooth health device.
 *
 */
public final class BluetoothHealthAppConfiguration implements Parcelable {
    private final String mName;
    private final int mDataType;
    private final int mRole;
    private final int mChannelType;

    /**
     * Constructor to register the SINK role
     *
     * @param name Friendly name associated with the application configuration
     * @param dataType Data Type of the remote Bluetooth Health device
     * @hide
     */
    BluetoothHealthAppConfiguration(String name, int dataType) {
        mName = name;
        mDataType = dataType;
        mRole = BluetoothHealth.SINK_ROLE;
        mChannelType = BluetoothHealth.CHANNEL_TYPE_ANY;
    }

    /**
     * Constructor to register the application configuration.
     *
     * @param name Friendly name associated with the application configuration
     * @param dataType Data Type of the remote Bluetooth Health device
     * @param role {@link BluetoothHealth#SOURCE_ROLE} or
     *                     {@link BluetoothHealth#SINK_ROLE}
     * @hide
     */
    BluetoothHealthAppConfiguration(String name, int dataType, int role, int
        channelType) {
        mName = name;
        mDataType = dataType;
        mRole = role;
        mChannelType = channelType;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothHealthAppConfiguration) {
            BluetoothHealthAppConfiguration config = (BluetoothHealthAppConfiguration) o;

            if (mName == null) return false;

            return mName.equals(config.getName()) &&
                    mDataType == config.getDataType() &&
                    mRole == config.getRole() &&
                    mChannelType == config.getChannelType();
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mName != null ? mName.hashCode() : 0);
        result = 31 * result + mDataType;
        result = 31 * result + mRole;
        result = 31 * result + mChannelType;
        return result;
    }

    @Override
    public String toString() {
        return "BluetoothHealthAppConfiguration [mName = " + mName +
            ",mDataType = " + mDataType + ", mRole = " + mRole + ",mChannelType = " +
            mChannelType + "]";
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Return the data type associated with this application configuration.
     *
     * @return dataType
     */
    public int getDataType() {
        return mDataType;
    }

    /**
     * Return the name of the application configuration.
     *
     * @return String name
     */
    public String getName() {
        return mName;
    }

    /**
     * Return the role associated with this application configuration.
     *
     * @return One of {@link BluetoothHealth#SOURCE_ROLE} or
     *                         {@link BluetoothHealth#SINK_ROLE}
     */
    public int getRole() {
        return mRole;
    }

    /**
     * Return the channel type associated with this application configuration.
     *
     * @return One of {@link BluetoothHealth#CHANNEL_TYPE_RELIABLE} or
     *                         {@link BluetoothHealth#CHANNEL_TYPE_STREAMING} or
     *                         {@link BluetoothHealth#CHANNEL_TYPE_ANY}.
     * @hide
     */
    public int getChannelType() {
        return mChannelType;
    }

    public static final Parcelable.Creator<BluetoothHealthAppConfiguration> CREATOR =
        new Parcelable.Creator<BluetoothHealthAppConfiguration>() {
        @Override
        public BluetoothHealthAppConfiguration createFromParcel(Parcel in) {
            String name = in.readString();
            int type = in.readInt();
            int role = in.readInt();
            int channelType = in.readInt();
            return new BluetoothHealthAppConfiguration(name, type, role,
                channelType);
        }

        @Override
        public BluetoothHealthAppConfiguration[] newArray(int size) {
            return new BluetoothHealthAppConfiguration[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mName);
        out.writeInt(mDataType);
        out.writeInt(mRole);
        out.writeInt(mChannelType);
    }
}
