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
package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class to encapsulate HDMI port information. Contains the capability of the ports such as
 * HDMI-CEC, MHL, ARC(Audio Return Channel), and physical address assigned to each port.
 *
 * @hide
 */
@SystemApi
public final class HdmiPortInfo implements Parcelable {
    /** HDMI port type: Input */
    public static final int PORT_INPUT = 0;

    /** HDMI port type: Output */
    public static final int PORT_OUTPUT = 1;

    private final int mId;
    private final int mType;
    private final int mAddress;
    private final boolean mCecSupported;
    private final boolean mArcSupported;
    private final boolean mMhlSupported;

    /**
     * Constructor.
     *
     * @param id identifier assigned to each port. 1 for HDMI port 1
     * @param type HDMI port input/output type
     * @param address physical address of the port
     * @param cec {@code true} if HDMI-CEC is supported on the port
     * @param mhl {@code true} if MHL is supported on the port
     * @param arc {@code true} if audio return channel is supported on the port
     */
    public HdmiPortInfo(int id, int type, int address, boolean cec, boolean mhl, boolean arc) {
        mId = id;
        mType = type;
        mAddress = address;
        mCecSupported = cec;
        mArcSupported = arc;
        mMhlSupported = mhl;
    }

    /**
     * Returns the port id.
     *
     * @return port id
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the port type.
     *
     * @return port type
     */
    public int getType() {
        return mType;
    }

    /**
     * Returns the port address.
     *
     * @return port address
     */
    public int getAddress() {
        return mAddress;
    }

    /**
     * Returns {@code true} if the port supports HDMI-CEC signaling.
     *
     * @return {@code true} if the port supports HDMI-CEC signaling.
     */
    public boolean isCecSupported() {
        return mCecSupported;
    }

    /**
     * Returns {@code true} if the port supports MHL signaling.
     *
     * @return {@code true} if the port supports MHL signaling.
     */
    public boolean isMhlSupported() {
        return mMhlSupported;
    }

    /**
     * Returns {@code true} if the port supports audio return channel.
     *
     * @return {@code true} if the port supports audio return channel
     */
    public boolean isArcSupported() {
        return mArcSupported;
    }

    /**
     * Describes the kinds of special objects contained in this Parcelable's
     * marshalled representation.
     */
    @Override
    public int describeContents() {
        return 0;
    }


    /**
     * A helper class to deserialize {@link HdmiPortInfo} for a parcel.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<HdmiPortInfo> CREATOR =
            new Parcelable.Creator<HdmiPortInfo>() {
                @Override
                public HdmiPortInfo createFromParcel(Parcel source) {
                    int id = source.readInt();
                    int type = source.readInt();
                    int address = source.readInt();
                    boolean cec = (source.readInt() == 1);
                    boolean arc = (source.readInt() == 1);
                    boolean mhl = (source.readInt() == 1);
                    return new HdmiPortInfo(id, type, address, cec, mhl, arc);
                }

                @Override
                public HdmiPortInfo[] newArray(int size) {
                    return new HdmiPortInfo[size];
                }
            };

    /**
     * Serializes this object into a {@link Parcel}.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *        May be 0 or {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mType);
        dest.writeInt(mAddress);
        dest.writeInt(mCecSupported ? 1 : 0);
        dest.writeInt(mArcSupported ? 1 : 0);
        dest.writeInt(mMhlSupported ? 1 : 0);
    }

    @Override
    public String toString() {
        StringBuffer s = new StringBuffer();
        s.append("port_id: ").append(mId).append(", ");
        s.append("address: ").append(String.format("0x%04x", mAddress)).append(", ");
        s.append("cec: ").append(mCecSupported).append(", ");
        s.append("arc: ").append(mArcSupported).append(", ");
        s.append("mhl: ").append(mMhlSupported);
        return s.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HdmiPortInfo)) {
            return false;
        }
        final HdmiPortInfo other = (HdmiPortInfo) o;
        return mId == other.mId && mType == other.mType && mAddress == other.mAddress
                && mCecSupported == other.mCecSupported && mArcSupported == other.mArcSupported
                && mMhlSupported == other.mMhlSupported;
    }
}
