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
package android.hardware.location;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.hardware.contexthub.V1_0.ContextHub;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import java.util.Arrays;

/**
 * @hide
 */
@SystemApi
public class ContextHubInfo implements Parcelable {
    private int mId;
    private String mName;
    private String mVendor;
    private String mToolchain;
    private int mPlatformVersion;
    private int mToolchainVersion;
    private float mPeakMips;
    private float mStoppedPowerDrawMw;
    private float mSleepPowerDrawMw;
    private float mPeakPowerDrawMw;
    private int mMaxPacketLengthBytes;
    private boolean mSupportsReliableMessages;
    private byte mChreApiMajorVersion;
    private byte mChreApiMinorVersion;
    private short mChrePatchVersion;
    private long mChrePlatformId;

    private int[] mSupportedSensors;

    private MemoryRegion[] mMemoryRegions;

    /*
     * TODO(b/67734082): Deprecate this constructor and mark private fields as final.
     */
    public ContextHubInfo() {
    }

    /**
     * @hide
     */
    public ContextHubInfo(ContextHub contextHub) {
        mId = contextHub.hubId;
        mName = contextHub.name;
        mVendor = contextHub.vendor;
        mToolchain = contextHub.toolchain;
        mPlatformVersion = contextHub.platformVersion;
        mToolchainVersion = contextHub.toolchainVersion;
        mPeakMips = contextHub.peakMips;
        mStoppedPowerDrawMw = contextHub.stoppedPowerDrawMw;
        mSleepPowerDrawMw = contextHub.sleepPowerDrawMw;
        mPeakPowerDrawMw = contextHub.peakPowerDrawMw;
        mMaxPacketLengthBytes = contextHub.maxSupportedMsgLen;
        mSupportsReliableMessages = false;
        mChrePlatformId = contextHub.chrePlatformId;
        mChreApiMajorVersion = contextHub.chreApiMajorVersion;
        mChreApiMinorVersion = contextHub.chreApiMinorVersion;
        mChrePatchVersion = contextHub.chrePatchVersion;

        mSupportedSensors = new int[0];
        mMemoryRegions = new MemoryRegion[0];
    }
    /**
     * @hide
     */
    public ContextHubInfo(android.hardware.contexthub.ContextHubInfo contextHub) {
        mId = contextHub.id;
        mName = contextHub.name;
        mVendor = contextHub.vendor;
        mToolchain = contextHub.toolchain;
        mPlatformVersion = 0;
        mToolchainVersion = 0;
        mPeakMips = contextHub.peakMips;
        mStoppedPowerDrawMw = 0;
        mSleepPowerDrawMw = 0;
        mPeakPowerDrawMw = 0;
        mMaxPacketLengthBytes = contextHub.maxSupportedMessageLengthBytes;
        mSupportsReliableMessages = Flags.reliableMessageImplementation()
                && contextHub.supportsReliableMessages;
        mChrePlatformId = contextHub.chrePlatformId;
        mChreApiMajorVersion = contextHub.chreApiMajorVersion;
        mChreApiMinorVersion = contextHub.chreApiMinorVersion;
        mChrePatchVersion = (short) contextHub.chrePatchVersion;

        mSupportedSensors = new int[0];
        mMemoryRegions = new MemoryRegion[0];
    }

    /**
     * Returns the maximum number of bytes for a message to the hub.
     *
     * @return int - maximum bytes that can be transmitted in a single packet.
     */
    public int getMaxPacketLengthBytes() {
        return mMaxPacketLengthBytes;
    }

    /**
     * Returns whether reliable messages are supported
     *
     * @return whether reliable messages are supported.
     */
    @FlaggedApi(Flags.FLAG_RELIABLE_MESSAGE)
    public boolean supportsReliableMessages() {
        return mSupportsReliableMessages;
    }

    /**
     * get the context hub unique identifer
     *
     * @return int - unique system wide identifier
     */
    public int getId() {
        return mId;
    }

    /**
     * get a string as a hub name
     *
     * @return String - a name for the hub
     */
    public String getName() {
        return mName;
    }

    /**
     * get a string as the vendor name
     *
     * @return String - a name for the vendor
     */
    public String getVendor() {
        return mVendor;
    }

    /**
     * get tool chain string
     *
     * @return String - description of the tool chain
     */
    public String getToolchain() {
        return mToolchain;
    }

    /**
     * get platform version
     *
     * @return int - platform version number
     */
    public int getPlatformVersion() {
        return mPlatformVersion;
    }

    /**
     * get static platform version number
     *
     * @return int - platform version number
     */
    public int getStaticSwVersion() {
        // Version parts are all unsigned values.
        return (Byte.toUnsignedInt(mChreApiMajorVersion) << 24)
                | (Byte.toUnsignedInt(mChreApiMinorVersion) << 16)
                | (Short.toUnsignedInt(mChrePatchVersion));
    }

