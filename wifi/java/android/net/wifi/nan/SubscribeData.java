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
 * Defines the data for a NAN subscribe session. Built using
 * {@link SubscribeData.Builder}. Subscribe is done using
 * {@link WifiNanManager#subscribe(SubscribeData, SubscribeSettings, WifiNanSessionListener, int)}
 * or
 * {@link WifiNanSubscribeSession#subscribe(SubscribeData, SubscribeSettings)}.
 * @hide PROPOSED_NAN_API
 */
public class SubscribeData implements Parcelable {
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

    private SubscribeData(String serviceName, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] txFilter, int txFilterLength, byte[] rxFilter,
            int rxFilterLength) {
        mServiceName = serviceName;
        mServiceSpecificInfoLength = serviceSpecificInfoLength;
        mServiceSpecificInfo = serviceSpecificInfo;
        mTxFilterLength = txFilterLength;
        mTxFilter = txFilter;
        mRxFilterLength = rxFilterLength;
        mRxFilter = rxFilter;
    }

    @Override
    public String toString() {
        return "SubscribeData [mServiceName='" + mServiceName + "', mServiceSpecificInfo='"
                + (new String(mServiceSpecificInfo, 0, mServiceSpecificInfoLength))
                + "', mTxFilter="
                + (new TlvBufferUtils.TlvIterable(0, 1, mTxFilter, mTxFilterLength)).toString()
                + ", mRxFilter="
                + (new TlvBufferUtils.TlvIterable(0, 1, mRxFilter, mRxFilterLength)).toString()
                + "']";
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
    }

    public static final Creator<SubscribeData> CREATOR = new Creator<SubscribeData>() {
        @Override
        public SubscribeData[] newArray(int size) {
            return new SubscribeData[size];
        }

        @Override
        public SubscribeData createFromParcel(Parcel in) {
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

            return new SubscribeData(serviceName, ssi, ssiLength, txFilter, txFilterLength,
                    rxFilter, rxFilterLength);
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof SubscribeData)) {
            return false;
        }

        SubscribeData lhs = (SubscribeData) o;

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

        return true;
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

        return result;
    }

    /**
     * Builder used to build {@link SubscribeData} objects.
     */
    public static final class Builder {
        private String mServiceName;
        private int mServiceSpecificInfoLength;
        private byte[] mServiceSpecificInfo = new byte[0];
        private int mTxFilterLength;
        private byte[] mTxFilter = new byte[0];
        private int mRxFilterLength;
        private byte[] mRxFilter = new byte[0];

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
            mServiceSpecificInfoLength = serviceSpecificInfoLength;
            mServiceSpecificInfo = serviceSpecificInfo;
            return this;
        }

        /**
         * Specify service specific information for the subscribe session - same
         * as {@link SubscribeData.Builder#setServiceSpecificInfo(byte[], int)}
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
         * {@link SubscribeSettings.Builder#setSubscribeType(int)} and
         * {@link SubscribeSettings#SUBSCRIBE_TYPE_ACTIVE}. Included in
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
            mTxFilter = txFilter;
            mTxFilterLength = txFilterLength;
            return this;
        }

        /**
         * The transmit filter for a passive subsribe session
         * {@link SubscribeSettings.Builder#setSubscribeType(int)} and
         * {@link SubscribeSettings#SUBSCRIBE_TYPE_PASSIVE}. Used by the
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
            mRxFilter = rxFilter;
            mRxFilterLength = rxFilterLength;
            return this;
        }

        /**
         * Build {@link SubscribeData} given the current requests made on the
         * builder.
         */
        public SubscribeData build() {
            return new SubscribeData(mServiceName, mServiceSpecificInfo, mServiceSpecificInfoLength,
                    mTxFilter, mTxFilterLength, mRxFilter, mRxFilterLength);
        }
    }
}
