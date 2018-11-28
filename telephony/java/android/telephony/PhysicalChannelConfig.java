/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.TelephonyManager.NetworkType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * @hide
 */
public final class PhysicalChannelConfig implements Parcelable {

    // TODO(b/72993578) consolidate these enums in a central location.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CONNECTION_PRIMARY_SERVING, CONNECTION_SECONDARY_SERVING, CONNECTION_UNKNOWN})
    public @interface ConnectionStatus {}

    /**
     * UE has connection to cell for signalling and possibly data (3GPP 36.331, 25.331).
     */
    public static final int CONNECTION_PRIMARY_SERVING = 1;

    /**
     * UE has connection to cell for data (3GPP 36.331, 25.331).
     */
    public static final int CONNECTION_SECONDARY_SERVING = 2;

    /** Connection status is unknown. */
    public static final int CONNECTION_UNKNOWN = Integer.MAX_VALUE;

    /**
     * Connection status of the cell.
     *
     * <p>One of {@link #CONNECTION_PRIMARY_SERVING}, {@link #CONNECTION_SECONDARY_SERVING}.
     */
    @ConnectionStatus
    private int mCellConnectionStatus;

    /**
     * Cell bandwidth, in kHz.
     */
    private int mCellBandwidthDownlinkKhz;

    /**
     * The radio technology for this physical channel.
     */
    @NetworkType
    private int mRat;

    /**
     * The rough frequency range for this physical channel.
     */
    @ServiceState.FrequencyRange
    private int mFrequencyRange;

    /**
     * The absolute radio frequency channel number, {@link Integer#MAX_VALUE} if unknown.
     */
    private int mChannelNumber;

    /**
     * A list of data calls mapped to this physical channel. An empty list means the physical
     * channel has no data call mapped to it.
     */
    private int[] mContextIds;