    /**
     * get the tool chain version
     *
     * @return int - the tool chain version
     */
    public int getToolchainVersion() {
        return mToolchainVersion;
    }

    /**
     * get the peak processing mips the hub can support
     *
     * @return float - peak MIPS that this hub can deliver
     */
    public float getPeakMips() {
        return mPeakMips;
    }

    /**
     * get the stopped power draw in milliwatts
     * This assumes that the hub enter a stopped state - which is
     * different from the sleep state. Latencies on exiting the
     * sleep state are typically higher and expect to be in multiple
     * milliseconds.
     *
     * @return float - power draw by the hub in stopped state
     */
    public float getStoppedPowerDrawMw() {
        return mStoppedPowerDrawMw;
    }

    /**
     * get the power draw of the hub in sleep mode. This assumes
     * that the hub supports a sleep mode in which the power draw is
     * lower than the power consumed when the hub is actively
     * processing. As a guideline, assume that the hub should be
     * able to enter sleep mode if it knows reliably on completion
     * of some task that the next interrupt/scheduled work item is
     * at least 250 milliseconds later.
     *
     * @return float - sleep power draw in milli watts
     */
    public float getSleepPowerDrawMw() {
        return mSleepPowerDrawMw;
    }

    /**
     * get the peak powe draw of the hub. This is the power consumed
     * by the hub at maximum load.
     *
     * @return float - peak power draw
     */
    public float getPeakPowerDrawMw() {
        return mPeakPowerDrawMw;
    }

    /**
     * get the sensors supported by this hub
     *
     * @return int[] - all the supported sensors on this hub
     *
     * @see ContextHubManager
     */
    public int[] getSupportedSensors() {
        return Arrays.copyOf(mSupportedSensors, mSupportedSensors.length);
    }

    /**
     * get the various memory regions on this hub
     *
     * @return MemoryRegion[] - all the memory regions on this hub
     *
     * @see MemoryRegion
     */
    public MemoryRegion[] getMemoryRegions() {
        return Arrays.copyOf(mMemoryRegions, mMemoryRegions.length);
    }

    /**
     * @return the CHRE platform ID as defined in chre/version.h
     */
    public long getChrePlatformId() {
        return mChrePlatformId;
    }

    /**
     * @return the CHRE API's major version as defined in chre/version.h
     */
    public byte getChreApiMajorVersion() {
        return mChreApiMajorVersion;
    }

    /**
     * @return the CHRE API's minor version as defined in chre/version.h
     */
    public byte getChreApiMinorVersion() {
        return mChreApiMinorVersion;
    }

    /**
     * @return the CHRE patch version as defined in chre/version.h
     */
    public short getChrePatchVersion() {
        return mChrePatchVersion;
    }

    @NonNull
    @Override
    public String toString() {
        String retVal = "";
        retVal += "ID/handle : " + mId;
        retVal += ", Name : " + mName;
        retVal += "\n\tVendor : " + mVendor;
        retVal += ", Toolchain : " + mToolchain;
        retVal += ", Toolchain version: 0x" + Integer.toHexString(mToolchainVersion);
        retVal += "\n\tPlatformVersion : 0x" + Integer.toHexString(mPlatformVersion);
        retVal += ", SwVersion : "
                + Byte.toUnsignedInt(mChreApiMajorVersion) + "." + Byte.toUnsignedInt(
                mChreApiMinorVersion) + "." + Short.toUnsignedInt(mChrePatchVersion);
        retVal += ", CHRE platform ID: 0x" + Long.toHexString(mChrePlatformId);
        retVal += "\n\tPeakMips : " + mPeakMips;
        retVal += ", StoppedPowerDraw : " + mStoppedPowerDrawMw + " mW";
        retVal += ", PeakPowerDraw : " + mPeakPowerDrawMw + " mW";
        retVal += ", MaxPacketLength : " + mMaxPacketLengthBytes + " Bytes";
        retVal += ", SupportsReliableMessage : " + mSupportsReliableMessages;

        return retVal;
    }

