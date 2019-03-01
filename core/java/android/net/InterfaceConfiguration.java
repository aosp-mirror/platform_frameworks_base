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

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.google.android.collect.Sets;

import java.net.InetAddress;
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

    // Must be kept in sync with constant in INetd.aidl
    private static final String FLAG_UP = "up";
    private static final String FLAG_DOWN = "down";

    private static final  String[] EMPTY_STRING_ARRAY = new String[0];

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("mHwAddr=").append(mHwAddr);
        builder.append(" mAddr=").append(String.valueOf(mAddr));
        builder.append(" mFlags=").append(getFlags());
        return builder.toString();
    }

    @UnsupportedAppUsage
    public Iterable<String> getFlags() {
        return mFlags;
    }

    public boolean hasFlag(String flag) {
        validateFlag(flag);
        return mFlags.contains(flag);
    }

    @UnsupportedAppUsage
    public void clearFlag(String flag) {
        validateFlag(flag);
        mFlags.remove(flag);
    }

    @UnsupportedAppUsage
    public void setFlag(String flag) {
        validateFlag(flag);
        mFlags.add(flag);
    }

    /**
     * Set flags to mark interface as up.
     */
    @UnsupportedAppUsage
    public void setInterfaceUp() {
        mFlags.remove(FLAG_DOWN);
        mFlags.add(FLAG_UP);
    }

    /**
     * Set flags to mark interface as down.
     */
    @UnsupportedAppUsage
    public void setInterfaceDown() {
        mFlags.remove(FLAG_UP);
        mFlags.add(FLAG_DOWN);
    }

    /**
     * Set flags so that no changes will be made to the up/down status.
     */
    public void ignoreInterfaceUpDownStatus() {
        mFlags.remove(FLAG_UP);
        mFlags.remove(FLAG_DOWN);
    }

    public LinkAddress getLinkAddress() {
        return mAddr;
    }

    @UnsupportedAppUsage
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
     * Construct InterfaceConfiguration from InterfaceConfigurationParcel.
     */
    public static InterfaceConfiguration fromParcel(InterfaceConfigurationParcel p) {
        InterfaceConfiguration cfg = new InterfaceConfiguration();
        cfg.setHardwareAddress(p.hwAddr);

        final InetAddress addr = NetworkUtils.numericToInetAddress(p.ipv4Addr);
        cfg.setLinkAddress(new LinkAddress(addr, p.prefixLength));
        for (String flag : p.flags) {
            cfg.setFlag(flag);
        }

        return cfg;
    }

    /**
     * Convert InterfaceConfiguration to InterfaceConfigurationParcel with given ifname.
     */
    public InterfaceConfigurationParcel toParcel(String iface) {
        InterfaceConfigurationParcel cfgParcel = new InterfaceConfigurationParcel();
        cfgParcel.ifName = iface;
        if (!TextUtils.isEmpty(mHwAddr)) {
            cfgParcel.hwAddr = mHwAddr;
        } else {
            cfgParcel.hwAddr = "";
        }
        cfgParcel.ipv4Addr = mAddr.getAddress().getHostAddress();
        cfgParcel.prefixLength = mAddr.getPrefixLength();
        cfgParcel.flags = mFlags.toArray(EMPTY_STRING_ARRAY);

        return cfgParcel;
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
            if (isUp()) {
                for (byte b : mAddr.getAddress().getAddress()) {
                    if (b != 0) return true;
                }
            }
        } catch (NullPointerException e) {
            return false;
        }
        return false;
    }

    public boolean isUp() {
        return hasFlag(FLAG_UP);
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

    public static final @android.annotation.NonNull Creator<InterfaceConfiguration> CREATOR = new Creator<
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
