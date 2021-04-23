/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.apf;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityResources;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * APF program support capabilities. APF stands for Android Packet Filtering and it is a flexible
 * way to drop unwanted network packets to save power.
 *
 * See documentation at hardware/google/apf/apf.h
 *
 * This class is immutable.
 * @hide
 */
@SystemApi
public final class ApfCapabilities implements Parcelable {
    private static ConnectivityResources sResources;

    /**
     * Version of APF instruction set supported for packet filtering. 0 indicates no support for
     * packet filtering using APF programs.
     */
    public final int apfVersionSupported;

    /**
     * Maximum size of APF program allowed.
     */
    public final int maximumApfProgramSize;

    /**
     * Format of packets passed to APF filter. Should be one of ARPHRD_*
     */
    public final int apfPacketFormat;

    public ApfCapabilities(
            int apfVersionSupported, int maximumApfProgramSize, int apfPacketFormat) {
        this.apfVersionSupported = apfVersionSupported;
        this.maximumApfProgramSize = maximumApfProgramSize;
        this.apfPacketFormat = apfPacketFormat;
    }

    private ApfCapabilities(Parcel in) {
        apfVersionSupported = in.readInt();
        maximumApfProgramSize = in.readInt();
        apfPacketFormat = in.readInt();
    }

    @NonNull
    private static synchronized ConnectivityResources getResources(@NonNull Context ctx) {
        if (sResources == null)  {
            sResources = new ConnectivityResources(ctx);
        }
        return sResources;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(apfVersionSupported);
        dest.writeInt(maximumApfProgramSize);
        dest.writeInt(apfPacketFormat);
    }

    public static final Creator<ApfCapabilities> CREATOR = new Creator<ApfCapabilities>() {
        @Override
        public ApfCapabilities createFromParcel(Parcel in) {
            return new ApfCapabilities(in);
        }

        @Override
        public ApfCapabilities[] newArray(int size) {
            return new ApfCapabilities[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return String.format("%s{version: %d, maxSize: %d, format: %d}", getClass().getSimpleName(),
                apfVersionSupported, maximumApfProgramSize, apfPacketFormat);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof  ApfCapabilities)) return false;
        final ApfCapabilities other = (ApfCapabilities) obj;
        return apfVersionSupported == other.apfVersionSupported
                && maximumApfProgramSize == other.maximumApfProgramSize
                && apfPacketFormat == other.apfPacketFormat;
    }

    /**
     * Determines whether the APF interpreter advertises support for the data buffer access opcodes
     * LDDW (LoaD Data Word) and STDW (STore Data Word). Full LDDW (LoaD Data Word) and
     * STDW (STore Data Word) support is present from APFv4 on.
     *
     * @return {@code true} if the IWifiStaIface#readApfPacketFilterData is supported.
     */
    public boolean hasDataAccess() {
        return apfVersionSupported >= 4;
    }

    /**
     * @return Whether the APF Filter in the device should filter out IEEE 802.3 Frames.
     */
    public static boolean getApfDrop8023Frames() {
        // TODO: deprecate/remove this method (now unused in the platform), as the resource was
        // moved to NetworkStack.
        final Resources systemRes = Resources.getSystem();
        final int id = systemRes.getIdentifier("config_apfDrop802_3Frames", "bool", "android");
        return systemRes.getBoolean(id);
    }

    /**
     * @return An array of denylisted EtherType, packets with EtherTypes within it will be dropped.
     */
    public static @NonNull int[] getApfEtherTypeBlackList() {
        // TODO: deprecate/remove this method (now unused in the platform), as the resource was
        // moved to NetworkStack.
        final Resources systemRes = Resources.getSystem();
        final int id = systemRes.getIdentifier("config_apfEthTypeBlackList", "array", "android");
        return systemRes.getIntArray(id);
    }
}
