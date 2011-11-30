/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.android.collect.Sets;

import java.util.HashSet;

/**
 * Configuration details for a network interface.
 *
 * @hide
 */
public class InterfaceConfiguration implements Parcelable {
    private String mHwAddr;
    private LinkAddress mAddr;
    private HashSet<String> mFlags = Sets.newHashSet();

    private static final String FLAG_UP = "up";
    private static final String FLAG_DOWN = "down";

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("mHwAddr=").append(mHwAddr);
        builder.append(" mAddr=").append(String.valueOf(mAddr));
        builder.append(" mFlags=").append(getFlags());
        return builder.toString();
    }

    /**
     * Return flags separated by spaces.
     */
    public String getFlags() {
        final int size = mFlags.size();
        if (size == 0) {
            return "";
        }

        final String[] flags = mFlags.toArray(new String[size]);
        final StringBuilder builder = new StringBuilder();

        builder.append(flags[0]);
        for (int i = 1; i < flags.length; i++) {
            builder.append(' ');
            builder.append(flags[i]);
        }
        return builder.toString();
    }

    public boolean hasFlag(String flag) {
        validateFlag(flag);
        return mFlags.contains(flag);
    }

    public void clearFlag(String flag) {
        validateFlag(flag);
        mFlags.remove(flag);
    }

    public void setFlag(String flag) {
        validateFlag(flag);
        mFlags.add(flag);
    }

    /**
     * Set flags to mark interface as up.
     */
    public void setInterfaceUp() {
        mFlags.remove(FLAG_DOWN);
        mFlags.add(FLAG_UP);
    }

    /**
     * Set flags to mark interface as down.
     */
    public void setInterfaceDown() {
        mFlags.remove(FLAG_UP);
        mFlags.add(FLAG_DOWN);
    }

    public LinkAddress getLinkAddress() {
        return mAddr;
    }

    public void setLinkAddress(LinkAddress addr) {
        mAddr = addr;
    }

    public String getHardwareAddress() {
        return mHwAddr;
    }

    public void setHardwareAddress(String hwAddr) {
        mHwAddr = hwAddr;
    }

    /**
     * This function determines if the interface is up and has a valid IP
     * configuration (IP address has a non zero octet).
     *
     * Note: It is supposed to be quick and hence should not initiate
     * any network activity
     */
    public boolean isActive() {
        try {
            if (hasFlag(FLAG_UP)) {
                for (byte b : mAddr.getAddress().getAddress()) {
                    if (b != 0) return true;
                }
            }
        } catch (NullPointerException e) {
            return false;
        }
        return false;
    }

    /** {@inheritDoc} */
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mHwAddr);
        if (mAddr != null) {
            dest.writeByte((byte)1);
            dest.writeParcelable(mAddr, flags);
        } else {
            dest.writeByte((byte)0);
        }
        dest.writeInt(mFlags.size());
        for (String flag : mFlags) {
            dest.writeString(flag);
        }
    }

    public static final Creator<InterfaceConfiguration> CREATOR = new Creator<
            InterfaceConfiguration>() {
        public InterfaceConfiguration createFromParcel(Parcel in) {
            InterfaceConfiguration info = new InterfaceConfiguration();
            info.mHwAddr = in.readString();
            if (in.readByte() == 1) {
                info.mAddr = in.readParcelable(null);
            }
            final int size = in.readInt();
            for (int i = 0; i < size; i++) {
                info.mFlags.add(in.readString());
            }
            return info;
        }

        public InterfaceConfiguration[] newArray(int size) {
            return new InterfaceConfiguration[size];
        }
    };

    private static void validateFlag(String flag) {
        if (flag.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("flag contains space: " + flag);
        }
    }
}
