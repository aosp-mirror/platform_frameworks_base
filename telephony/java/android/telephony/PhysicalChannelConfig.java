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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetworkType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

public final class PhysicalChannelConfig implements Parcelable {

    // TODO(b/72993578) consolidate these enums in a central location.
    /** @hide */
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
    public static final int CONNECTION_UNKNOWN = -1;

    /** Channel number is unknown. */
    public static final int CHANNEL_NUMBER_UNKNOWN = Integer.MAX_VALUE;

    /** Physical Cell Id is unknown. */
    public static final int PHYSICAL_CELL_ID_UNKNOWN = -1;

    /** Physical Cell Id's maximum value is 1007. */
    public static final int PHYSICAL_CELL_ID_MAXIMUM_VALUE = 1007;

    /** Cell bandwidth is unknown. */
    public static final int CELL_BANDWIDTH_UNKNOWN = 0;

    /** The frequency is unknown. */
    public static final int FREQUENCY_UNKNOWN = -1;

    /** The band is unknown. */
    public static final int BAND_UNKNOWN = 0;

    /**
     * Connection status of the cell.
     *
     * <p>One of {@link #CONNECTION_PRIMARY_SERVING}, {@link #CONNECTION_SECONDARY_SERVING}.
     */
    @ConnectionStatus
    private int mCellConnectionStatus;

    /**
     * Downlink cell bandwidth, in kHz.
     */
    private int mCellBandwidthDownlinkKhz;

    /**
     * Uplink cell bandwidth, in kHz.
     */
    private int mCellBandwidthUplinkKhz;

    /**
     * The radio technology for this physical channel.
     */
    @NetworkType
    private int mNetworkType;

    /**
     * The rough frequency range for this physical channel.
     */
    @ServiceState.FrequencyRange
    private int mFrequencyRange;

    /**
     * The frequency of Downlink.
     */
    private int mDownlinkFrequency;

    /**
     * The frequency of Uplink.
     */
    private int mUplinkFrequency;

    /**
     * Downlink Absolute Radio Frequency Channel Number
     */
    private int mDownlinkChannelNumber;

    /**
     * Uplink Absolute Radio Frequency Channel Number
     */
    private int mUplinkChannelNumber;

    /**
     * A list of data calls mapped to this physical channel. An empty list means the physical
     * channel has no data call mapped to it.
     */
    private int[] mContextIds;

    /**
     * The physical cell identifier for this cell - PCI, PSC, {@link #PHYSICAL_CELL_ID_UNKNOWN}
     */
    private int mPhysicalCellId;

