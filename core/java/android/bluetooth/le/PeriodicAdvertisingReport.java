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

package android.bluetooth.le;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * PeriodicAdvertisingReport for Bluetooth LE synchronized advertising.
 *
 * @hide
 */
public final class PeriodicAdvertisingReport implements Parcelable {

    /**
     * The data returned is complete
     */
    public static final int DATA_COMPLETE = 0;

    /**
     * The data returned is incomplete. The controller was unsuccessfull to
     * receive all chained packets, returning only partial data.
     */
    public static final int DATA_INCOMPLETE_TRUNCATED = 2;

    private int mSyncHandle;
    private int mTxPower;
    private int mRssi;
    private int mDataStatus;

    // periodic advertising data.
    @Nullable
    private ScanRecord mData;

    // Device timestamp when the result was last seen.
    private long mTimestampNanos;

    /**
     * Constructor of periodic advertising result.
     */
    public PeriodicAdvertisingReport(int syncHandle, int txPower, int rssi,
            int dataStatus, ScanRecord data) {
        mSyncHandle = syncHandle;
        mTxPower = txPower;
        mRssi = rssi;
        mDataStatus = dataStatus;
        mData = data;
    }

    private PeriodicAdvertisingReport(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSyncHandle);
        dest.writeInt(mTxPower);
        dest.writeInt(mRssi);
        dest.writeInt(mDataStatus);
        if (mData != null) {
            dest.writeInt(1);
            dest.writeByteArray(mData.getBytes());
        } else {
            dest.writeInt(0);
        }
    }

    private void readFromParcel(Parcel in) {
        mSyncHandle = in.readInt();
        mTxPower = in.readInt();
        mRssi = in.readInt();
        mDataStatus = in.readInt();
        if (in.readInt() == 1) {
            mData = ScanRecord.parseFromBytes(in.createByteArray());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the synchronization handle.
     */
    public int getSyncHandle() {
        return mSyncHandle;
    }

    /**
     * Returns the transmit power in dBm. The valid range is [-127, 126]. Value
     * of 127 means information was not available.
     */
    public int getTxPower() {
        return mTxPower;
    }

    /**
     * Returns the received signal strength in dBm. The valid range is [-127, 20].
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * Returns the data status. Can be one of {@link PeriodicAdvertisingReport#DATA_COMPLETE}
     * or {@link PeriodicAdvertisingReport#DATA_INCOMPLETE_TRUNCATED}.
     */
    public int getDataStatus() {
        return mDataStatus;
    }

    /**
     * Returns the data contained in this periodic advertising report.
     */
    @Nullable
    public ScanRecord getData() {
        return mData;
    }

    /**
     * Returns timestamp since boot when the scan record was observed.
     */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSyncHandle, mTxPower, mRssi, mDataStatus, mData, mTimestampNanos);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PeriodicAdvertisingReport other = (PeriodicAdvertisingReport) obj;
        return (mSyncHandle == other.mSyncHandle)
                && (mTxPower == other.mTxPower)
                && (mRssi == other.mRssi)
                && (mDataStatus == other.mDataStatus)
                && Objects.equals(mData, other.mData)
                && (mTimestampNanos == other.mTimestampNanos);
    }

    @Override
    public String toString() {
        return "PeriodicAdvertisingReport{syncHandle=" + mSyncHandle
                + ", txPower=" + mTxPower + ", rssi=" + mRssi + ", dataStatus=" + mDataStatus
                + ", data=" + Objects.toString(mData) + ", timestampNanos=" + mTimestampNanos + '}';
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PeriodicAdvertisingReport> CREATOR =
            new Creator<PeriodicAdvertisingReport>() {
                @Override
                public PeriodicAdvertisingReport createFromParcel(Parcel source) {
                    return new PeriodicAdvertisingReport(source);
                }

                @Override
                public PeriodicAdvertisingReport[] newArray(int size) {
                    return new PeriodicAdvertisingReport[size];
                }
            };
}