    /**
     * Dump the internal state as a ContextHubInfoProto to the given ProtoOutputStream.
     *
     * If the output belongs to a sub message, the caller is responsible for wrapping this function
     * between {@link ProtoOutputStream#start(long)} and {@link ProtoOutputStream#end(long)}.
     *
     * @hide
     */
    public void dump(ProtoOutputStream proto) {
        proto.write(ContextHubInfoProto.ID, mId);
        proto.write(ContextHubInfoProto.NAME, mName);
        proto.write(ContextHubInfoProto.VENDOR, mVendor);
        proto.write(ContextHubInfoProto.TOOLCHAIN, mToolchain);
        proto.write(ContextHubInfoProto.PLATFORM_VERSION, mPlatformVersion);
        proto.write(ContextHubInfoProto.STATIC_SW_VERSION, getStaticSwVersion());
        proto.write(ContextHubInfoProto.TOOLCHAIN_VERSION, mToolchainVersion);
        proto.write(ContextHubInfoProto.CHRE_PLATFORM_ID, mChrePlatformId);
        proto.write(ContextHubInfoProto.PEAK_MIPS, mPeakMips);
        proto.write(ContextHubInfoProto.STOPPED_POWER_DRAW_MW, mStoppedPowerDrawMw);
        proto.write(ContextHubInfoProto.SLEEP_POWER_DRAW_MW, mSleepPowerDrawMw);
        proto.write(ContextHubInfoProto.PEAK_POWER_DRAW_MW, mPeakPowerDrawMw);
        proto.write(ContextHubInfoProto.MAX_PACKET_LENGTH_BYTES, mMaxPacketLengthBytes);
        proto.write(ContextHubInfoProto.SUPPORTS_RELIABLE_MESSAGES,
                mSupportsReliableMessages);
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object == this) {
            return true;
        }

        boolean isEqual = false;
        if (object instanceof ContextHubInfo) {
            ContextHubInfo other = (ContextHubInfo) object;
            isEqual = (other.getId() == mId)
                    && other.getName().equals(mName)
                    && other.getVendor().equals(mVendor)
                    && other.getToolchain().equals(mToolchain)
                    && (other.getToolchainVersion() == mToolchainVersion)
                    && (other.getStaticSwVersion() == getStaticSwVersion())
                    && (other.getChrePlatformId() == mChrePlatformId)
                    && (other.getPeakMips() == mPeakMips)
                    && (other.getStoppedPowerDrawMw() == mStoppedPowerDrawMw)
                    && (other.getSleepPowerDrawMw() == mSleepPowerDrawMw)
                    && (other.getPeakPowerDrawMw() == mPeakPowerDrawMw)
                    && (other.getMaxPacketLengthBytes() == mMaxPacketLengthBytes)
                    && (!Flags.reliableMessage()
                            || (other.supportsReliableMessages() == mSupportsReliableMessages))
                    && Arrays.equals(other.getSupportedSensors(), mSupportedSensors)
                    && Arrays.equals(other.getMemoryRegions(), mMemoryRegions);
        }

        return isEqual;
    }

    private ContextHubInfo(Parcel in) {
        mId = in.readInt();
        mName = in.readString();
        mVendor = in.readString();
        mToolchain = in.readString();
        mPlatformVersion = in.readInt();
        mToolchainVersion = in.readInt();
        mPeakMips = in.readFloat();
        mStoppedPowerDrawMw = in.readFloat();
        mSleepPowerDrawMw = in.readFloat();
        mPeakPowerDrawMw = in.readFloat();
        mMaxPacketLengthBytes = in.readInt();
        mChrePlatformId = in.readLong();
        mChreApiMajorVersion = in.readByte();
        mChreApiMinorVersion = in.readByte();
        mChrePatchVersion = (short) in.readInt();

        int numSupportedSensors = in.readInt();
        mSupportedSensors = new int[numSupportedSensors];
        in.readIntArray(mSupportedSensors);
        mMemoryRegions = in.createTypedArray(MemoryRegion.CREATOR);
        mSupportsReliableMessages = in.readBoolean();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeString(mName);
        out.writeString(mVendor);
        out.writeString(mToolchain);
        out.writeInt(mPlatformVersion);
        out.writeInt(mToolchainVersion);
        out.writeFloat(mPeakMips);
        out.writeFloat(mStoppedPowerDrawMw);
        out.writeFloat(mSleepPowerDrawMw);
        out.writeFloat(mPeakPowerDrawMw);
        out.writeInt(mMaxPacketLengthBytes);
        out.writeLong(mChrePlatformId);
        out.writeByte(mChreApiMajorVersion);
        out.writeByte(mChreApiMinorVersion);
        out.writeInt(mChrePatchVersion);

        out.writeInt(mSupportedSensors.length);
        out.writeIntArray(mSupportedSensors);
        out.writeTypedArray(mMemoryRegions, flags);
        out.writeBoolean(mSupportsReliableMessages);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ContextHubInfo> CREATOR
            = new Parcelable.Creator<ContextHubInfo>() {
        public ContextHubInfo createFromParcel(Parcel in) {
            return new ContextHubInfo(in);
        }

        public ContextHubInfo[] newArray(int size) {
            return new ContextHubInfo[size];
        }
    };
}
