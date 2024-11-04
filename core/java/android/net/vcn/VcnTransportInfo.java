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

package android.net.vcn;

import static android.net.NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS;
import static android.net.vcn.VcnGatewayConnectionConfig.MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS;
import static android.net.vcn.VcnGatewayConnectionConfig.MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.SubscriptionManager;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * VcnTransportInfo contains information about the VCN's underlying transports for SysUi.
 *
 * <p>Presence of this class in the NetworkCapabilities.TransportInfo implies that the network is a
 * VCN.
 *
 * <p>VcnTransportInfo must exist on top of either an underlying Wifi or Cellular Network. If the
 * underlying Network is WiFi, the subId will be {@link
 * SubscriptionManager#INVALID_SUBSCRIPTION_ID}. If the underlying Network is Cellular, the WifiInfo
 * will be {@code null}.
 *
 * <p>Receipt of a VcnTransportInfo requires the NETWORK_SETTINGS permission; else the entire
 * VcnTransportInfo instance will be redacted.
 *
 * @hide
 */
// TODO: Do not store WifiInfo and subscription ID in VcnTransportInfo anymore
public class VcnTransportInfo implements TransportInfo, Parcelable {
    @Nullable private final WifiInfo mWifiInfo;
    private final int mSubId;
    private final int mMinUdpPort4500NatTimeoutSeconds;

    public VcnTransportInfo(@NonNull WifiInfo wifiInfo) {
        this(wifiInfo, INVALID_SUBSCRIPTION_ID, MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET);
    }

    public VcnTransportInfo(@NonNull WifiInfo wifiInfo, int minUdpPort4500NatTimeoutSeconds) {
        this(wifiInfo, INVALID_SUBSCRIPTION_ID, minUdpPort4500NatTimeoutSeconds);
    }

    public VcnTransportInfo(int subId) {
        this(null /* wifiInfo */, subId, MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET);
    }

    public VcnTransportInfo(int subId, int minUdpPort4500NatTimeoutSeconds) {
        this(null /* wifiInfo */, subId, minUdpPort4500NatTimeoutSeconds);
    }

    private VcnTransportInfo(
            @Nullable WifiInfo wifiInfo, int subId, int minUdpPort4500NatTimeoutSeconds) {
        mWifiInfo = wifiInfo;
        mSubId = subId;
        mMinUdpPort4500NatTimeoutSeconds = minUdpPort4500NatTimeoutSeconds;
    }

    /**
     * Get the {@link WifiInfo} for this VcnTransportInfo.
     *
     * <p>If the underlying Network for the associated VCN is Cellular, returns null.
     *
     * @return the WifiInfo if there is an underlying WiFi connection, else null.
     */
    @Nullable
    public WifiInfo getWifiInfo() {
        return mWifiInfo;
    }

    /**
     * Get the subId for the VCN Network associated with this VcnTransportInfo.
     *
     * <p>If the underlying Network for the associated VCN is WiFi, returns {@link
     * SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     *
     * @return the Subscription ID if a cellular underlying Network is present, else {@link
     *     android.telephony.SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     */
    public int getSubId() {
        return mSubId;
    }

    /**
     * Get the VCN provided UDP port 4500 NAT timeout
     *
     * @return the UDP 4500 NAT timeout, or
     *     VcnGatewayConnectionConfig.MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET if not set.
     */
    public int getMinUdpPort4500NatTimeoutSeconds() {
        return mMinUdpPort4500NatTimeoutSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWifiInfo, mSubId, mMinUdpPort4500NatTimeoutSeconds);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VcnTransportInfo)) return false;
        final VcnTransportInfo that = (VcnTransportInfo) o;
        return Objects.equals(mWifiInfo, that.mWifiInfo)
                && mSubId == that.mSubId
                && mMinUdpPort4500NatTimeoutSeconds == that.mMinUdpPort4500NatTimeoutSeconds;
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @NonNull
    public TransportInfo makeCopy(long redactions) {
        if ((redactions & NetworkCapabilities.REDACT_FOR_NETWORK_SETTINGS) != 0) {
            return new VcnTransportInfo(
                    null, INVALID_SUBSCRIPTION_ID, MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET);
        }

        return new VcnTransportInfo(
                (mWifiInfo == null) ? null : mWifiInfo.makeCopy(redactions),
                mSubId,
                mMinUdpPort4500NatTimeoutSeconds);
    }

    @Override
    public long getApplicableRedactions() {
        long redactions = REDACT_FOR_NETWORK_SETTINGS;

        // Add additional wifi redactions if necessary
        if (mWifiInfo != null) {
            redactions |= mWifiInfo.getApplicableRedactions();
        }

        return redactions;
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSubId);
        dest.writeParcelable(mWifiInfo, flags);
        dest.writeInt(mMinUdpPort4500NatTimeoutSeconds);
    }

    @Override
    public String toString() {
        return "VcnTransportInfo { mWifiInfo = " + mWifiInfo + ", mSubId = " + mSubId + " }";
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<VcnTransportInfo> CREATOR =
            new Creator<VcnTransportInfo>() {
                public VcnTransportInfo createFromParcel(Parcel in) {
                    final int subId = in.readInt();
                    final WifiInfo wifiInfo =
                            in.readParcelable(null, android.net.wifi.WifiInfo.class);
                    final int minUdpPort4500NatTimeoutSeconds = in.readInt();

                    // If all fields are their null values, return null TransportInfo to avoid
                    // leaking information about this being a VCN Network (instead of macro
                    // cellular, etc)
                    if (wifiInfo == null
                            && subId == INVALID_SUBSCRIPTION_ID
                            && minUdpPort4500NatTimeoutSeconds
                                    == MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET) {
                        return null;
                    }

                    return new VcnTransportInfo(wifiInfo, subId, minUdpPort4500NatTimeoutSeconds);
                }

                public VcnTransportInfo[] newArray(int size) {
                    return new VcnTransportInfo[size];
                }
            };

    /** This class can be used to construct a {@link VcnTransportInfo}. */
    public static final class Builder {
        private int mMinUdpPort4500NatTimeoutSeconds = MIN_UDP_PORT_4500_NAT_TIMEOUT_UNSET;

        /** Construct Builder */
        public Builder() {}

        /**
         * Sets the maximum supported IKEv2/IPsec NATT keepalive timeout.
         *
         * <p>This is used as a power-optimization hint for other IKEv2/IPsec use cases (e.g. VPNs,
         * or IWLAN) to reduce the necessary keepalive frequency, thus conserving power and data.
         *
         * @param minUdpPort4500NatTimeoutSeconds the maximum keepalive timeout supported by the VCN
         *     Gateway Connection, generally the minimum duration a NAT mapping is cached on the VCN
         *     Gateway.
         * @return this {@link Builder} instance, for chaining
         */
        @NonNull
        public Builder setMinUdpPort4500NatTimeoutSeconds(
                @IntRange(from = MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS)
                        int minUdpPort4500NatTimeoutSeconds) {
            Preconditions.checkArgument(
                    minUdpPort4500NatTimeoutSeconds >= MIN_UDP_PORT_4500_NAT_TIMEOUT_SECONDS,
                    "Timeout must be at least 120s");

            mMinUdpPort4500NatTimeoutSeconds = minUdpPort4500NatTimeoutSeconds;
            return Builder.this;
        }

        /** Build a VcnTransportInfo instance */
        @NonNull
        public VcnTransportInfo build() {
            return new VcnTransportInfo(
                    null /* wifiInfo */, INVALID_SUBSCRIPTION_ID, mMinUdpPort4500NatTimeoutSeconds);
        }
    }
}
