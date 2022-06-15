/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.QosSessionAttributes;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Provides Qos attributes of an EPS bearer.
 *
 * {@hide}
 */
@SystemApi
public final class EpsBearerQosSessionAttributes implements Parcelable, QosSessionAttributes {
    private static final String TAG = EpsBearerQosSessionAttributes.class.getSimpleName();
    private final int mQci;
    private final long mMaxUplinkBitRate;
    private final long mMaxDownlinkBitRate;
    private final long mGuaranteedUplinkBitRate;
    private final long mGuaranteedDownlinkBitRate;
    @NonNull private final List<InetSocketAddress> mRemoteAddresses;

    /**
     * Quality of Service Class Identifier (QCI), see 3GPP TS 23.203 and 29.212.
     * The allowed values are standard values(1-9, 65-68, 69-70, 75, 79-80, 82-85)
     * defined in the spec and operator specific values in the range 128-254.
     *
     * @return the qci of the session
     */
    public int getQosIdentifier() {
        return mQci;
    }

    /**
     * Minimum bit rate in kbps that is guaranteed to be provided by the network on the uplink.
     *
     * see 3GPP TS 23.107 section 6.4.3.1
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the guaranteed bit rate in kbps
     */
    public long getGuaranteedUplinkBitRateKbps() {
        return mGuaranteedUplinkBitRate;
    }

    /**
     * Minimum bit rate in kbps that is guaranteed to be provided by the network on the downlink.
     *
     * see 3GPP TS 23.107 section 6.4.3.1
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the guaranteed bit rate in kbps
     */
    public long getGuaranteedDownlinkBitRateKbps() {
        return mGuaranteedDownlinkBitRate;
    }

    /**
     * The maximum kbps that the network will accept.
     *
     * see 3GPP TS 23.107 section 6.4.3.1
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the max uplink bit rate in kbps
     */
    public long getMaxUplinkBitRateKbps() {
        return mMaxUplinkBitRate;
    }

    /**
     * The maximum kbps that the network can provide.
     *
     * see 3GPP TS 23.107 section 6.4.3.1
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the max downlink bit rate in kbps
     */
    public long getMaxDownlinkBitRateKbps() {
        return mMaxDownlinkBitRate;
    }

    /**
     * List of remote addresses associated with the Qos Session.  The given uplink bit rates apply
     * to this given list of remote addresses.
     *
     * Note: In the event that the list is empty, it is assumed that the uplink bit rates apply to
     * all remote addresses that are not contained in a different set of attributes.
     *
     * @return list of remote socket addresses that the attributes apply to
     */
    @NonNull
    public List<InetSocketAddress> getRemoteAddresses() {
        return mRemoteAddresses;
    }

    /**
     * ..ctor for attributes
     *
     * @param qci quality class indicator
     * @param maxDownlinkBitRate the max downlink bit rate in kbps
     * @param maxUplinkBitRate the max uplink bit rate in kbps
     * @param guaranteedDownlinkBitRate the guaranteed downlink bit rate in kbps
     * @param guaranteedUplinkBitRate the guaranteed uplink bit rate in kbps
     * @param remoteAddresses the remote addresses that the uplink bit rates apply to
     *
     * @hide
     */
    public EpsBearerQosSessionAttributes(final int qci,
            final long maxDownlinkBitRate, final long maxUplinkBitRate,
            final long guaranteedDownlinkBitRate, final long guaranteedUplinkBitRate,
            @NonNull final List<InetSocketAddress> remoteAddresses) {
        Objects.requireNonNull(remoteAddresses, "remoteAddress must be non-null");
        mQci = qci;
        mMaxDownlinkBitRate = maxDownlinkBitRate;
        mMaxUplinkBitRate = maxUplinkBitRate;
        mGuaranteedDownlinkBitRate = guaranteedDownlinkBitRate;
        mGuaranteedUplinkBitRate = guaranteedUplinkBitRate;

        final List<InetSocketAddress> remoteAddressesTemp = copySocketAddresses(remoteAddresses);
        mRemoteAddresses = Collections.unmodifiableList(remoteAddressesTemp);
    }

    private static List<InetSocketAddress> copySocketAddresses(
            @NonNull final List<InetSocketAddress> remoteAddresses) {
        final List<InetSocketAddress> remoteAddressesTemp = new ArrayList<>();
        for (final InetSocketAddress socketAddress : remoteAddresses) {
            if (socketAddress != null && socketAddress.getAddress() != null) {
                remoteAddressesTemp.add(socketAddress);
            }
        }
        return remoteAddressesTemp;
    }

    private EpsBearerQosSessionAttributes(@NonNull final Parcel in) {
        mQci = in.readInt();
        mMaxDownlinkBitRate = in.readLong();
        mMaxUplinkBitRate = in.readLong();
        mGuaranteedDownlinkBitRate = in.readLong();
        mGuaranteedUplinkBitRate = in.readLong();

        final int size = in.readInt();
        final List<InetSocketAddress> remoteAddresses = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] addressBytes = in.createByteArray();
            final int port = in.readInt();
            try {
                remoteAddresses.add(
                        new InetSocketAddress(InetAddress.getByAddress(addressBytes), port));
            } catch (final UnknownHostException e) {
                // Impossible case since we filter out null values in the ..ctor
                Log.e(TAG, "unable to unparcel remote address at index: " + i, e);
            }
        }
        mRemoteAddresses = Collections.unmodifiableList(remoteAddresses);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeInt(mQci);
        dest.writeLong(mMaxDownlinkBitRate);
        dest.writeLong(mMaxUplinkBitRate);
        dest.writeLong(mGuaranteedDownlinkBitRate);
        dest.writeLong(mGuaranteedUplinkBitRate);

        final int size = mRemoteAddresses.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            final InetSocketAddress address = mRemoteAddresses.get(i);
            dest.writeByteArray(address.getAddress().getAddress());
            dest.writeInt(address.getPort());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EpsBearerQosSessionAttributes epsBearerAttr = (EpsBearerQosSessionAttributes) o;
        return mQci == epsBearerAttr.mQci
                && mMaxUplinkBitRate == epsBearerAttr.mMaxUplinkBitRate
                && mMaxDownlinkBitRate == epsBearerAttr.mMaxDownlinkBitRate
                && mGuaranteedUplinkBitRate == epsBearerAttr.mGuaranteedUplinkBitRate
                && mGuaranteedDownlinkBitRate == epsBearerAttr.mGuaranteedDownlinkBitRate
                && mRemoteAddresses.size() == epsBearerAttr.mRemoteAddresses.size()
                && mRemoteAddresses.containsAll(epsBearerAttr.mRemoteAddresses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mQci, mMaxUplinkBitRate, mMaxDownlinkBitRate,
                mGuaranteedUplinkBitRate, mGuaranteedDownlinkBitRate, mRemoteAddresses);
    }

    @NonNull
    public static final Creator<EpsBearerQosSessionAttributes> CREATOR =
            new Creator<EpsBearerQosSessionAttributes>() {
        @NonNull
        @Override
        public EpsBearerQosSessionAttributes createFromParcel(@NonNull final Parcel in) {
            return new EpsBearerQosSessionAttributes(in);
        }

        @NonNull
        @Override
        public EpsBearerQosSessionAttributes[] newArray(final int size) {
            return new EpsBearerQosSessionAttributes[size];
        }
    };
}