    /**
     * The physical cell identifier for this cell - PCI, PSC, {@link Integer#MAX_VALUE} if known.
     */
    private int mPhysicalCellId;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCellConnectionStatus);
        dest.writeInt(mCellBandwidthDownlinkKhz);
        dest.writeInt(mRat);
        dest.writeInt(mChannelNumber);
        dest.writeInt(mFrequencyRange);
        dest.writeIntArray(mContextIds);
        dest.writeInt(mPhysicalCellId);
    }

    /**
     * @return Cell bandwidth, in kHz
     */
    public int getCellBandwidthDownlink() {
        return mCellBandwidthDownlinkKhz;
    }

    /**
     * Get the list of data call ids mapped to this physical channel. This list is sorted into
     * ascending numerical order. Each id in this list must match the id in
     * {@link com.android.internal.telephony.dataconnection.DataConnection}. An empty list means the
     * physical channel has no data call mapped to it.
     *
     * @return an integer list indicates the data call ids.
     */
    public int[] getContextIds() {
        return mContextIds;
    }

    /**
     * @return the rough frequency range for this physical channel.
     * @see {@link ServiceState#FREQUENCY_RANGE_LOW}
     * @see {@link ServiceState#FREQUENCY_RANGE_MID}
     * @see {@link ServiceState#FREQUENCY_RANGE_HIGH}
     * @see {@link ServiceState#FREQUENCY_RANGE_MMWAVE}
     */
    @ServiceState.FrequencyRange
    public int getFrequencyRange() {
        return mFrequencyRange;
    }

    /**
     * @return the absolute radio frequency channel number for this physical channel,
     * {@link Integer#MAX_VALUE} if unknown.
     */
    public int getChannelNumber() {
        return mChannelNumber;
    }

    /**
     * In UTRAN, this value is primary scrambling code. The range is [0, 511].
     * Reference: 3GPP TS 25.213 section 5.2.2.
     *
     * In EUTRAN, this value is physical layer cell identity. The range is [0, 503].
     * Reference: 3GPP TS 36.211 section 6.11.
     *
     * In 5G RAN, this value is physical layer cell identity. The range is [0, 1008].
     * Reference: 3GPP TS 38.211 section 7.4.2.1.
     *
     * @return the physical cell identifier for this cell, {@link Integer#MAX_VALUE} if unknown.
     */
    public int getPhysicalCellId() {
        return mPhysicalCellId;
    }

    /**The radio technology for this physical channel. */
    @NetworkType
    public int getRat() {
        return mRat;
    }

    /**
     * Gets the connection status of the cell.
     *
     * @see #CONNECTION_PRIMARY_SERVING
     * @see #CONNECTION_SECONDARY_SERVING
     * @see #CONNECTION_UNKNOWN
     *
     * @return Connection status of the cell
     */
    @ConnectionStatus
    public int getConnectionStatus() {
        return mCellConnectionStatus;
    }

    /** @return String representation of the connection status */
    private String getConnectionStatusString() {
        switch(mCellConnectionStatus) {
            case CONNECTION_PRIMARY_SERVING:
                return "PrimaryServing";
            case CONNECTION_SECONDARY_SERVING:
                return "SecondaryServing";
            case CONNECTION_UNKNOWN:
                return "Unknown";
            default:
                return "Invalid(" + mCellConnectionStatus + ")";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PhysicalChannelConfig)) {
            return false;
        }

        PhysicalChannelConfig config = (PhysicalChannelConfig) o;
        return mCellConnectionStatus == config.mCellConnectionStatus
                && mCellBandwidthDownlinkKhz == config.mCellBandwidthDownlinkKhz
                && mRat == config.mRat
                && mFrequencyRange == config.mFrequencyRange
                && mChannelNumber == config.mChannelNumber
                && mPhysicalCellId == config.mPhysicalCellId
                && Arrays.equals(mContextIds, config.mContextIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mCellConnectionStatus, mCellBandwidthDownlinkKhz, mRat, mFrequencyRange,
                mChannelNumber, mPhysicalCellId, mContextIds);
    }

    public static final Parcelable.Creator<PhysicalChannelConfig> CREATOR =
        new Parcelable.Creator<PhysicalChannelConfig>() {
            public PhysicalChannelConfig createFromParcel(Parcel in) {
                return new PhysicalChannelConfig(in);
            }

            public PhysicalChannelConfig[] newArray(int size) {
                return new PhysicalChannelConfig[size];
            }
        };

    @Override
    public String toString() {
        return new StringBuilder()
                .append("{mConnectionStatus=")
                .append(getConnectionStatusString())
                .append(",mCellBandwidthDownlinkKhz=")
                .append(mCellBandwidthDownlinkKhz)
                .append(",mRat=")
                .append(mRat)
                .append(",mFrequencyRange=")
                .append(mFrequencyRange)
                .append(",mChannelNumber=")
                .append(mChannelNumber)
                .append(",mContextIds=")
                .append(mContextIds.toString())
                .append(",mPhysicalCellId=")
                .append(mPhysicalCellId)
                .append("}")
                .toString();
    }

    private PhysicalChannelConfig(Parcel in) {
        mCellConnectionStatus = in.readInt();
        mCellBandwidthDownlinkKhz = in.readInt();
        mRat = in.readInt();
        mChannelNumber = in.readInt();
        mFrequencyRange = in.readInt();
        mContextIds = in.createIntArray();
        mPhysicalCellId = in.readInt();
    }

    private PhysicalChannelConfig(Builder builder) {
        mCellConnectionStatus = builder.mCellConnectionStatus;
        mCellBandwidthDownlinkKhz = builder.mCellBandwidthDownlinkKhz;
        mRat = builder.mRat;
        mChannelNumber = builder.mChannelNumber;
        mFrequencyRange = builder.mFrequencyRange;
        mContextIds = builder.mContextIds;
        mPhysicalCellId = builder.mPhysicalCellId;
    }

    /** The builder of {@code PhysicalChannelConfig}. */
    public static final class Builder {
        private int mRat;
        private int mFrequencyRange;
        private int mChannelNumber;
        private int mCellBandwidthDownlinkKhz;
        private int mCellConnectionStatus;
        private int[] mContextIds;
        private int mPhysicalCellId;

        /** @hide */
        public Builder() {
            mRat = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
            mFrequencyRange = ServiceState.FREQUENCY_RANGE_UNKNOWN;
            mChannelNumber = Integer.MAX_VALUE;
            mCellBandwidthDownlinkKhz = 0;
            mCellConnectionStatus = CONNECTION_UNKNOWN;
            mContextIds = new int[0];
            mPhysicalCellId = Integer.MAX_VALUE;
        }

        /** @hide */
        public PhysicalChannelConfig build() {
            return new PhysicalChannelConfig(this);
        }

        /** @hide */
        public Builder setRat(int rat) {
            this.mRat = rat;
            return this;
        }

        /** @hide */
        public Builder setFrequencyRange(int frequencyRange) {
            this.mFrequencyRange = frequencyRange;
            return this;
        }

        /** @hide */
        public Builder setChannelNumber(int channelNumber) {
            this.mChannelNumber = channelNumber;
            return this;
        }

        /** @hide */
        public Builder setCellBandwidthDownlinkKhz(int cellBandwidthDownlinkKhz) {
            this.mCellBandwidthDownlinkKhz = cellBandwidthDownlinkKhz;
            return this;
        }

        /** @hide */
        public Builder setCellConnectionStatus(int connectionStatus) {
            this.mCellConnectionStatus = connectionStatus;
            return this;
        }

        /** @hide */
        public Builder setContextIds(int[] contextIds) {
            if (contextIds != null) Arrays.sort(contextIds);
            this.mContextIds = contextIds;
            return this;
        }

        /** @hide */
        public Builder setPhysicalCellId(int physicalCellId) {
            this.mPhysicalCellId = physicalCellId;
            return this;
        }
    }
}
