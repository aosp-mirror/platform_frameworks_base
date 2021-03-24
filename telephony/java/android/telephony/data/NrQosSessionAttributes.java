/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.QosSessionAttributes;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Provides Qos attributes of an NR bearer.
 *
 * {@hide}
 */
@SystemApi
public final class NrQosSessionAttributes implements Parcelable, QosSessionAttributes {
    private static final String TAG = NrQosSessionAttributes.class.getSimpleName();
    private final int m5Qi;
    private final @IntRange(from=1, to=63) int mQfi;
    private final long mMaxUplinkBitRate;
    private final long mMaxDownlinkBitRate;
    private final long mGuaranteedUplinkBitRate;
    private final long mGuaranteedDownlinkBitRate;
    private final long mAveragingWindow;
    @NonNull private final List<InetSocketAddress> mRemoteAddresses;

    /**
     * 5G QOS Identifier (5QI), see 3GPP TS 24.501 and 23.501.
     * The allowed values are standard values(1-9, 65-68, 69-70, 75, 79-80, 82-85)
     * defined in the spec and operator specific values in the range 128-254.
     *
     * @return the 5QI of the QOS flow
     */
    public int getQosIdentifier() {
        return m5Qi;
    }

    /**
     * QOS flow identifier of the QOS flow description in the
     * range of 1 to 63. see 3GPP TS 24.501 and 23.501.
     *
     * @return the QOS flow identifier of the session
     */
    public @IntRange(from=1, to=63) int getQosFlowIdentifier() {
        return mQfi;
    }

    /**
     * Minimum bit rate in kbps that is guaranteed to be provided by the network on the uplink.
     *
     * see 3GPP TS 24.501 section 6.2.5
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
     * see 3GPP TS 24.501 section 6.2.5
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the guaranteed bit rate in kbps
     */
    public long getGuaranteedDownlinkBitRateKbps() {
        return mGuaranteedDownlinkBitRate;
    }

    /**
     * The maximum uplink kbps that the network will accept.
     *
     * see 3GPP TS 24.501 section 6.2.5
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the max uplink bit rate in kbps
     */
    public long getMaxUplinkBitRateKbps() {
        return mMaxUplinkBitRate;
    }

    /**
     * The maximum downlink kbps that the network can provide.
     *
     * see 3GPP TS 24.501 section 6.2.5
     *
     * Note: The Qos Session may be shared with OTHER applications besides yours.
     *
     * @return the max downlink bit rate in kbps
     */
    public long getMaxDownlinkBitRateKbps() {
        return mMaxDownlinkBitRate;
    }

    /**
     * The duration in milliseconds over which the maximum bit rates and guaranteed bit rates
     * are calculated
     *
     * see 3GPP TS 24.501 section 6.2.5
     *
     * @return the averaging window duration in milliseconds
     */
    @NonNull
    public Duration getBitRateWindowDuration() {
        return Duration.ofMillis(mAveragingWindow);
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
     * @param fiveQi 5G quality class indicator
     * @param qfi QOS flow identifier
     * @param maxDownlinkBitRate the max downlink bit rate in kbps
     * @param maxUplinkBitRate the max uplink bit rate in kbps
     * @param guaranteedDownlinkBitRate the guaranteed downlink bit rate in kbps
     * @param guaranteedUplinkBitRate the guaranteed uplink bit rate in kbps
     * @param averagingWindow the averaging window duration in milliseconds
     * @param remoteAddresses the remote addresses that the uplink bit rates apply to
     *
     * @hide
     */
    public NrQosSessionAttributes(final int fiveQi, final int qfi,
            final long maxDownlinkBitRate, final long maxUplinkBitRate,
            final long guaranteedDownlinkBitRate, final long guaranteedUplinkBitRate,
            final long averagingWindow, @NonNull final List<InetSocketAddress> remoteAddresses) {
        Objects.requireNonNull(remoteAddresses, "remoteAddress must be non-null");
        m5Qi = fiveQi;
        mQfi = qfi;
        mMaxDownlinkBitRate = maxDownlinkBitRate;
        mMaxUplinkBitRate = maxUplinkBitRate;
        mGuaranteedDownlinkBitRate = guaranteedDownlinkBitRate;
        mGuaranteedUplinkBitRate = guaranteedUplinkBitRate;
        mAveragingWindow = averagingWindow;

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

    private NrQosSessionAttributes(@NonNull final Parcel in) {
        m5Qi = in.readInt();
        mQfi = in.readInt();
        mMaxDownlinkBitRate = in.readLong();
        mMaxUplinkBitRate = in.readLong();
        mGuaranteedDownlinkBitRate = in.readLong();
        mGuaranteedUplinkBitRate = in.readLong();
        mAveragingWindow = in.readLong();

        final int size = in.readInt();
        final List<InetSocketAddress> remoteAddresses = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] addressBytes = in.createByteArray();
            final int port = in.readInt();
            try {
                remoteAddresses.add(
                        new InetSocketAddress(InetAddress.getByAddress(addressBytes), port));
            } catch (final UnknownHostException e) {
                // Impossible case since its filtered out the null values in the ..ctor
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
        dest.writeInt(m5Qi);
        dest.writeInt(mQfi);
        dest.writeLong(mMaxDownlinkBitRate);
        dest.writeLong(mMaxUplinkBitRate);
        dest.writeLong(mGuaranteedDownlinkBitRate);
        dest.writeLong(mGuaranteedUplinkBitRate);
        dest.writeLong(mAveragingWindow);

        final int size = mRemoteAddresses.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            final InetSocketAddress address = mRemoteAddresses.get(i);
            dest.writeByteArray(address.getAddress().getAddress());
            dest.writeInt(address.getPort());
        }
    }

    @NonNull
    public static final Creator<NrQosSessionAttributes> CREATOR =
            new Creator<NrQosSessionAttributes>() {
        @NonNull
        @Override
        public NrQosSessionAttributes createFromParcel(@NonNull final Parcel in) {
            return new NrQosSessionAttributes(in);
        }

        @NonNull
        @Override
        public NrQosSessionAttributes[] newArray(final int size) {
            return new NrQosSessionAttributes[size];
        }
    };
}
