/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the neighboring cell information, including
 * Received Signal Strength and Cell ID location.
 */
public class NeighboringCellInfo implements Parcelable
{
    /**
     * Signal strength is not available
     */
    static final public int UNKNOWN_RSSI = 99;
    /**
     * Cell location is not available
     */
    static final public int UNKNOWN_CID = -1;

    private int mRssi;
    private int mCid;

    /**
     * Empty constructor.  Initializes the RSSI and CID.
     */
    public NeighboringCellInfo() {
        mRssi = UNKNOWN_RSSI;
        mCid = UNKNOWN_CID;
    }

    /**
     * Initialize the object from rssi and cid.
     */
    public NeighboringCellInfo(int rssi, int cid) {
        mRssi = rssi;
        mCid = cid;
    }

    /**
     * Initialize the object from a parcel.
     */
    public NeighboringCellInfo(Parcel in) {
        mRssi = in.readInt();
        mCid = in.readInt();
    }

    /**
     * @return received signal strength in "asu", ranging from 0 - 31,
     * or UNKNOWN_RSSI if unknown
     *
     * For GSM, dBm = -113 + 2*asu,
     * 0 means "-113 dBm or less" and 31 means "-51 dBm or greater"
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * @return cell id, UNKNOWN_CID if unknown, 0xffffffff max legal value
     */
    public int getCid() {
        return mCid;
    }

    /**
     * Set the cell id.
     */
    public void setCid(int cid) {
        mCid = cid;
    }

    /**
     * Set the signal strength of the cell.
     */
    public void setRssi(int rssi) {
        mRssi = rssi;
    }

    @Override
    public String toString() {
        return "["+ ((mCid == UNKNOWN_CID) ? "/" : Integer.toHexString(mCid))
        + " at " + ((mRssi == UNKNOWN_RSSI)? "/" : mRssi) + "]";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRssi);
        dest.writeInt(mCid);
    }

    public static final Parcelable.Creator<NeighboringCellInfo> CREATOR
    = new Parcelable.Creator<NeighboringCellInfo>() {
        public NeighboringCellInfo createFromParcel(Parcel in) {
            return new NeighboringCellInfo(in);
        }

        public NeighboringCellInfo[] newArray(int size) {
            return new NeighboringCellInfo[size];
        }
    };
}


