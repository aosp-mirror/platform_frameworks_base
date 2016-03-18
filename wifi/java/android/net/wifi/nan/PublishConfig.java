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

package android.net.wifi.nan;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Defines the configuration of a NAN publish session. Built using
 * {@link PublishConfig.Builder}. Publish is done using
 * {@link WifiNanManager#publish(PublishConfig, WifiNanSessionCallback)} or
 * {@link WifiNanPublishSession#updatePublish(PublishConfig)}.
 *
 * @hide PROPOSED_NAN_API
 */
public class PublishConfig implements Parcelable {
    /**
     * Defines an unsolicited publish session - i.e. a publish session where
     * publish packets are transmitted over-the-air. Configuration is done using
     * {@link PublishConfig.Builder#setPublishType(int)}.
     */
    public static final int PUBLISH_TYPE_UNSOLICITED = 0;

    /**
     * Defines a solicited publish session - i.e. a publish session where
     * publish packets are not transmitted over-the-air and the device listens
     * and matches to transmitted subscribe packets. Configuration is done using
     * {@link PublishConfig.Builder#setPublishType(int)}.
     */
    public static final int PUBLISH_TYPE_SOLICITED = 1;

    /**
     * @hide
     */
    public final String mServiceName;

    /**
     * @hide
     */
    public final int mServiceSpecificInfoLength;

    /**
     * @hide
     */
    public final byte[] mServiceSpecificInfo;

    /**
     * @hide
     */
    public final int mTxFilterLength;

    /**
     * @hide
     */
    public final byte[] mTxFilter;

    /**
     * @hide
     */
    public final int mRxFilterLength;

    /**
     * @hide
     */
    public final byte[] mRxFilter;

    /**
     * @hide
     */
    public final int mPublishType;

    /**
     * @hide
     */
    public final int mPublishCount;

    /**
     * @hide
     */
    public final int mTtlSec;

    /**
     * @hide
     */
    public final boolean mEnableTerminateNotification;

    private PublishConfig(String serviceName, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] txFilter, int txFilterLength, byte[] rxFilter,
            int rxFilterLength, int publishType, int publichCount, int ttlSec,
            boolean enableTerminateNotification) {
        mServiceName = serviceName;
        mServiceSpecificInfoLength = serviceSpecificInfoLength;
        mServiceSpecificInfo = serviceSpecificInfo;
        mTxFilterLength = txFilterLength;
        mTxFilter = txFilter;
        mRxFilterLength = rxFilterLength;
        mRxFilter = rxFilter;
        mPublishType = publishType;
        mPublishCount = publichCount;
        mTtlSec = ttlSec;
        mEnableTerminateNotification = enableTerminateNotification;
    }

    @Override
    public String toString() {
        return "PublishConfig [mServiceName='" + mServiceName + "', mServiceSpecificInfo='"
                + (new String(mServiceSpecificInfo, 0, mServiceSpecificInfoLength))
                + "', mTxFilter="
                + (new TlvBufferUtils.TlvIterable(0, 1, mTxFilter, mTxFilterLength)).toString()
                + ", mRxFilter="
                + (new TlvBufferUtils.TlvIterable(0, 1, mRxFilter, mRxFilterLength)).toString()
                + ", mPublishType=" + mPublishType + ", mPublishCount=" + mPublishCount
                + ", mTtlSec=" + mTtlSec + ", mEnableTerminateNotification="
                + mEnableTerminateNotification + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeInt(mServiceSpecificInfoLength);
        if (mServiceSpecificInfoLength != 0) {
            dest.writeByteArray(mServiceSpecificInfo, 0, mServiceSpecificInfoLength);
        }
        dest.writeInt(mTxFilterLength);
        if (mTxFilterLength != 0) {
            dest.writeByteArray(mTxFilter, 0, mTxFilterLength);
        }
        dest.writeInt(mRxFilterLength);
        if (mRxFilterLength != 0) {
            dest.writeByteArray(mRxFilter, 0, mRxFilterLength);
        }
        dest.writeInt(mPublishType);
        dest.writeInt(mPublishCount);
        dest.writeInt(mTtlSec);
        dest.writeInt(mEnableTerminateNotification ? 1 : 0);
    }

    public static final Creator<PublishConfig> CREATOR = new Creator<PublishConfig>() {
        @Override
        public PublishConfig[] newArray(int size) {
            return new PublishConfig[size];
        }

        @Override
        public PublishConfig createFromParcel(Parcel in) {
            String serviceName = in.readString();
            int ssiLength = in.readInt();
            byte[] ssi = new byte[ssiLength];
            if (ssiLength != 0) {
                in.readByteArray(ssi);
            }
            int txFilterLength = in.readInt();
            byte[] txFilter = new byte[txFilterLength];
            if (txFilterLength != 0) {
                in.readByteArray(txFilter);
            }
            int rxFilterLength = in.readInt();
            byte[] rxFilter = new byte[rxFilterLength];
            if (rxFilterLength != 0) {
                in.readByteArray(rxFilter);
            }
            int publishType = in.readInt();
            int publishCount = in.readInt();
            int ttlSec = in.readInt();
            boolean enableTerminateNotification = in.readInt() != 0;

            return new PublishConfig(serviceName, ssi, ssiLength, txFilter, txFilterLength,
                    rxFilter, rxFilterLength, publishType, publishCount, ttlSec,
                    enableTerminateNotification);
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PublishConfig)) {
            return false;
        }

        PublishConfig lhs = (PublishConfig) o;

        if (!mServiceName.equals(lhs.mServiceName)
                || mServiceSpecificInfoLength != lhs.mServiceSpecificInfoLength
                || mTxFilterLength != lhs.mTxFilterLength
                || mRxFilterLength != lhs.mRxFilterLength) {
            return false;
        }

        if (mServiceSpecificInfo != null && lhs.mServiceSpecificInfo != null) {
            for (int i = 0; i < mServiceSpecificInfoLength; ++i) {
                if (mServiceSpecificInfo[i] != lhs.mServiceSpecificInfo[i]) {
                    return false;
                }
            }
        } else if (mServiceSpecificInfoLength != 0) {
            return false; // invalid != invalid
        }

        if (mTxFilter != null && lhs.mTxFilter != null) {
            for (int i = 0; i < mTxFilterLength; ++i) {
                if (mTxFilter[i] != lhs.mTxFilter[i]) {
                    return false;
                }
            }
        } else if (mTxFilterLength != 0) {
            return false; // invalid != invalid
        }

        if (mRxFilter != null && lhs.mRxFilter != null) {
            for (int i = 0; i < mRxFilterLength; ++i) {
                if (mRxFilter[i] != lhs.mRxFilter[i]) {
                    return false;
                }
            }
        } else if (mRxFilterLength != 0) {
            return false; // invalid != invalid
        }

        return mPublishType == lhs.mPublishType && mPublishCount == lhs.mPublishCount
                && mTtlSec == lhs.mTtlSec
                && mEnableTerminateNotification == lhs.mEnableTerminateNotification;
    }

    @Override
    public int hashCode() {
        int result = 17;

        result = 31 * result + mServiceName.hashCode();
        result = 31 * result + mServiceSpecificInfoLength;
        result = 31 * result + Arrays.hashCode(mServiceSpecificInfo);
        result = 31 * result + mTxFilterLength;
        result = 31 * result + Arrays.hashCode(mTxFilter);
        result = 31 * result + mRxFilterLength;
        result = 31 * result + Arrays.hashCode(mRxFilter);
        result = 31 * result + mPublishType;
        result = 31 * result + mPublishCount;
        result = 31 * result + mTtlSec;
        result = 31 * result + (mEnableTerminateNotification ? 1 : 0);

        return result;
    }

    /**
     * Validates that the contents of the PublishConfig are valid. Otherwise
     * throws an IllegalArgumentException.
     *
     * @hide
     */
    public void validate() throws IllegalArgumentException {
        if (mServiceSpecificInfoLength != 0 && (mServiceSpecificInfo == null
                || mServiceSpecificInfo.length < mServiceSpecificInfoLength)) {
            throw new IllegalArgumentException("Non-matching combination of "
                    + "serviceSpecificInfo and serviceSpecificInfoLength");
        }
        if (mTxFilterLength != 0 && (mTxFilter == null || mTxFilter.length < mTxFilterLength)) {
            throw new IllegalArgumentException(
                    "Non-matching combination of txFilter and txFilterLength");
        }
        if (mRxFilterLength != 0 && (mRxFilter == null || mRxFilter.length < mRxFilterLength)) {
            throw new IllegalArgumentException(
                    "Non-matching combination of rxFilter and rxFilterLength");
        }
        if (mPublishType < PUBLISH_TYPE_UNSOLICITED || mPublishType > PUBLISH_TYPE_SOLICITED) {
            throw new IllegalArgumentException("Invalid publishType - " + mPublishType);
        }
        if (mPublishCount < 0) {
            throw new IllegalArgumentException("Invalid publishCount - must be non-negative");
        }
        if (mTtlSec < 0) {
            throw new IllegalArgumentException("Invalid ttlSec - must be non-negative");
        }
        if (mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED && mRxFilterLength != 0) {
            throw new IllegalArgumentException("Invalid publish config: UNSOLICITED "
                    + "publishes (active) can't have an Rx filter");
        }
        if (mPublishType == PublishConfig.PUBLISH_TYPE_SOLICITED && mTxFilterLength != 0) {
            throw new IllegalArgumentException("Invalid publish config: SOLICITED "
                    + "publishes (passive) can't have a Tx filter");
        }
    }

    /**
     * Builder used to build {@link PublishConfig} objects.
     */
    public static final class Builder {
        private String mServiceName;
        private int mServiceSpecificInfoLength;
        private byte[] mServiceSpecificInfo = new byte[0];
        private int mTxFilterLength;
        private byte[] mTxFilter = new byte[0];
        private int mRxFilterLength;
        private byte[] mRxFilter = new byte[0];
        private int mPublishType = PUBLISH_TYPE_UNSOLICITED;
        private int mPublishCount = 0;
        private int mTtlSec = 0;
        private boolean mEnableTerminateNotification = true;

        /**
         * Specify the service name of the publish session. The actual on-air
         * value is a 6 byte hashed representation of this string.
         *
         * @param serviceName The service name for the publish session.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceName(String serviceName) {
            mServiceName = serviceName;
            return this;
        }

        /**
         * Specify service specific information for the publish session. This is
         * a free-form byte array available to the application to send
         * additional information as part of the discovery operation - i.e. it
         * will not be used to determine whether a publish/subscribe match
         * occurs.
         *
         * @param serviceSpecificInfo A byte-array for the service-specific
         *            information field.
         * @param serviceSpecificInfoLength The length of the byte-array to be
         *            used.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceSpecificInfo(byte[] serviceSpecificInfo,
                int serviceSpecificInfoLength) {
            if (serviceSpecificInfoLength != 0 && (serviceSpecificInfo == null
                    || serviceSpecificInfo.length < serviceSpecificInfoLength)) {
                throw new IllegalArgumentException("Non-matching combination of "
                        + "serviceSpecificInfo and serviceSpecificInfoLength");
            }
            mServiceSpecificInfoLength = serviceSpecificInfoLength;
            mServiceSpecificInfo = serviceSpecificInfo;
            return this;
        }

        /**
         * Specify service specific information for the publish session - same
         * as {@link PublishConfig.Builder#setServiceSpecificInfo(byte[], int)}
         * but obtaining the data from a String.
         *
         * @param serviceSpecificInfoStr The service specific information string
         *            to be included (as a byte array) in the publish
         *            information.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceSpecificInfo(String serviceSpecificInfoStr) {
            mServiceSpecificInfoLength = serviceSpecificInfoStr.length();
            mServiceSpecificInfo = serviceSpecificInfoStr.getBytes();
            return this;
        }

        /**
         * The transmit filter for an active publish session
         * {@link PublishConfig.Builder#setPublishType(int)} and
         * {@link PublishConfig#PUBLISH_TYPE_UNSOLICITED}. Included in
         * transmitted publish packets and used by receivers (subscribers) to
         * determine whether they match - in addition to just relying on the
         * service name.
         * <p>
         * Format is an LV byte array - the {@link TlvBufferUtils} utility class
         * is available to form and parse.
         *
         * @param txFilter The byte-array containing the LV formatted transmit
         *            filter.
         * @param txFilterLength The number of bytes in the transmit filter
         *            argument.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setTxFilter(byte[] txFilter, int txFilterLength) {
            if (txFilterLength != 0 && (txFilter == null || txFilter.length < txFilterLength)) {
                throw new IllegalArgumentException(
                        "Non-matching combination of txFilter and txFilterLength");
            }
            mTxFilter = txFilter;
            mTxFilterLength = txFilterLength;
            return this;
        }

        /**
         * The transmit filter for a passive publish session
         * {@link PublishConfig.Builder#setPublishType(int)} and
         * {@link PublishConfig#PUBLISH_TYPE_SOLICITED}. Used by the publisher
         * to determine whether they match transmitted subscriber packets
         * (active subscribers) - in addition to just relying on the service
         * name.
         * <p>
         * Format is an LV byte array - the {@link TlvBufferUtils} utility class
         * is available to form and parse.
         *
         * @param rxFilter The byte-array containing the LV formatted receive
         *            filter.
         * @param rxFilterLength The number of bytes in the receive filter
         *            argument.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setRxFilter(byte[] rxFilter, int rxFilterLength) {
            if (rxFilterLength != 0 && (rxFilter == null || rxFilter.length < rxFilterLength)) {
                throw new IllegalArgumentException(
                        "Non-matching combination of rxFilter and rxFilterLength");
            }
            mRxFilter = rxFilter;
            mRxFilterLength = rxFilterLength;
            return this;
        }

        /**
         * Sets the type of the publish session: solicited (aka active - publish
         * packets are transmitted over-the-air), or unsolicited (aka passive -
         * no publish packets are transmitted, a match is made against an active
         * subscribe session whose packets are transmitted over-the-air).
         *
         * @param publishType Publish session type: solicited (
         *            {@link PublishConfig#PUBLISH_TYPE_SOLICITED}) or
         *            unsolicited (
         *            {@link PublishConfig#PUBLISH_TYPE_UNSOLICITED}).
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setPublishType(int publishType) {
            if (publishType < PUBLISH_TYPE_UNSOLICITED || publishType > PUBLISH_TYPE_SOLICITED) {
                throw new IllegalArgumentException("Invalid publishType - " + publishType);
            }
            mPublishType = publishType;
            return this;
        }

        /**
         * Sets the number of times a solicited (
         * {@link PublishConfig.Builder#setPublishType(int)}) publish session
         * will transmit a packet. When the count is reached an event will be
         * generated for {@link WifiNanSessionCallback#onSessionTerminated(int)}
         * with reason={@link WifiNanSessionCallback#TERMINATE_REASON_DONE}.
         *
         * @param publishCount Number of publish packets to transmit.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setPublishCount(int publishCount) {
            if (publishCount < 0) {
                throw new IllegalArgumentException("Invalid publishCount - must be non-negative");
            }
            mPublishCount = publishCount;
            return this;
        }

        /**
         * Sets the time interval (in seconds) a solicited (
         * {@link PublishConfig.Builder#setPublishCount(int)}) publish session
         * will be alive - i.e. transmitting a packet. When the TTL is reached
         * an event will be generated for
         * {@link WifiNanSessionCallback#onSessionTerminated(int)} with reason=
         * {@link WifiNanSessionCallback#TERMINATE_REASON_DONE}.
         *
         * @param ttlSec Lifetime of a publish session in seconds.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setTtlSec(int ttlSec) {
            if (ttlSec < 0) {
                throw new IllegalArgumentException("Invalid ttlSec - must be non-negative");
            }
            mTtlSec = ttlSec;
            return this;
        }

        /**
         * Configure whether a publish terminate notification
         * {@link WifiNanSessionCallback#onSessionTerminated(int)} is reported
         * back to the callback.
         *
         * @param enable If true the terminate callback will be called when the
         *            publish is terminated. Otherwise it will not be called.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setEnableTerminateNotification(boolean enable) {
            mEnableTerminateNotification = enable;
            return this;
        }

        /**
         * Build {@link PublishConfig} given the current requests made on the
         * builder.
         */
        public PublishConfig build() {
            return new PublishConfig(mServiceName, mServiceSpecificInfo, mServiceSpecificInfoLength,
                    mTxFilter, mTxFilterLength, mRxFilter, mRxFilterLength, mPublishType,
                    mPublishCount, mTtlSec, mEnableTerminateNotification);
        }
    }
}
