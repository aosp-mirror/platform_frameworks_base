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
 * Defines the configuration of a NAN subscribe session. Built using
 * {@link SubscribeConfig.Builder}. Subscribe is done using
 * {@link WifiNanManager#subscribe(SubscribeConfig, WifiNanSessionCallback)} or
 * {@link WifiNanSubscribeSession#updateSubscribe(SubscribeConfig)}.
 *
 * @hide PROPOSED_NAN_API
 */
public class SubscribeConfig implements Parcelable {

    /**
     * Defines a passive subscribe session - i.e. a subscribe session where
     * subscribe packets are not transmitted over-the-air and the device listens
     * and matches to transmitted publish packets. Configuration is done using
     * {@link SubscribeConfig.Builder#setSubscribeType(int)}.
     */
    public static final int SUBSCRIBE_TYPE_PASSIVE = 0;

    /**
     * Defines an active subscribe session - i.e. a subscribe session where
     * subscribe packets are transmitted over-the-air. Configuration is done
     * using {@link SubscribeConfig.Builder#setSubscribeType(int)}.
     */
    public static final int SUBSCRIBE_TYPE_ACTIVE = 1;

    /**
     * Specifies that only the first match of a set of identical matches (same
     * publish) will be reported to the subscriber.
     */
    public static final int MATCH_STYLE_FIRST_ONLY = 0;

    /**
     * Specifies that all matches of a set of identical matches (same publish)
     * will be reported to the subscriber.
     */
    public static final int MATCH_STYLE_ALL = 1;

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
    public final int mSubscribeType;

    /**
     * @hide
     */
    public final int mSubscribeCount;

    /**
     * @hide
     */
    public final int mTtlSec;

    /**
     * @hide
     */
    public final int mMatchStyle;

    /**
     * @hide
     */
    public final boolean mEnableTerminateNotification;

    private SubscribeConfig(String serviceName, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] txFilter, int txFilterLength, byte[] rxFilter,
            int rxFilterLength, int subscribeType, int publichCount, int ttlSec, int matchStyle,
            boolean enableTerminateNotification) {
        mServiceName = serviceName;
        mServiceSpecificInfoLength = serviceSpecificInfoLength;
        mServiceSpecificInfo = serviceSpecificInfo;
        mTxFilterLength = txFilterLength;
        mTxFilter = txFilter;
        mRxFilterLength = rxFilterLength;
        mRxFilter = rxFilter;
        mSubscribeType = subscribeType;
        mSubscribeCount = publichCount;
        mTtlSec = ttlSec;
        mMatchStyle = matchStyle;
        mEnableTerminateNotification = enableTerminateNotification;
    }

    @Override
    public String toString() {
        return "SubscribeConfig [mServiceName='" + mServiceName + "', mServiceSpecificInfo='"
                + (new String(mServiceSpecificInfo, 0, mServiceSpecificInfoLength))
                + "', mTxFilter="
                + (new TlvBufferUtils.TlvIterable(0, 1, mTxFilter, mTxFilterLength)).toString()
                + ", mRxFilter="
                + (new TlvBufferUtils.TlvIterable(0, 1, mRxFilter, mRxFilterLength)).toString()
                + ", mSubscribeType=" + mSubscribeType + ", mSubscribeCount=" + mSubscribeCount
                + ", mTtlSec=" + mTtlSec + ", mMatchType=" + mMatchStyle
                + ", mEnableTerminateNotification=" + mEnableTerminateNotification + "]";
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
        dest.writeInt(mSubscribeType);
        dest.writeInt(mSubscribeCount);
        dest.writeInt(mTtlSec);
        dest.writeInt(mMatchStyle);
        dest.writeInt(mEnableTerminateNotification ? 1 : 0);
    }

    public static final Creator<SubscribeConfig> CREATOR = new Creator<SubscribeConfig>() {
        @Override
        public SubscribeConfig[] newArray(int size) {
            return new SubscribeConfig[size];
        }

        @Override
        public SubscribeConfig createFromParcel(Parcel in) {
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
            int subscribeType = in.readInt();
            int subscribeCount = in.readInt();
            int ttlSec = in.readInt();
            int matchStyle = in.readInt();
            boolean enableTerminateNotification = in.readInt() != 0;

            return new SubscribeConfig(serviceName, ssi, ssiLength, txFilter, txFilterLength,
                    rxFilter, rxFilterLength, subscribeType, subscribeCount, ttlSec, matchStyle,
                    enableTerminateNotification);
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof SubscribeConfig)) {
            return false;
        }

        SubscribeConfig lhs = (SubscribeConfig) o;

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

        return mSubscribeType == lhs.mSubscribeType && mSubscribeCount == lhs.mSubscribeCount
                && mTtlSec == lhs.mTtlSec && mMatchStyle == lhs.mMatchStyle
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
        result = 31 * result + mSubscribeType;
        result = 31 * result + mSubscribeCount;
        result = 31 * result + mTtlSec;
        result = 31 * result + mMatchStyle;
        result = 31 * result + (mEnableTerminateNotification ? 1 : 0);

        return result;
    }

    /**
     * Validates that the contents of the SubscribeConfig are valid. Otherwise
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
        if (mSubscribeType < SUBSCRIBE_TYPE_PASSIVE || mSubscribeType > SUBSCRIBE_TYPE_ACTIVE) {
            throw new IllegalArgumentException("Invalid subscribeType - " + mSubscribeType);
        }
        if (mSubscribeCount < 0) {
            throw new IllegalArgumentException("Invalid subscribeCount - must be non-negative");
        }
        if (mTtlSec < 0) {
            throw new IllegalArgumentException("Invalid ttlSec - must be non-negative");
        }
        if (mMatchStyle != MATCH_STYLE_FIRST_ONLY && mMatchStyle != MATCH_STYLE_ALL) {
            throw new IllegalArgumentException(
                    "Invalid matchType - must be MATCH_FIRST_ONLY or MATCH_ALL");
        }
        if (mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE && mRxFilterLength != 0) {
            throw new IllegalArgumentException(
                    "Invalid subscribe config: ACTIVE subscribes can't have an Rx filter");
        }
        if (mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE && mTxFilterLength != 0) {
            throw new IllegalArgumentException(
                    "Invalid subscribe config: PASSIVE subscribes can't have a Tx filter");
        }
    }

    /**
     * Builder used to build {@link SubscribeConfig} objects.
     */
    public static final class Builder {
        private String mServiceName;
        private int mServiceSpecificInfoLength;
        private byte[] mServiceSpecificInfo = new byte[0];
        private int mTxFilterLength;
        private byte[] mTxFilter = new byte[0];
        private int mRxFilterLength;
        private byte[] mRxFilter = new byte[0];
        private int mSubscribeType = SUBSCRIBE_TYPE_PASSIVE;
        private int mSubscribeCount = 0;
        private int mTtlSec = 0;
        private int mMatchStyle = MATCH_STYLE_ALL;
        private boolean mEnableTerminateNotification = true;

        /**
         * Specify the service name of the subscribe session. The actual on-air
         * value is a 6 byte hashed representation of this string.
         *
         * @param serviceName The service name for the subscribe session.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceName(String serviceName) {
            mServiceName = serviceName;
            return this;
        }

        /**
         * Specify service specific information for the subscribe session. This
         * is a free-form byte array available to the application to send
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
         * Specify service specific information for the subscribe session - same
         * as
         * {@link SubscribeConfig.Builder#setServiceSpecificInfo(byte[], int)}
         * but obtaining the data from a String.
         *
         * @param serviceSpecificInfoStr The service specific information string
         *            to be included (as a byte array) in the subscribe
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
         * The transmit filter for an active subscribe session
         * {@link SubscribeConfig.Builder#setSubscribeType(int)} and
         * {@link SubscribeConfig#SUBSCRIBE_TYPE_ACTIVE}. Included in
         * transmitted subscribe packets and used by receivers (passive
         * publishers) to determine whether they match - in addition to just
         * relying on the service name.
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
         * The transmit filter for a passive subsribe session
         * {@link SubscribeConfig.Builder#setSubscribeType(int)} and
         * {@link SubscribeConfig#SUBSCRIBE_TYPE_PASSIVE}. Used by the
         * subscriber to determine whether they match transmitted publish
         * packets - in addition to just relying on the service name.
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
         * Sets the type of the subscribe session: active (subscribe packets are
         * transmitted over-the-air), or passive (no subscribe packets are
         * transmitted, a match is made against a solicited/active publish
         * session whose packets are transmitted over-the-air).
         *
         * @param subscribeType Subscribe session type: active (
         *            {@link SubscribeConfig#SUBSCRIBE_TYPE_ACTIVE}) or passive
         *            ( {@link SubscribeConfig#SUBSCRIBE_TYPE_PASSIVE} ).
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setSubscribeType(int subscribeType) {
            if (subscribeType < SUBSCRIBE_TYPE_PASSIVE || subscribeType > SUBSCRIBE_TYPE_ACTIVE) {
                throw new IllegalArgumentException("Invalid subscribeType - " + subscribeType);
            }
            mSubscribeType = subscribeType;
            return this;
        }

        /**
         * Sets the number of times an active (
         * {@link SubscribeConfig.Builder#setSubscribeType(int)}) subscribe
         * session will transmit a packet. When the count is reached an event
         * will be generated for
         * {@link WifiNanSessionCallback#onSessionTerminated(int)} with reason=
         * {@link WifiNanSessionCallback#TERMINATE_REASON_DONE}.
         *
         * @param subscribeCount Number of subscribe packets to transmit.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setSubscribeCount(int subscribeCount) {
            if (subscribeCount < 0) {
                throw new IllegalArgumentException("Invalid subscribeCount - must be non-negative");
            }
            mSubscribeCount = subscribeCount;
            return this;
        }

        /**
         * Sets the time interval (in seconds) an active (
         * {@link SubscribeConfig.Builder#setSubscribeType(int)}) subscribe
         * session will be alive - i.e. transmitting a packet. When the TTL is
         * reached an event will be generated for
         * {@link WifiNanSessionCallback#onSessionTerminated(int)} with reason=
         * {@link WifiNanSessionCallback#TERMINATE_REASON_DONE}.
         *
         * @param ttlSec Lifetime of a subscribe session in seconds.
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
         * Sets the match style of the subscription - how are matches from a
         * single match session (corresponding to the same publish action on the
         * peer) reported to the host (using the
         * {@link WifiNanSessionCallback#onMatch(int, byte[], int, byte[], int)}
         * ). The options are: only report the first match and ignore the rest
         * {@link SubscribeConfig#MATCH_STYLE_FIRST_ONLY} or report every single
         * match {@link SubscribeConfig#MATCH_STYLE_ALL}.
         *
         * @param matchStyle The reporting style for the discovery match.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setMatchStyle(int matchStyle) {
            if (matchStyle != MATCH_STYLE_FIRST_ONLY && matchStyle != MATCH_STYLE_ALL) {
                throw new IllegalArgumentException(
                        "Invalid matchType - must be MATCH_FIRST_ONLY or MATCH_ALL");
            }
            mMatchStyle = matchStyle;
            return this;
        }

        /**
         * Configure whether a subscribe terminate notification
         * {@link WifiNanSessionCallback#onSessionTerminated(int)} is reported
         * back to the callback.
         *
         * @param enable If true the terminate callback will be called when the
         *            subscribe is terminated. Otherwise it will not be called.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setEnableTerminateNotification(boolean enable) {
            mEnableTerminateNotification = enable;
            return this;
        }

        /**
         * Build {@link SubscribeConfig} given the current requests made on the
         * builder.
         */
        public SubscribeConfig build() {
            return new SubscribeConfig(mServiceName, mServiceSpecificInfo,
                    mServiceSpecificInfoLength, mTxFilter, mTxFilterLength, mRxFilter,
                    mRxFilterLength, mSubscribeType, mSubscribeCount, mTtlSec, mMatchStyle,
                    mEnableTerminateNotification);
        }
    }
}
