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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.IntRange;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Encapsulates HDMI port information. Contains the capability of the ports such as
 * HDMI-CEC, MHL, ARC(Audio Return Channel), eARC and physical address assigned to each port.
 *
 * @hide
 */
@SystemApi
public final class HdmiPortInfo implements Parcelable {
    /** HDMI port type: Input */
    public static final int PORT_INPUT = 0;

    /** HDMI port type: Output */
    public static final int PORT_OUTPUT = 1;

    /**
     * @hide
     *
     * @see HdmiPortInfo#getType()
     */
    @IntDef(prefix = { "PORT_" }, value = {
            PORT_INPUT,
            PORT_OUTPUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PortType {}

    private final int mId;
    private final int mType;
    private final int mAddress;
    private final boolean mCecSupported;
    private final boolean mArcSupported;
    private final boolean mEarcSupported;
    private final boolean mMhlSupported;

    /**
     * Constructor.
     *
     * @param id identifier assigned to each port. 1 for HDMI OUT port 1
     * @param type HDMI port input/output type
     * @param address physical address of the port
     * @param cec {@code true} if HDMI-CEC is supported on the port
     * @param mhl {@code true} if MHL is supported on the port
     * @param arc {@code true} if audio return channel is supported on the port
     *
     * @deprecated use {@link Builder()} instead
     */
    @Deprecated
    public HdmiPortInfo(int id, @PortType int type,
            @IntRange(from = 0) int address, boolean cec, boolean mhl, boolean arc) {
        mId = id;
        mType = type;
        mAddress = address;
        mCecSupported = cec;
        mArcSupported = arc;
        mEarcSupported = false;
        mMhlSupported = mhl;
    }

    /**
     * Converts an instance to a builder
     *
     * @hide
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    private HdmiPortInfo(Builder builder) {
        this.mId = builder.mId;
        this.mType = builder.mType;
        this.mAddress = builder.mAddress;
        this.mCecSupported = builder.mCecSupported;
        this.mArcSupported = builder.mArcSupported;
        this.mEarcSupported = builder.mEarcSupported;
        this.mMhlSupported = builder.mMhlSupported;
    }

    /**
     * Returns the port id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the port type.
     */
    @PortType
    public int getType() {
        return mType;
    }

    /**
     * Returns the port address.
     */
    @IntRange(from = 0)
    public int getAddress() {
        return mAddress;
    }

    /**
     * Returns {@code true} if the port supports HDMI-CEC signaling.
     */
    public boolean isCecSupported() {
        return mCecSupported;
    }

    /**
     * Returns {@code true} if the port supports MHL signaling.
     */
    public boolean isMhlSupported() {
        return mMhlSupported;
    }

    /**
     * Returns {@code true} if the port supports audio return channel.
     */
    public boolean isArcSupported() {
        return mArcSupported;
    }

    /**
     * Returns {@code true} if the port supports eARC.
     */
    public boolean isEarcSupported() {
        return mEarcSupported;
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
                    boolean earc = (source.readInt() == 1);
                    return new Builder(id, type, address)
                            .setCecSupported(cec)
                            .setArcSupported(arc)
                            .setEarcSupported(earc)
                            .setMhlSupported(mhl)
                            .build();
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
     * @hide
     */
    @SystemApi
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mType);
        dest.writeInt(mAddress);
        dest.writeInt(mCecSupported ? 1 : 0);
        dest.writeInt(mArcSupported ? 1 : 0);
        dest.writeInt(mMhlSupported ? 1 : 0);
        dest.writeInt(mEarcSupported ? 1 : 0);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("port_id: ").append(mId).append(", ");
        s.append("type: ").append((mType == PORT_INPUT) ? "HDMI_IN" : "HDMI_OUT").append(", ");
        s.append("address: ").append(String.format("0x%04x", mAddress)).append(", ");
        s.append("cec: ").append(mCecSupported).append(", ");
        s.append("arc: ").append(mArcSupported).append(", ");
        s.append("mhl: ").append(mMhlSupported).append(", ");
        s.append("earc: ").append(mEarcSupported);
        return s.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof HdmiPortInfo)) {
            return false;
        }
        final HdmiPortInfo other = (HdmiPortInfo) o;
        return mId == other.mId && mType == other.mType && mAddress == other.mAddress
                && mCecSupported == other.mCecSupported && mArcSupported == other.mArcSupported
                && mMhlSupported == other.mMhlSupported && mEarcSupported == other.mEarcSupported;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                mId, mType, mAddress, mCecSupported, mArcSupported, mMhlSupported, mEarcSupported);
    }

    /**
     * Builder for {@link HdmiPortInfo} instances.
     */
    public static final class Builder {
        // Required parameters
        private int mId;
        private int mType;
        private int mAddress;

        // Optional parameters that are set to false by default.
        private boolean mCecSupported;
        private boolean mArcSupported;
        private boolean mEarcSupported;
        private boolean mMhlSupported;

        /**
         * Constructor
         *
         * @param id      identifier assigned to each port. 1 for HDMI OUT port 1
         * @param type    HDMI port input/output type
         * @param address physical address of the port
         * @throws IllegalArgumentException if the parameters are invalid
         */
        public Builder(int id, @PortType int type, @IntRange(from = 0) int address) {
            if (type != PORT_INPUT && type != PORT_OUTPUT) {
                throw new IllegalArgumentException(
                        "type should be " + PORT_INPUT + " or " + PORT_OUTPUT + ".");
            }
            if (address < 0) {
                throw new IllegalArgumentException("address should be positive.");
            }
            mId = id;
            mType = type;
            mAddress = address;
        }

        private Builder(@NonNull HdmiPortInfo hdmiPortInfo) {
            mId = hdmiPortInfo.mId;
            mType = hdmiPortInfo.mType;
            mAddress = hdmiPortInfo.mAddress;
            mCecSupported = hdmiPortInfo.mCecSupported;
            mArcSupported = hdmiPortInfo.mArcSupported;
            mEarcSupported = hdmiPortInfo.mEarcSupported;
            mMhlSupported = hdmiPortInfo.mMhlSupported;
        }

        /**
         * Create a new {@link HdmiPortInfo} object.
         */
        @NonNull
        public HdmiPortInfo build() {
            return new HdmiPortInfo(this);
        }

        /**
         * Sets the value for whether the port supports HDMI-CEC signaling.
         */
        @NonNull
        public Builder setCecSupported(boolean supported) {
            mCecSupported = supported;
            return this;
        }

        /**
         * Sets the value for whether the port supports audio return channel.
         */
        @NonNull
        public Builder setArcSupported(boolean supported) {
            mArcSupported = supported;
            return this;
        }

        /**
         * Sets the value for whether the port supports eARC.
         */
        @NonNull
        public Builder setEarcSupported(boolean supported) {
            mEarcSupported = supported;
            return this;
        }

        /**
         * Sets the value for whether the port supports MHL signaling.
         */
        @NonNull
        public Builder setMhlSupported(boolean supported) {
            mMhlSupported = supported;
            return this;
        }
    }
}