    /**
     * This is the band which is being used.
     */
    private int mBand;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mCellConnectionStatus);
        dest.writeInt(mCellBandwidthDownlinkKhz);
        dest.writeInt(mCellBandwidthUplinkKhz);
        dest.writeInt(mNetworkType);
        dest.writeInt(mDownlinkChannelNumber);
        dest.writeInt(mUplinkChannelNumber);
        dest.writeInt(mFrequencyRange);
        dest.writeIntArray(mContextIds);
        dest.writeInt(mPhysicalCellId);
        dest.writeInt(mBand);
    }

    /**
     * @return Downlink cell bandwidth in kHz, {@link #CELL_BANDWIDTH_UNKNOWN} if unknown.
     */
    @IntRange(from = 1)
    public int getCellBandwidthDownlinkKhz() {
        return mCellBandwidthDownlinkKhz;
    }

    /**
     * @return Uplink cell bandwidth in kHz, {@link #CELL_BANDWIDTH_UNKNOWN} if unknown.
     */
    @IntRange(from = 1)
    public int getCellBandwidthUplinkKhz() {
        return mCellBandwidthUplinkKhz;
    }

    /**
     * Get the list of data call ids mapped to this physical channel. This list is sorted into
     * ascending numerical order. Each id in this list must match the id in
     * {@link com.android.internal.telephony.dataconnection.DataConnection}. An empty list means the
     * physical channel has no data call mapped to it.
     *
     * @return an integer list indicates the data call ids,
     * @hide
     */
    public int[] getContextIds() {
        return mContextIds;
    }

    /**
     * @return the absolute radio frequency channel number for this physical channel,
     * {@link #CHANNEL_NUMBER_UNKNOWN} if unknown.
     * @deprecated Use {@link #getDownlinkChannelNumber()} to get the channel number.
     */
    @Deprecated
    public int getChannelNumber() {
        return getDownlinkChannelNumber();
    }

    /**
     * @return the rough frequency range for this physical channel,
     * {@link ServiceState#FREQUENCY_RANGE_UNKNOWN} if unknown.
     * @see {@link ServiceState#FREQUENCY_RANGE_LOW}
     * @see {@link ServiceState#FREQUENCY_RANGE_MID}
     * @see {@link ServiceState#FREQUENCY_RANGE_HIGH}
     * @see {@link ServiceState#FREQUENCY_RANGE_MMWAVE}
     * @hide
     */
    @ServiceState.FrequencyRange
    public int getFrequencyRange() {
        return mFrequencyRange;
    }

    /**
     * @return Downlink Absolute Radio Frequency Channel Number,
     * {@link #CHANNEL_NUMBER_UNKNOWN} if unknown.
     */
    @IntRange(from = 0)
    public int getDownlinkChannelNumber() {
        return mDownlinkChannelNumber;
    }

    /**
     * @return Uplink Absolute Radio Frequency Channel Number,
     * {@link #CHANNEL_NUMBER_UNKNOWN} if unknown.
     */
    @IntRange(from = 0)
    public int getUplinkChannelNumber() {
        return mUplinkChannelNumber;
    }

    /**
     * The valid bands are {@link AccessNetworkConstants.GeranBand},
     * {@link AccessNetworkConstants.UtranBand}, {@link AccessNetworkConstants.EutranBand} and
     * {@link AccessNetworkConstants.NgranBands}.
     *
     * @return the frequency band, {@link #BAND_UNKNOWN} if unknown. */
    @IntRange(from = 1, to = 261)
    public int getBand() {
        return mBand;
    }

    /**
     * @return The downlink frequency in kHz, {@link #FREQUENCY_UNKNOWN} if unknown.
     */
    @IntRange(from = 0)
    public int getDownlinkFrequencyKhz() {
        return mDownlinkFrequency;
    }

    /**
     * @return The uplink frequency in kHz, {@link #FREQUENCY_UNKNOWN} if unknown.
     */
    @IntRange(from = 0)
    public int getUplinkFrequencyKhz() {
        return mUplinkFrequency;
    }

    /**
     * In UTRAN, this value is primary scrambling code. The range is [0, 511].
     * Reference: 3GPP TS 25.213 section 5.2.2.
     *
     * In EUTRAN, this value is physical layer cell identity. The range is [0, 503].
     * Reference: 3GPP TS 36.211 section 6.11.
     *
     * In 5G RAN, this value is physical layer cell identity. The range is [0, 1007].
     * Reference: 3GPP TS 38.211 section 7.4.2.1.
     *
     * @return the physical cell identifier for this cell, {@link #PHYSICAL_CELL_ID_UNKNOWN}
     * if {@link android.telephony.CellInfo#UNAVAILABLE}.
     */
    @IntRange(from = 0, to = 1007)
    public int getPhysicalCellId() {
        return mPhysicalCellId;
    }

    /**
     * @return The network type for this physical channel,
     * {@link TelephonyManager#NETWORK_TYPE_UNKNOWN} if unknown.
     */
    @NetworkType
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * Gets the connection status of the cell.
     *
     * @see #CONNECTION_PRIMARY_SERVING
     * @see #CONNECTION_SECONDARY_SERVING
     * @see #CONNECTION_UNKNOWN
     *
     * @return Connection status of the cell, {@link #CONNECTION_UNKNOWN} if unknown.
     */
    @ConnectionStatus
    public int getConnectionStatus() {
        return mCellConnectionStatus;
    }

    /**
     * @return String representation of the connection status
     * @hide
     */
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

    private void setDownlinkFrequency() {
        switch (mNetworkType) {
            case TelephonyManager.NETWORK_TYPE_NR:
                mDownlinkFrequency = AccessNetworkUtils.getFrequencyFromNrArfcn(
                        mDownlinkChannelNumber);
                break;
            case TelephonyManager.NETWORK_TYPE_LTE:
                mDownlinkFrequency = AccessNetworkUtils.getFrequencyFromEarfcn(
                        mBand, mDownlinkChannelNumber, false);
                break;
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                mDownlinkFrequency = AccessNetworkUtils.getFrequencyFromUarfcn(
                        mBand, mDownlinkChannelNumber, false);
                break;
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GSM:
                mDownlinkFrequency = AccessNetworkUtils.getFrequencyFromArfcn(
                        mBand, mDownlinkChannelNumber, false);
                break;
        }
    }

    private void setUplinkFrequency() {
        switch (mNetworkType){
            case TelephonyManager.NETWORK_TYPE_NR:
                mUplinkFrequency = mDownlinkFrequency;
                break;
            case TelephonyManager.NETWORK_TYPE_LTE:
                mUplinkFrequency = AccessNetworkUtils.getFrequencyFromEarfcn(
                        mBand, mUplinkChannelNumber, true);
                break;
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                mUplinkFrequency = AccessNetworkUtils.getFrequencyFromUarfcn(
                        mBand, mUplinkChannelNumber, true);
                break;
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GSM:
                mUplinkFrequency = AccessNetworkUtils.getFrequencyFromArfcn(
                        mBand, mUplinkChannelNumber, true);
                break;
        }
    }

    private void setFrequencyRange() {
        if (mFrequencyRange != ServiceState.FREQUENCY_RANGE_UNKNOWN) {
            return;
        }

        switch (mNetworkType) {
            case TelephonyManager.NETWORK_TYPE_NR:
                mFrequencyRange = AccessNetworkUtils.getFrequencyRangeGroupFromNrBand(mBand);
                break;
            case TelephonyManager.NETWORK_TYPE_LTE:
                mFrequencyRange = AccessNetworkUtils.getFrequencyRangeGroupFromEutranBand(mBand);
                break;
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                mFrequencyRange = AccessNetworkUtils.getFrequencyRangeGroupFromUtranBand(mBand);
                break;
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GSM:
                mFrequencyRange = AccessNetworkUtils.getFrequencyRangeGroupFromGeranBand(mBand);
                break;
            default:
                mFrequencyRange = ServiceState.FREQUENCY_RANGE_UNKNOWN;
                break;
        }

        if (mFrequencyRange == ServiceState.FREQUENCY_RANGE_UNKNOWN) {
            mFrequencyRange = AccessNetworkUtils.getFrequencyRangeFromArfcn(
                    mDownlinkFrequency);
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
                && mCellBandwidthUplinkKhz == config.mCellBandwidthUplinkKhz
                && mNetworkType == config.mNetworkType
                && mFrequencyRange == config.mFrequencyRange
                && mDownlinkChannelNumber == config.mDownlinkChannelNumber
                && mUplinkChannelNumber == config.mUplinkChannelNumber
                && mPhysicalCellId == config.mPhysicalCellId
                && Arrays.equals(mContextIds, config.mContextIds)
                && mBand == config.mBand
                && mDownlinkFrequency == config.mDownlinkFrequency
                && mUplinkFrequency == config.mUplinkFrequency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mCellConnectionStatus, mCellBandwidthDownlinkKhz, mCellBandwidthUplinkKhz,
                mNetworkType, mFrequencyRange, mDownlinkChannelNumber, mUplinkChannelNumber,
                mContextIds, mPhysicalCellId, mBand, mDownlinkFrequency, mUplinkFrequency);
    }

    public static final
    @android.annotation.NonNull Parcelable.Creator<PhysicalChannelConfig> CREATOR =
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
                .append(",mCellBandwidthUplinkKhz=")
                .append(mCellBandwidthUplinkKhz)
                .append(",mNetworkType=")
                .append(TelephonyManager.getNetworkTypeName(mNetworkType))
                .append(",mFrequencyRange=")
                .append(ServiceState.frequencyRangeToString(mFrequencyRange))
                .append(",mDownlinkChannelNumber=")
                .append(mDownlinkChannelNumber)
                .append(",mUplinkChannelNumber=")
                .append(mUplinkChannelNumber)
                .append(",mContextIds=")
                .append(Arrays.toString(mContextIds))
                .append(",mPhysicalCellId=")
                .append(mPhysicalCellId)
                .append(",mBand=")
                .append(mBand)
                .append(",mDownlinkFrequency=")
                .append(mDownlinkFrequency)
                .append(",mUplinkFrequency=")
                .append(mUplinkFrequency)
                .append("}")
                .toString();
    }

    private PhysicalChannelConfig(Parcel in) {
        mCellConnectionStatus = in.readInt();
        mCellBandwidthDownlinkKhz = in.readInt();
        mCellBandwidthUplinkKhz = in.readInt();
        mNetworkType = in.readInt();
        mDownlinkChannelNumber = in.readInt();
        mUplinkChannelNumber = in.readInt();
        mFrequencyRange = in.readInt();
        mContextIds = in.createIntArray();
        mPhysicalCellId = in.readInt();
        mBand = in.readInt();
        if (mBand > BAND_UNKNOWN) {
            setDownlinkFrequency();
            setUplinkFrequency();
            setFrequencyRange();
        }
    }

    private PhysicalChannelConfig(Builder builder) {
        mCellConnectionStatus = builder.mCellConnectionStatus;
        mCellBandwidthDownlinkKhz = builder.mCellBandwidthDownlinkKhz;
        mCellBandwidthUplinkKhz = builder.mCellBandwidthUplinkKhz;
        mNetworkType = builder.mNetworkType;
        mDownlinkChannelNumber = builder.mDownlinkChannelNumber;
        mUplinkChannelNumber = builder.mUplinkChannelNumber;
        mFrequencyRange = builder.mFrequencyRange;
        mContextIds = builder.mContextIds;
        mPhysicalCellId = builder.mPhysicalCellId;
        mBand = builder.mBand;
        if (mBand > BAND_UNKNOWN) {
            setDownlinkFrequency();
            setUplinkFrequency();
            setFrequencyRange();
        }
    }

    /**
     * The builder of {@code PhysicalChannelConfig}.
     * @hide
     */
    public static final class Builder {
        private int mNetworkType;
        private int mFrequencyRange;
        private int mDownlinkChannelNumber;
        private int mUplinkChannelNumber;
        private int mCellBandwidthDownlinkKhz;
        private int mCellBandwidthUplinkKhz;
        private int mCellConnectionStatus;
        private int[] mContextIds;
        private int mPhysicalCellId;
        private int mBand;

        public Builder() {
            mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mFrequencyRange = ServiceState.FREQUENCY_RANGE_UNKNOWN;
            mDownlinkChannelNumber = CHANNEL_NUMBER_UNKNOWN;
            mUplinkChannelNumber = CHANNEL_NUMBER_UNKNOWN;
            mCellBandwidthDownlinkKhz = CELL_BANDWIDTH_UNKNOWN;
            mCellBandwidthUplinkKhz = CELL_BANDWIDTH_UNKNOWN;
            mCellConnectionStatus = CONNECTION_UNKNOWN;
            mContextIds = new int[0];
            mPhysicalCellId = PHYSICAL_CELL_ID_UNKNOWN;
            mBand = BAND_UNKNOWN;
        }

        public PhysicalChannelConfig build() {
            return new PhysicalChannelConfig(this);
        }

        public @NonNull Builder setNetworkType(@NetworkType int networkType) {
            if (!TelephonyManager.isNetworkTypeValid(networkType)) {
                throw new IllegalArgumentException("Network type: " + networkType + " is invalid.");
            }
            mNetworkType = networkType;
            return this;
        }

        public @NonNull Builder setFrequencyRange(int frequencyRange) {
            if (!ServiceState.isFrequencyRangeValid(frequencyRange)) {
                throw new IllegalArgumentException("Frequency range: " + frequencyRange +
                        " is invalid.");
            }
            mFrequencyRange = frequencyRange;
            return this;
        }

        public @NonNull Builder setDownlinkChannelNumber(int downlinkChannelNumber) {
            mDownlinkChannelNumber = downlinkChannelNumber;
            return this;
        }

        public @NonNull Builder setUplinkChannelNumber(int uplinkChannelNumber) {
            mUplinkChannelNumber = uplinkChannelNumber;
            return this;
        }

        public @NonNull Builder setCellBandwidthDownlinkKhz(int cellBandwidthDownlinkKhz) {
            if (cellBandwidthDownlinkKhz < CELL_BANDWIDTH_UNKNOWN) {
                throw new IllegalArgumentException("Cell downlink bandwidth(kHz): " +
                        cellBandwidthDownlinkKhz + " is invalid.");
            }
            mCellBandwidthDownlinkKhz = cellBandwidthDownlinkKhz;
            return this;
        }

        public @NonNull Builder setCellBandwidthUplinkKhz(int cellBandwidthUplinkKhz) {
            if (cellBandwidthUplinkKhz < CELL_BANDWIDTH_UNKNOWN) {
                throw new IllegalArgumentException("Cell uplink bandwidth(kHz): "+
                        cellBandwidthUplinkKhz +" is invalid.");
            }
            mCellBandwidthUplinkKhz = cellBandwidthUplinkKhz;
            return this;
        }

        public @NonNull Builder setCellConnectionStatus(int connectionStatus) {
            mCellConnectionStatus = connectionStatus;
            return this;
        }

        public @NonNull Builder setContextIds(int[] contextIds) {
            if (contextIds != null) Arrays.sort(contextIds);
            mContextIds = contextIds;
            return this;
        }

        public @NonNull Builder setPhysicalCellId(int physicalCellId) {
            if (physicalCellId > PHYSICAL_CELL_ID_MAXIMUM_VALUE) {
                throw new IllegalArgumentException("Physical cell Id: " + physicalCellId +
                        " is over limit.");
            }
            mPhysicalCellId = physicalCellId;
            return this;
        }

        public @NonNull Builder setBand(int band) {
            if (band <= BAND_UNKNOWN) {
                throw new IllegalArgumentException("Band: " + band +
                        " is invalid.");
            }
            mBand = band;
            return this;
        }
    }
}
