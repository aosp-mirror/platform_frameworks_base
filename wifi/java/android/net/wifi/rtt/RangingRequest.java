/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi.rtt;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.IdentityChangedListener;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Defines the ranging request to other devices. The ranging request is built using
 * {@link RangingRequest.Builder}.
 * A ranging request is executed using
 * {@link WifiRttManager#startRanging(RangingRequest, java.util.concurrent.Executor, RangingResultCallback)}.
 * <p>
 * The ranging request is a batch request - specifying a set of devices (specified using
 * {@link RangingRequest.Builder#addAccessPoint(ScanResult)} and
 * {@link RangingRequest.Builder#addAccessPoints(List)}).
 */
public final class RangingRequest implements Parcelable {
    private static final int MAX_PEERS = 10;

    /**
     * Returns the maximum number of peers to range which can be specified in a single {@code
     * RangingRequest}. The limit applies no matter how the peers are added to the request, e.g.
     * through {@link RangingRequest.Builder#addAccessPoint(ScanResult)} or
     * {@link RangingRequest.Builder#addAccessPoints(List)}.
     *
     * @return Maximum number of peers.
     */
    public static int getMaxPeers() {
        return MAX_PEERS;
    }

    /** @hide */
    public final List<ResponderConfig> mRttPeers;

    /** @hide */
    private RangingRequest(List<ResponderConfig> rttPeers) {
        mRttPeers = rttPeers;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(mRttPeers);
    }

    public static final @android.annotation.NonNull Creator<RangingRequest> CREATOR = new Creator<RangingRequest>() {
        @Override
        public RangingRequest[] newArray(int size) {
            return new RangingRequest[size];
        }

        @Override
        public RangingRequest createFromParcel(Parcel in) {
            return new RangingRequest(in.readArrayList(null));
        }
    };

    /** @hide */
    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner(", ", "RangingRequest: mRttPeers=[", "]");
        for (ResponderConfig rc : mRttPeers) {
            sj.add(rc.toString());
        }
        return sj.toString();
    }

    /** @hide */
    public void enforceValidity(boolean awareSupported) {
        if (mRttPeers.size() > MAX_PEERS) {
            throw new IllegalArgumentException(
                    "Ranging to too many peers requested. Use getMaxPeers() API to get limit.");
        }

        for (ResponderConfig peer: mRttPeers) {
            if (!peer.isValid(awareSupported)) {
                throw new IllegalArgumentException("Invalid Responder specification");
            }
        }
    }

    /**
     * Builder class used to construct {@link RangingRequest} objects.
     */
    public static final class Builder {
        private List<ResponderConfig> mRttPeers = new ArrayList<>();

        /**
         * Add the device specified by the {@link ScanResult} to the list of devices with
         * which to measure range. The total number of peers added to a request cannot exceed the
         * limit specified by {@link #getMaxPeers()}.
         * <p>
         * Ranging may not be supported if the Access Point does not support IEEE 802.11mc. Use
         * {@link ScanResult#is80211mcResponder()} to verify the Access Point's capabilities. If
         * not supported the result status will be
         * {@link RangingResult#STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC}.
         *
         * @param apInfo Information of an Access Point (AP) obtained in a Scan Result.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder addAccessPoint(@NonNull ScanResult apInfo) {
            if (apInfo == null) {
                throw new IllegalArgumentException("Null ScanResult!");
            }
            return addResponder(ResponderConfig.fromScanResult(apInfo));
        }

        /**
         * Add the devices specified by the {@link ScanResult}s to the list of devices with
         * which to measure range. The total number of peers added to a request cannot exceed the
         * limit specified by {@link #getMaxPeers()}.
         * <p>
         * Ranging may not be supported if the Access Point does not support IEEE 802.11mc. Use
         * {@link ScanResult#is80211mcResponder()} to verify the Access Point's capabilities. If
         * not supported the result status will be
         * {@link RangingResult#STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC}.
         *
         * @param apInfos Information of an Access Points (APs) obtained in a Scan Result.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder addAccessPoints(@NonNull List<ScanResult> apInfos) {
            if (apInfos == null) {
                throw new IllegalArgumentException("Null list of ScanResults!");
            }
            for (ScanResult scanResult : apInfos) {
                addAccessPoint(scanResult);
            }
            return this;
        }

        /**
         * Add the device specified by the {@code peerMacAddress} to the list of devices with
         * which to measure range.
         * <p>
         * The MAC address may be obtained out-of-band from a peer Wi-Fi Aware device. A Wi-Fi
         * Aware device may obtain its MAC address using the {@link IdentityChangedListener}
         * provided to
         * {@link WifiAwareManager#attach(AttachCallback, IdentityChangedListener, Handler)}.
         * <p>
         * Note: in order to use this API the device must support Wi-Fi Aware
         * {@link android.net.wifi.aware}. The peer device which is being ranged to must be
         * configured to publish a service (with any name) with:
         * <li>Type {@link android.net.wifi.aware.PublishConfig#PUBLISH_TYPE_UNSOLICITED}.
         * <li>Ranging enabled
         * {@link android.net.wifi.aware.PublishConfig.Builder#setRangingEnabled(boolean)}.
         *
         * @param peerMacAddress The MAC address of the Wi-Fi Aware peer.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder addWifiAwarePeer(@NonNull MacAddress peerMacAddress) {
            if (peerMacAddress == null) {
                throw new IllegalArgumentException("Null peer MAC address");
            }
            return addResponder(
                    ResponderConfig.fromWifiAwarePeerMacAddressWithDefaults(peerMacAddress));
        }

        /**
         * Add a device specified by a {@link PeerHandle} to the list of devices with which to
         * measure range.
         * <p>
         * The {@link PeerHandle} may be obtained as part of the Wi-Fi Aware discovery process. E.g.
         * using {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle, byte[], List)}.
         * <p>
         * Note: in order to use this API the device must support Wi-Fi Aware
         * {@link android.net.wifi.aware}. The peer device which is being ranged to must be
         * configured to publish a service (with any name) with:
         * <li>Type {@link android.net.wifi.aware.PublishConfig#PUBLISH_TYPE_UNSOLICITED}.
         * <li>Ranging enabled
         * {@link android.net.wifi.aware.PublishConfig.Builder#setRangingEnabled(boolean)}.
         *
         * @param peerHandle The peer handler of the peer Wi-Fi Aware device.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder addWifiAwarePeer(@NonNull PeerHandle peerHandle) {
            if (peerHandle == null) {
                throw new IllegalArgumentException("Null peer handler (identifier)");
            }

            return addResponder(ResponderConfig.fromWifiAwarePeerHandleWithDefaults(peerHandle));
        }

        /**
         * Add the Responder device specified by the {@link ResponderConfig} to the list of devices
         * with which to measure range. The total number of peers added to the request cannot exceed
         * the limit specified by {@link #getMaxPeers()}.
         *
         * @param responder Information on the RTT Responder.
         * @return The builder, to facilitate chaining {@code builder.setXXX(..).setXXX(..)}.
         *
         * @hide
         */
        @SystemApi
        public Builder addResponder(@NonNull ResponderConfig responder) {
            if (responder == null) {
                throw new IllegalArgumentException("Null Responder!");
            }

            mRttPeers.add(responder);
            return this;
        }

        /**
         * Build {@link RangingRequest} given the current configurations made on the
         * builder.
         */
        public RangingRequest build() {
            return new RangingRequest(mRttPeers);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RangingRequest)) {
            return false;
        }

        RangingRequest lhs = (RangingRequest) o;

        return mRttPeers.size() == lhs.mRttPeers.size() && mRttPeers.containsAll(lhs.mRttPeers);
    }

    @Override
    public int hashCode() {
        return mRttPeers.hashCode();
    }
}
