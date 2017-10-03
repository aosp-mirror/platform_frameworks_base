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

import android.net.wifi.ScanResult;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Defines the ranging request to other devices. The ranging request is built using
 * {@link RangingRequest.Builder}.
 * A ranging request is executed using
 * {@link WifiRttManager#startRanging(RangingRequest, RangingResultCallback, Handler)}.
 * <p>
 * The ranging request is a batch request - specifying a set of devices (specified using
 * {@link RangingRequest.Builder#addAp(ScanResult)} and
 * {@link RangingRequest.Builder#addAps(List)}).
 *
 * @hide RTT_API
 */
public final class RangingRequest implements Parcelable {
    private static final int MAX_PEERS = 10;

    /**
     * Returns the maximum number of peers to range which can be specified in a single {@code
     * RangingRequest}. The limit applies no matter how the peers are added to the request, e.g.
     * through {@link RangingRequest.Builder#addAp(ScanResult)} or
     * {@link RangingRequest.Builder#addAps(List)}.
     *
     * @return Maximum number of peers.
     */
    public static int getMaxPeers() {
        return MAX_PEERS;
    }

    /** @hide */
    public final List<RttPeer> mRttPeers;

    /** @hide */
    private RangingRequest(List<RttPeer> rttPeers) {
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

    public static final Creator<RangingRequest> CREATOR = new Creator<RangingRequest>() {
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
        StringJoiner sj = new StringJoiner(", ", "RangingRequest: mRttPeers=[", ",");
        for (RttPeer rp : mRttPeers) {
            sj.add(rp.toString());
        }
        return sj.toString();
    }

    /** @hide */
    public void enforceValidity() {
        if (mRttPeers.size() > MAX_PEERS) {
            throw new IllegalArgumentException(
                    "Ranging to too many peers requested. Use getMaxPeers() API to get limit.");
        }
    }

    /**
     * Builder class used to construct {@link RangingRequest} objects.
     */
    public static final class Builder {
        private List<RttPeer> mRttPeers = new ArrayList<>();

        /**
         * Add the device specified by the {@link ScanResult} to the list of devices with
         * which to measure range. The total number of results added to a request cannot exceed the
         * limit specified by {@link #getMaxPeers()}.
         *
         * @param apInfo Information of an Access Point (AP) obtained in a Scan Result.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder addAp(ScanResult apInfo) {
            if (apInfo == null) {
                throw new IllegalArgumentException("Null ScanResult!");
            }
            mRttPeers.add(new RttPeerAp(apInfo));
            return this;
        }

        /**
         * Add the devices specified by the {@link ScanResult}s to the list of devices with
         * which to measure range. The total number of results added to a request cannot exceed the
         * limit specified by {@link #getMaxPeers()}.
         *
         * @param apInfos Information of an Access Points (APs) obtained in a Scan Result.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder addAps(List<ScanResult> apInfos) {
            if (apInfos == null) {
                throw new IllegalArgumentException("Null list of ScanResults!");
            }
            for (ScanResult scanResult : apInfos) {
                addAp(scanResult);
            }
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

    /** @hide */
    public interface RttPeer {
        // empty (marker interface)
    }

    /** @hide */
    public static class RttPeerAp implements RttPeer, Parcelable {
        public final ScanResult scanResult;

        public RttPeerAp(ScanResult scanResult) {
            this.scanResult = scanResult;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            scanResult.writeToParcel(dest, flags);
        }

        public static final Creator<RttPeerAp> CREATOR = new Creator<RttPeerAp>() {
            @Override
            public RttPeerAp[] newArray(int size) {
                return new RttPeerAp[size];
            }

            @Override
            public RttPeerAp createFromParcel(Parcel in) {
                return new RttPeerAp(ScanResult.CREATOR.createFromParcel(in));
            }
        };

        @Override
        public String toString() {
            return new StringBuilder("RttPeerAp: scanResult=").append(
                    scanResult.toString()).toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof RttPeerAp)) {
                return false;
            }

            RttPeerAp lhs = (RttPeerAp) o;

            // Note: the only thing which matters for the request identity is the BSSID of the AP
            return TextUtils.equals(scanResult.BSSID, lhs.scanResult.BSSID);
        }

        @Override
        public int hashCode() {
            return scanResult.hashCode();
        }
    }
}