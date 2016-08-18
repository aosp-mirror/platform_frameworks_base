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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import libcore.util.HexEncoding;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Defines the configuration of a NAN publish session. Built using
 * {@link PublishConfig.Builder}. A publish session is created using
 * {@link WifiNanManager#publish(PublishConfig, WifiNanSessionCallback)} or updated using
 * {@link WifiNanPublishSession#updatePublish(PublishConfig)}.
 *
 * @hide PROPOSED_NAN_API
 */
public class PublishConfig implements Parcelable {
    /** @hide */
    @IntDef({
            PUBLISH_TYPE_UNSOLICITED, PUBLISH_TYPE_SOLICITED })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PublishTypes {
    }

    /**
     * Defines an unsolicited publish session - a publish session where the publisher is
     * advertising itself by broadcasting on-the-air. An unsolicited publish session is paired
     * with an passive subscribe session {@link SubscribeConfig#SUBSCRIBE_TYPE_PASSIVE}.
     * Configuration is done using {@link PublishConfig.Builder#setPublishType(int)}.
     */
    public static final int PUBLISH_TYPE_UNSOLICITED = 0;

    /**
     * Defines a solicited publish session - a publish session which is silent, waiting for a
     * matching active subscribe session - and responding to it in unicast. A
     * solicited publish session is paired with an active subscribe session
     * {@link SubscribeConfig#SUBSCRIBE_TYPE_ACTIVE}. Configuration is done using
     * {@link PublishConfig.Builder#setPublishType(int)}.
     */
    public static final int PUBLISH_TYPE_SOLICITED = 1;

    /** @hide */
    public final byte[] mServiceName;

    /** @hide */
    public final byte[] mServiceSpecificInfo;

    /** @hide */
    public final byte[] mTxFilter;

    /** @hide */
    public final byte[] mRxFilter;

    /** @hide */
    public final int mPublishType;

    /** @hide */
    public final int mPublishCount;

    /** @hide */
    public final int mTtlSec;

    /** @hide */
    public final boolean mEnableTerminateNotification;

    private PublishConfig(byte[] serviceName, byte[] serviceSpecificInfo,
            byte[] txFilter, byte[] rxFilter, int publishType, int publichCount, int ttlSec,
            boolean enableTerminateNotification) {
        mServiceName = serviceName;
        mServiceSpecificInfo = serviceSpecificInfo;
        mTxFilter = txFilter;
        mRxFilter = rxFilter;
        mPublishType = publishType;
        mPublishCount = publichCount;
        mTtlSec = ttlSec;
        mEnableTerminateNotification = enableTerminateNotification;
    }

    @Override
    public String toString() {
        return "PublishConfig [mServiceName='" + mServiceName + ", mServiceSpecificInfo='" + (
                (mServiceSpecificInfo == null) ? "null" : HexEncoding.encode(mServiceSpecificInfo))
                + ", mTxFilter=" + (new LvBufferUtils.LvIterable(1, mTxFilter)).toString()
                + ", mRxFilter=" + (new LvBufferUtils.LvIterable(1, mRxFilter)).toString()
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
        dest.writeByteArray(mServiceName);
        dest.writeByteArray(mServiceSpecificInfo);
        dest.writeByteArray(mTxFilter);
        dest.writeByteArray(mRxFilter);
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
            byte[] serviceName = in.createByteArray();
            byte[] ssi = in.createByteArray();
            byte[] txFilter = in.createByteArray();
            byte[] rxFilter = in.createByteArray();
            int publishType = in.readInt();
            int publishCount = in.readInt();
            int ttlSec = in.readInt();
            boolean enableTerminateNotification = in.readInt() != 0;

            return new PublishConfig(serviceName, ssi, txFilter, rxFilter, publishType,
                    publishCount, ttlSec, enableTerminateNotification);
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

        return Arrays.equals(mServiceName, lhs.mServiceName) && Arrays.equals(mServiceSpecificInfo,
                lhs.mServiceSpecificInfo) && Arrays.equals(mTxFilter, lhs.mTxFilter)
                && Arrays.equals(mRxFilter, lhs.mRxFilter) && mPublishType == lhs.mPublishType
                && mPublishCount == lhs.mPublishCount && mTtlSec == lhs.mTtlSec
                && mEnableTerminateNotification == lhs.mEnableTerminateNotification;
    }

    @Override
    public int hashCode() {
        int result = 17;

        result = 31 * result + Arrays.hashCode(mServiceName);
        result = 31 * result + Arrays.hashCode(mServiceSpecificInfo);
        result = 31 * result + Arrays.hashCode(mTxFilter);
        result = 31 * result + Arrays.hashCode(mRxFilter);
        result = 31 * result + mPublishType;
        result = 31 * result + mPublishCount;
        result = 31 * result + mTtlSec;
        result = 31 * result + (mEnableTerminateNotification ? 1 : 0);

        return result;
    }

    /**
     * Verifies that the contents of the PublishConfig are valid. Otherwise
     * throws an IllegalArgumentException.
     *
     * @hide
     */
    public void validate() throws IllegalArgumentException {
        WifiNanUtils.validateServiceName(mServiceName);

        if (!LvBufferUtils.isValid(mTxFilter, 1)) {
            throw new IllegalArgumentException(
                    "Invalid txFilter configuration - LV fields do not match up to length");
        }
        if (!LvBufferUtils.isValid(mRxFilter, 1)) {
            throw new IllegalArgumentException(
                    "Invalid rxFilter configuration - LV fields do not match up to length");
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
        if (mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED && mRxFilter != null
                && mRxFilter.length != 0) {
            throw new IllegalArgumentException("Invalid publish config: UNSOLICITED "
                    + "publishes (active) can't have an Rx filter");
        }
        if (mPublishType == PublishConfig.PUBLISH_TYPE_SOLICITED && mTxFilter != null
                && mTxFilter.length != 0) {
            throw new IllegalArgumentException("Invalid publish config: SOLICITED "
                    + "publishes (passive) can't have a Tx filter");
        }
    }

    /**
     * Builder used to build {@link PublishConfig} objects.
     */
    public static final class Builder {
        private byte[] mServiceName;
        private byte[] mServiceSpecificInfo;
        private byte[] mTxFilter;
        private byte[] mRxFilter;
        private int mPublishType = PUBLISH_TYPE_UNSOLICITED;
        private int mPublishCount = 0;
        private int mTtlSec = 0;
        private boolean mEnableTerminateNotification = true;

        /**
         * Specify the service name of the publish session. The actual on-air
         * value is a 6 byte hashed representation of this string.
         * <p>
         * The Service Name is a UTF-8 encoded string from 1 to 255 bytes in length.
         * The only acceptable single-byte UTF-8 symbols for a Service Name are alphanumeric
         * values (A-Z, a-z, 0-9), the hyphen ('-'), and the period ('.'). All valid multi-byte
         * UTF-8 characters are acceptable in a Service Name.
         * <p>
         * Must be called - an empty ServiceName is not valid.
         *
         * @param serviceName The service name for the publish session.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceName(@NonNull String serviceName) {
            if (serviceName == null) {
                throw new IllegalArgumentException("Invalid service name - must be non-null");
            }
            mServiceName = serviceName.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /**
         * Specify service specific information for the publish session. This is
         * a free-form byte array available to the application to send
         * additional information as part of the discovery operation - it
         * will not be used to determine whether a publish/subscribe match
         * occurs.
         * <p>
         *     Optional. Empty by default.
         *
         * @param serviceSpecificInfo A byte-array for the service-specific
         *            information field.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceSpecificInfo(@Nullable byte[] serviceSpecificInfo) {
            mServiceSpecificInfo = serviceSpecificInfo;
            return this;
        }

        /**
         * Specify service specific information for the publish session - a simple wrapper
         * of {@link PublishConfig.Builder#setServiceSpecificInfo(byte[])}
         * obtaining the data from a String.
         * <p>
         *     Optional. Empty by default.
         *
         * @param serviceSpecificInfoStr The service specific information string
         *            to be included (as a byte array) in the publish
         *            information.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setServiceSpecificInfo(@NonNull String serviceSpecificInfoStr) {
            mServiceSpecificInfo = serviceSpecificInfoStr.getBytes();
            return this;
        }

        /**
         * Specify the transmit filter for an active publish session
         * {@link PublishConfig.Builder#setPublishType(int)} and
         * {@link PublishConfig#PUBLISH_TYPE_UNSOLICITED}. Used to determine discovery - both
         * Service Name and filters have to match for discovery to be triggered.
         * <p>
         * Format is an LV byte array: a single byte Length field followed by L bytes (the value of
         * the Length field) of a value blob.
         * </p>
         * <p>
         *     Optional. Empty by default.
         * </p>
         *
         * @param txFilter The byte-array containing the LV formatted transmit filter.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setTxFilter(@Nullable byte[] txFilter) {
            mTxFilter = txFilter;
            return this;
        }

        /**
         * Specify the receive filter for a passive publish session
         * {@link PublishConfig.Builder#setPublishType(int)} and
         * {@link PublishConfig#PUBLISH_TYPE_SOLICITED}. Used to determine discovery - both
         * Service Name and filters have to match for discovery to be triggered.
         * <p>
         * Format is an LV byte array: a single byte Length field followed by L bytes (the value of
         * the Length field) of a value blob.
         * <p>
         *     Optional. Empty by default.
         *
         * @param rxFilter The byte-array containing the LV formatted receive
         *            filter.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setRxFilter(@Nullable byte[] rxFilter) {
            mRxFilter = rxFilter;
            return this;
        }

        /**
         * Specify the type of the publish session: solicited (aka active - publish
         * packets are transmitted over-the-air), or unsolicited (aka passive -
         * no publish packets are transmitted, a match is made against an active
         * subscribe session whose packets are transmitted over-the-air).
         *
         * @param publishType Publish session type:
         *            {@link PublishConfig#PUBLISH_TYPE_SOLICITED} or
         *            {@link PublishConfig#PUBLISH_TYPE_UNSOLICITED} (the default).
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setPublishType(@PublishTypes int publishType) {
            if (publishType < PUBLISH_TYPE_UNSOLICITED || publishType > PUBLISH_TYPE_SOLICITED) {
                throw new IllegalArgumentException("Invalid publishType - " + publishType);
            }
            mPublishType = publishType;
            return this;
        }

        /**
         * Sets the number of times an unsolicited (configured using
         * {@link PublishConfig.Builder#setPublishType(int)}) publish session
         * will be broadcast. When the count is reached an event will be
         * generated for {@link WifiNanSessionCallback#onSessionTerminated(int)}
         * with {@link WifiNanSessionCallback#TERMINATE_REASON_DONE} [unless
         * {@link #setEnableTerminateNotification(boolean)} disables the callback].
         * <p>
         *     Optional. 0 by default - indicating the session doesn't terminate on its own.
         *     Session will be terminated when {@link WifiNanSession#terminate()} is called.
         *
         * @param publishCount Number of publish packets to broadcast.
         *
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
         * Sets the time interval (in seconds) an unsolicited (
         * {@link PublishConfig.Builder#setPublishType(int)}) publish session
         * will be alive - broadcasting a packet. When the TTL is reached
         * an event will be generated for
         * {@link WifiNanSessionCallback#onSessionTerminated(int)} with
         * {@link WifiNanSessionCallback#TERMINATE_REASON_DONE}  [unless
         * {@link #setEnableTerminateNotification(boolean)} disables the callback].
         * <p>
         *     Optional. 0 by default - indicating the session doesn't terminate on its own.
         *     Session will be terminated when {@link WifiNanSession#terminate()} is called.
         *
         * @param ttlSec Lifetime of a publish session in seconds.
         *
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
         *
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
            return new PublishConfig(mServiceName, mServiceSpecificInfo, mTxFilter, mRxFilter,
                    mPublishType, mPublishCount, mTtlSec, mEnableTerminateNotification);
        }
    }
}
