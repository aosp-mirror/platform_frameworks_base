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

import android.net.wifi.aware.PeerHandle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import libcore.util.HexEncoding;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Ranging result for a request started by
 * {@link WifiRttManager#startRanging(RangingRequest, RangingResultCallback, Handler)}. Results are
 * returned in {@link RangingResultCallback#onRangingResults(List)}.
 * <p>
 * A ranging result is the distance measurement result for a single device specified in the
 * {@link RangingRequest}.
 *
 * @hide RTT_API
 */
public final class RangingResult implements Parcelable {
    private static final String TAG = "RangingResult";

    private final int mStatus;
    private final byte[] mMac;
    private final PeerHandle mPeerHandle;
    private final int mDistanceCm;
    private final int mDistanceStdDevCm;
    private final int mRssi;
    private final long mTimestamp;

    /** @hide */
    public RangingResult(int status, byte[] mac, int distanceCm, int distanceStdDevCm, int rssi,
            long timestamp) {
        mStatus = status;
        mMac = mac;
        mPeerHandle = null;
        mDistanceCm = distanceCm;
        mDistanceStdDevCm = distanceStdDevCm;
        mRssi = rssi;
        mTimestamp = timestamp;
    }

    /** @hide */
    public RangingResult(int status, PeerHandle peerHandle, int distanceCm, int distanceStdDevCm,
            int rssi, long timestamp) {
        mStatus = status;
        mMac = null;
        mPeerHandle = peerHandle;
        mDistanceCm = distanceCm;
        mDistanceStdDevCm = distanceStdDevCm;
        mRssi = rssi;
        mTimestamp = timestamp;
    }

    /**
     * @return The status of ranging measurement: {@link RangingResultCallback#STATUS_SUCCESS} in
     * case of success, and {@link RangingResultCallback#STATUS_FAIL} in case of failure.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * @return The MAC address of the device whose range measurement was requested. Will correspond
     * to the MAC address of the device in the {@link RangingRequest}.
     * <p>
     * Will return a {@code null} for results corresponding to requests issued using a {@code
     * PeerHandle}, i.e. using the {@link RangingRequest.Builder#addWifiAwarePeer(PeerHandle)} API.
     * <p>
     * Valid whether {@link #getStatus()} is SUCCESS or FAIL.
     */
    public byte[] getMacAddress() {
        return mMac;
    }

    /**
     * @return The PeerHandle of the device whose reange measurement was requested. Will correspond
     * to the PeerHandle of the devices requested using
     * {@link RangingRequest.Builder#addWifiAwarePeer(PeerHandle)}.
     * <p>
     * Will return a {@code null} for results corresponding to requests issued using a MAC address.
     * <p>
     *
     * Valid whether {@link #getStatus()} is SUCCESS or FAIL.
     */
    public PeerHandle getPeerHandle() {
        return mPeerHandle;
    }

    /**
     * @return The distance (in cm) to the device specified by {@link #getMacAddress()}.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link RangingResultCallback#STATUS_SUCCESS}.
     */
    public int getDistanceCm() {
        if (mStatus != RangingResultCallback.STATUS_SUCCESS) {
            Log.e(TAG, "getDistanceCm(): invalid value retrieved");
        }
        return mDistanceCm;
    }

    /**
     * @return The standard deviation of the measured distance (in cm) to the device specified by
     * {@link #getMacAddress()}. The standard deviation is calculated over the measurements
     * executed in a single RTT burst.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link RangingResultCallback#STATUS_SUCCESS}.
     */
    public int getDistanceStdDevCm() {
        if (mStatus != RangingResultCallback.STATUS_SUCCESS) {
            Log.e(TAG, "getDistanceStdDevCm(): invalid value retrieved");
        }
        return mDistanceStdDevCm;
    }

    /**
     * @return The average RSSI (in units of -0.5dB) observed during the RTT measurement.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link RangingResultCallback#STATUS_SUCCESS}.
     */
    public int getRssi() {
        if (mStatus != RangingResultCallback.STATUS_SUCCESS) {
            // TODO: should this be an exception?
            Log.e(TAG, "getRssi(): invalid value retrieved");
        }
        return mRssi;
    }

    /**
     * @return The timestamp, in us since boot, at which the ranging operation was performed.
     * <p>
     * Only valid if {@link #getStatus()} returns {@link RangingResultCallback#STATUS_SUCCESS}.
     */
    public long getRangingTimestampUs() {
        return mTimestamp;
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeByteArray(mMac);
        if (mPeerHandle == null) {
            dest.writeBoolean(false);
        } else {
            dest.writeBoolean(true);
            dest.writeInt(mPeerHandle.peerId);
        }
        dest.writeInt(mDistanceCm);
        dest.writeInt(mDistanceStdDevCm);
        dest.writeInt(mRssi);
        dest.writeLong(mTimestamp);
    }

    /** @hide */
    public static final Creator<RangingResult> CREATOR = new Creator<RangingResult>() {
        @Override
        public RangingResult[] newArray(int size) {
            return new RangingResult[size];
        }

        @Override
        public RangingResult createFromParcel(Parcel in) {
            int status = in.readInt();
            byte[] mac = in.createByteArray();
            boolean peerHandlePresent = in.readBoolean();
            PeerHandle peerHandle = null;
            if (peerHandlePresent) {
                peerHandle = new PeerHandle(in.readInt());
            }
            int distanceCm = in.readInt();
            int distanceStdDevCm = in.readInt();
            int rssi = in.readInt();
            long timestamp = in.readLong();
            if (peerHandlePresent) {
                return new RangingResult(status, peerHandle, distanceCm, distanceStdDevCm, rssi,
                        timestamp);
            } else {
                return new RangingResult(status, mac, distanceCm, distanceStdDevCm, rssi,
                        timestamp);
            }
        }
    };

    /** @hide */
    @Override
    public String toString() {
        return new StringBuilder("RangingResult: [status=").append(mStatus).append(", mac=").append(
                mMac == null ? "<null>" : new String(HexEncoding.encodeToString(mMac))).append(
                ", peerHandle=").append(mPeerHandle == null ? "<null>" : mPeerHandle.peerId).append(
                ", distanceCm=").append(mDistanceCm).append(", distanceStdDevCm=").append(
                mDistanceStdDevCm).append(", rssi=").append(mRssi).append(", timestamp=").append(
                mTimestamp).append("]").toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RangingResult)) {
            return false;
        }

        RangingResult lhs = (RangingResult) o;

        return mStatus == lhs.mStatus && Arrays.equals(mMac, lhs.mMac) && Objects.equals(
                mPeerHandle, lhs.mPeerHandle) && mDistanceCm == lhs.mDistanceCm
                && mDistanceStdDevCm == lhs.mDistanceStdDevCm && mRssi == lhs.mRssi
                && mTimestamp == lhs.mTimestamp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, mMac, mPeerHandle, mDistanceCm, mDistanceStdDevCm, mRssi,
                mTimestamp);
    }
}