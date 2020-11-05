/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An object used when requesting to open a new {@link RangingSession}.
 * <p>Use {@link RangingParams.Builder} to create an instance of this class.
 *
 *  @hide
 */
public final class RangingParams implements Parcelable {
    private final boolean mIsInitiator;
    private final boolean mIsController;
    private final Duration mSamplePeriod;
    private final UwbAddress mLocalDeviceAddress;
    private final List<UwbAddress> mRemoteDeviceAddresses;
    private final int mChannelNumber;
    private final int mTransmitPreambleCodeIndex;
    private final int mReceivePreambleCodeIndex;
    private final int mStsPhyPacketType;
    private final PersistableBundle mSpecificationParameters;

    private RangingParams(boolean isInitiator, boolean isController,
            @NonNull Duration samplingPeriod, @NonNull UwbAddress localDeviceAddress,
            @NonNull List<UwbAddress> remoteDeviceAddresses, int channelNumber,
            int transmitPreambleCodeIndex, int receivePreambleCodeIndex,
            @StsPhyPacketType int stsPhyPacketType,
            @NonNull PersistableBundle specificationParameters) {
        mIsInitiator = isInitiator;
        mIsController = isController;
        mSamplePeriod = samplingPeriod;
        mLocalDeviceAddress = localDeviceAddress;
        mRemoteDeviceAddresses = remoteDeviceAddresses;
        mChannelNumber = channelNumber;
        mTransmitPreambleCodeIndex = transmitPreambleCodeIndex;
        mReceivePreambleCodeIndex = receivePreambleCodeIndex;
        mStsPhyPacketType = stsPhyPacketType;
        mSpecificationParameters = specificationParameters;
    }

    /**
     * Get if the local device is the initiator
     *
     * @return true if the device is the initiator
     */
    public boolean isInitiator() {
        return mIsInitiator;
    }

    /**
     * Get if the local device is the controller
     *
     * @return true if the device is the controller
     */
    public boolean isController() {
        return mIsController;
    }

    /**
     * The desired amount of time between two adjacent samples of measurement
     *
     * @return the ranging sample period
     */
    @NonNull
    public Duration getSamplingPeriod() {
        return mSamplePeriod;
    }

    /**
     * Local device's {@link UwbAddress}
     *
     * <p>Simultaneous {@link RangingSession}s on the same device can have different results for
     * {@link #getLocalDeviceAddress()}.
     *
     * @return the local device's {@link UwbAddress}
     */
    @NonNull
    public UwbAddress getLocalDeviceAddress() {
        return mLocalDeviceAddress;
    }

    /**
     * Gets a list of all remote device's {@link UwbAddress}
     *
     * @return a {@link List} of {@link UwbAddress} representing the remote devices
     */
    @NonNull
    public List<UwbAddress> getRemoteDeviceAddresses() {
        return mRemoteDeviceAddresses;
    }

    /**
     * Channel number used between this device pair as defined by 802.15.4z
     *
     * Range: -1, 0-15
     *
     * @return the channel to use
     */
    public int getChannelNumber() {
        return mChannelNumber;
    }

    /**
     * Preamble index used between this device pair as defined by 802.15.4z
     *
     * Range: 0, 0-32
     *
     * @return the preamble index to use for transmitting
     */
    public int getTxPreambleIndex() {
        return mTransmitPreambleCodeIndex;
    }

    /**
     * preamble index used between this device pair as defined by 802.15.4z
     *
     * Range: 0, 13-16, 21-32
     *
     * @return the preamble index to use for receiving
     */
    public int getRxPreambleIndex() {
        return mReceivePreambleCodeIndex;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            STS_PHY_PACKET_TYPE_SP0,
            STS_PHY_PACKET_TYPE_SP1,
            STS_PHY_PACKET_TYPE_SP2,
            STS_PHY_PACKET_TYPE_SP3})
    public @interface StsPhyPacketType {}

    /**
     * PHY packet type SP0 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP0 = 0;

    /**
     * PHY packet type SP1 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP1 = 1;

    /**
     * PHY packet type SP2 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP2 = 2;

    /**
     * PHY packet type SP3 when STS is used as defined by 802.15.4z
     */
    public static final int STS_PHY_PACKET_TYPE_SP3 = 3;

    /**
     * Get the type of PHY packet when STS is used as defined by 802.15.4z
     *
     * @return the {@link StsPhyPacketType} to use
     */
    @StsPhyPacketType
    public int getStsPhyPacketType() {
        return mStsPhyPacketType;
    }

    /**
     * Parameters for a specific UWB protocol constructed using a support library.
     *
     * <p>Android reserves the '^android.*' namespace
     *
     * @return a {@link PersistableBundle} copy of protocol specific parameters
     */
    public @Nullable PersistableBundle getSpecificationParameters() {
        return new PersistableBundle(mSpecificationParameters);
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof RangingParams) {
            RangingParams other = (RangingParams) obj;

            return mIsInitiator == other.mIsInitiator
                    && mIsController == other.mIsController
                    && mSamplePeriod.equals(other.mSamplePeriod)
                    && mLocalDeviceAddress.equals(other.mLocalDeviceAddress)
                    && mRemoteDeviceAddresses.equals(other.mRemoteDeviceAddresses)
                    && mChannelNumber == other.mChannelNumber
                    && mTransmitPreambleCodeIndex == other.mTransmitPreambleCodeIndex
                    && mReceivePreambleCodeIndex == other.mReceivePreambleCodeIndex
                    && mStsPhyPacketType == other.mStsPhyPacketType
                    && mSpecificationParameters.size() == other.mSpecificationParameters.size()
                    && mSpecificationParameters.kindofEquals(other.mSpecificationParameters);
        }
        return false;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mIsInitiator, mIsController, mSamplePeriod, mLocalDeviceAddress,
                mRemoteDeviceAddresses, mChannelNumber, mTransmitPreambleCodeIndex,
                mReceivePreambleCodeIndex, mStsPhyPacketType, mSpecificationParameters);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsInitiator);
        dest.writeBoolean(mIsController);
        dest.writeLong(mSamplePeriod.getSeconds());
        dest.writeInt(mSamplePeriod.getNano());
        dest.writeParcelable(mLocalDeviceAddress, flags);

        UwbAddress[] remoteAddresses = new UwbAddress[mRemoteDeviceAddresses.size()];
        mRemoteDeviceAddresses.toArray(remoteAddresses);
        dest.writeParcelableArray(remoteAddresses, flags);

        dest.writeInt(mChannelNumber);
        dest.writeInt(mTransmitPreambleCodeIndex);
        dest.writeInt(mReceivePreambleCodeIndex);
        dest.writeInt(mStsPhyPacketType);
        dest.writePersistableBundle(mSpecificationParameters);
    }

    public static final @android.annotation.NonNull Creator<RangingParams> CREATOR =
            new Creator<RangingParams>() {
                @Override
                public RangingParams createFromParcel(Parcel in) {
                    Builder builder = new Builder();
                    builder.setIsInitiator(in.readBoolean());
                    builder.setIsController(in.readBoolean());
                    builder.setSamplePeriod(Duration.ofSeconds(in.readLong(), in.readInt()));
                    builder.setLocalDeviceAddress(
                            in.readParcelable(UwbAddress.class.getClassLoader()));

                    UwbAddress[] remoteAddresses =
                            in.readParcelableArray(null, UwbAddress.class);
                    for (UwbAddress remoteAddress : remoteAddresses) {
                        builder.addRemoteDeviceAddress(remoteAddress);
                    }

                    builder.setChannelNumber(in.readInt());
                    builder.setTransmitPreambleCodeIndex(in.readInt());
                    builder.setReceivePreambleCodeIndex(in.readInt());
                    builder.setStsPhPacketType(in.readInt());
                    builder.setSpecificationParameters(in.readPersistableBundle());

                    return builder.build();
                }

                @Override
                public RangingParams[] newArray(int size) {
                    return new RangingParams[size];
                }
    };

    /**
     * Builder class for {@link RangingParams}.
     */
    public static final class Builder {
        private boolean mIsInitiator = false;
        private boolean mIsController = false;
        private Duration mSamplePeriod = null;
        private UwbAddress mLocalDeviceAddress = null;
        private List<UwbAddress> mRemoteDeviceAddresses = new ArrayList<>();
        private int mChannelNumber = 0;
        private int mTransmitPreambleCodeIndex = 0;
        private int mReceivePreambleCodeIndex = 0;
        private int mStsPhyPacketType = STS_PHY_PACKET_TYPE_SP0;
        private PersistableBundle mSpecificationParameters = new PersistableBundle();

        /**
         * Set whether the device is the initiator or responder as defined by IEEE 802.15.4z
         *
         * @param isInitiator whether the device is the initiator (true) or responder (false)
         */
        public Builder setIsInitiator(boolean isInitiator) {
            mIsInitiator = isInitiator;
            return this;
        }

        /**
         * Set whether the local device is the controller or controlee as defined by IEEE 802.15.4z
         *
         * @param isController whether the device is the controller (true) or controlee (false)
         */
        public Builder setIsController(boolean isController) {
            mIsController = isController;
            return this;
        }

        /**
         * Set the time between ranging samples
         *
         * @param samplePeriod the time between ranging samples
         */
        public Builder setSamplePeriod(@NonNull Duration samplePeriod) {
            mSamplePeriod = samplePeriod;
            return this;
        }

        /**
         * Set the local device address
         *
         * @param localDeviceAddress the local device's address for the {@link RangingSession}
         */
        public Builder setLocalDeviceAddress(@NonNull UwbAddress localDeviceAddress) {
            mLocalDeviceAddress = localDeviceAddress;
            return this;
        }

        /**
         * Add a remote device's address to the ranging session
         *
         * @param remoteDeviceAddress a remote device's address for the {@link RangingSession}
         * @throws IllegalArgumentException if {@code remoteDeviceAddress} is already present.
         */
        public Builder addRemoteDeviceAddress(@NonNull UwbAddress remoteDeviceAddress) {
            if (mRemoteDeviceAddresses.contains(remoteDeviceAddress)) {
                throw new IllegalArgumentException(
                        "Remote device address already added: " + remoteDeviceAddress.toString());
            }
            mRemoteDeviceAddresses.add(remoteDeviceAddress);
            return this;
        }

        /**
         * Set the IEEE 802.15.4z channel to use for the {@link RangingSession}
         * <p>Valid values are in the range [-1, 15]
         *
         * @param channelNumber the channel to use for the {@link RangingSession}
         * @throws IllegalArgumentException if {@code channelNumber} is invalid.
         */
        public Builder setChannelNumber(int channelNumber) {
            if (channelNumber < -1 || channelNumber > 15) {
                throw new IllegalArgumentException("Invalid channel number");
            }
            mChannelNumber = channelNumber;
            return this;
        }

        private static final Set<Integer> VALID_TX_PREAMBLE_CODES = new HashSet<Integer>(
                Arrays.asList(0, 13, 14, 15, 16, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32));

        /**
         * Set the IEEE 802.15.4z preamble code index to use when transmitting
         *
         * <p>Valid values are in the ranges: [0], [13-16], [21-32]
         *
         * @param transmitPreambleCodeIndex preamble code index to use for transmitting
         * @throws IllegalArgumentException if {@code transmitPreambleCodeIndex} is invalid.
         */
        public Builder setTransmitPreambleCodeIndex(int transmitPreambleCodeIndex) {
            if (!VALID_TX_PREAMBLE_CODES.contains(transmitPreambleCodeIndex)) {
                throw new IllegalArgumentException(
                        "Invalid transmit preamble: " + transmitPreambleCodeIndex);
            }
            mTransmitPreambleCodeIndex = transmitPreambleCodeIndex;
            return this;
        }

        private static final Set<Integer> VALID_RX_PREAMBLE_CODES = new HashSet<Integer>(
                Arrays.asList(0, 16, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32));

        /**
         * Set the IEEE 802.15.4z preamble code index to use when receiving
         *
         * Valid values are in the ranges: [0], [16-32]
         *
         * @param receivePreambleCodeIndex preamble code index to use for receiving
         * @throws IllegalArgumentException if {@code receivePreambleCodeIndex} is invalid.
         */
        public Builder setReceivePreambleCodeIndex(int receivePreambleCodeIndex) {
            if (!VALID_RX_PREAMBLE_CODES.contains(receivePreambleCodeIndex)) {
                throw new IllegalArgumentException(
                        "Invalid receive preamble: " + receivePreambleCodeIndex);
            }
            mReceivePreambleCodeIndex = receivePreambleCodeIndex;
            return this;
        }

        /**
         * Set the IEEE 802.15.4z PHY packet type when STS is used
         *
         * @param stsPhyPacketType PHY packet type when STS is used
         * @throws IllegalArgumentException if {@code stsPhyPacketType} is invalid.
         */
        public Builder setStsPhPacketType(@StsPhyPacketType int stsPhyPacketType) {
            if (stsPhyPacketType != STS_PHY_PACKET_TYPE_SP0
                    && stsPhyPacketType != STS_PHY_PACKET_TYPE_SP1
                    && stsPhyPacketType != STS_PHY_PACKET_TYPE_SP2
                    && stsPhyPacketType != STS_PHY_PACKET_TYPE_SP3) {
                throw new IllegalArgumentException("unknown StsPhyPacketType: " + stsPhyPacketType);
            }

            mStsPhyPacketType = stsPhyPacketType;
            return this;
        }

        /**
         * Set the specification parameters
         *
         * <p>Creates a copy of the parameters
         *
         * @param parameters specification parameters built from support library
         */
        public Builder setSpecificationParameters(@NonNull PersistableBundle parameters) {
            mSpecificationParameters = new PersistableBundle(parameters);
            return this;
        }

        /**
         * Build the {@link RangingParams} object.
         *
         * @throws IllegalStateException if required parameters are missing
         */
        public RangingParams build() {
            if (mSamplePeriod == null) {
                throw new IllegalStateException("No sample period provided");
            }

            if (mLocalDeviceAddress == null) {
                throw new IllegalStateException("Local device address not provided");
            }

            if (mRemoteDeviceAddresses.size() == 0) {
                throw new IllegalStateException("No remote device address(es) provided");
            }

            return new RangingParams(mIsInitiator, mIsController, mSamplePeriod,
                    mLocalDeviceAddress, mRemoteDeviceAddresses, mChannelNumber,
                    mTransmitPreambleCodeIndex, mReceivePreambleCodeIndex, mStsPhyPacketType,
                    mSpecificationParameters);
        }
    }
}
