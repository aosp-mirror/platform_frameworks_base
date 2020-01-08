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

import static android.telephony.TelephonyManager.NETWORK_TYPE_EDGE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_GPRS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UMTS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;


/**
 * Represents the neighboring cell information, including
 * Received Signal Strength and Cell ID location.
 *
 * @deprecated This class should not be used by any app targeting
 *     {@link android.os.Build.VERSION_CODES#Q Android Q} or higher. Instead callers should use
 *     {@link android.telephony.CellInfo CellInfo}.
 */
@Deprecated
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

    /**
     * In GSM, mRssi is the Received RSSI;
     * In UMTS, mRssi is the Level index of CPICH Received Signal Code Power
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mRssi;
    /**
     * CID in 16 bits format in GSM. Return UNKNOWN_CID in UMTS and CMDA.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mCid;
    /**
     * LAC in 16 bits format in GSM. Return UNKNOWN_CID in UMTS and CMDA.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mLac;
    /**
     * Primary Scrambling Code in 9 bits format in UMTS
     * Return UNKNOWN_CID in GSM and CMDA.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mPsc;
    /**
     * Radio network type, value is one of following
     * TelephonyManager.NETWORK_TYPE_XXXXXX.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mNetworkType;

    /**
     * Empty constructor.  Initializes the RSSI and CID.
     *
     * NeighboringCellInfo is one time shot for the neighboring cells based on
     * the radio network type at that moment. Its constructor needs radio network
     * type.
     *
     * @deprecated by {@link #NeighboringCellInfo(int, String, int)}
     */
    @Deprecated
    public NeighboringCellInfo() {
        mRssi = UNKNOWN_RSSI;
        mLac = UNKNOWN_CID;
        mCid = UNKNOWN_CID;
        mPsc = UNKNOWN_CID;
        mNetworkType = NETWORK_TYPE_UNKNOWN;
    }

    /**
     * Initialize the object from rssi and cid.
     *
     * NeighboringCellInfo is one time shot for the neighboring cells based on
     * the radio network type at that moment. Its constructor needs radio network
     * type.
     *
     * @deprecated by {@link #NeighboringCellInfo(int, String, int)}
     */
    @Deprecated
    public NeighboringCellInfo(int rssi, int cid) {
        mRssi = rssi;
        mCid = cid;
    }

    /** @hide */
    public NeighboringCellInfo(final CellInfoGsm info) {
        mNetworkType = TelephonyManager.NETWORK_TYPE_GPRS;

        mRssi = info.getCellSignalStrength().getAsuLevel();
        if (mRssi == Integer.MAX_VALUE) mRssi = UNKNOWN_RSSI;

        mLac = info.getCellIdentity().getLac();
        if (mLac == Integer.MAX_VALUE) mLac = UNKNOWN_CID;

        mCid = info.getCellIdentity().getCid();
        if (mCid == Integer.MAX_VALUE) mCid = UNKNOWN_CID;

        mPsc = UNKNOWN_CID;
    }

    /** @hide */
    public NeighboringCellInfo(final CellInfoWcdma info) {
        mNetworkType = TelephonyManager.NETWORK_TYPE_UMTS;

        mRssi = info.getCellSignalStrength().getAsuLevel();
        if (mRssi == Integer.MAX_VALUE) mRssi = UNKNOWN_RSSI;

        mLac = info.getCellIdentity().getLac();
        if (mLac == Integer.MAX_VALUE) mLac = UNKNOWN_CID;

        mCid = info.getCellIdentity().getCid();
        if (mCid == Integer.MAX_VALUE) mCid = UNKNOWN_CID;

        mPsc = info.getCellIdentity().getPsc();
        if (mPsc == Integer.MAX_VALUE) mPsc = UNKNOWN_CID;
    }

    /**
     * Initialize the object from rssi, location string, and radioType
     * radioType is one of following
     * {@link TelephonyManager#NETWORK_TYPE_GPRS TelephonyManager.NETWORK_TYPE_GPRS},
     * {@link TelephonyManager#NETWORK_TYPE_EDGE TelephonyManager.NETWORK_TYPE_EDGE},
     * {@link TelephonyManager#NETWORK_TYPE_UMTS TelephonyManager.NETWORK_TYPE_UMTS},
     * {@link TelephonyManager#NETWORK_TYPE_HSDPA TelephonyManager.NETWORK_TYPE_HSDPA},
     * {@link TelephonyManager#NETWORK_TYPE_HSUPA TelephonyManager.NETWORK_TYPE_HSUPA},
     * and {@link TelephonyManager#NETWORK_TYPE_HSPA TelephonyManager.NETWORK_TYPE_HSPA}.
     */
    public NeighboringCellInfo(int rssi, String location, int radioType) {
        // set default value
        mRssi = rssi;
        mNetworkType = NETWORK_TYPE_UNKNOWN;
        mPsc = UNKNOWN_CID;
        mLac = UNKNOWN_CID;
        mCid = UNKNOWN_CID;


        // pad location string with leading "0"
        int l = location.length();
        if (l > 8) return;
        if (l < 8) {
            for (int i = 0; i < (8-l); i++) {
                location = "0" + location;
            }
        }
        // TODO - handle LTE and eHRPD (or find they can't be supported)
        try {// set LAC/CID or PSC based on radioType
            switch (radioType) {
            case NETWORK_TYPE_GPRS:
            case NETWORK_TYPE_EDGE:
                mNetworkType = radioType;
                // check if 0xFFFFFFFF for UNKNOWN_CID
                if (!location.equalsIgnoreCase("FFFFFFFF")) {
                    mCid = Integer.parseInt(location.substring(4), 16);
                    mLac = Integer.parseInt(location.substring(0, 4), 16);
                }
                break;
            case NETWORK_TYPE_UMTS:
            case NETWORK_TYPE_HSDPA:
            case NETWORK_TYPE_HSUPA:
            case NETWORK_TYPE_HSPA:
                mNetworkType = radioType;
                mPsc = Integer.parseInt(location, 16);
                break;
            }
        } catch (NumberFormatException e) {
            // parsing location error
            mPsc = UNKNOWN_CID;
            mLac = UNKNOWN_CID;
            mCid = UNKNOWN_CID;
            mNetworkType = NETWORK_TYPE_UNKNOWN;
        }
    }

    /**
     * Initialize the object from a parcel.
     */
    public NeighboringCellInfo(Parcel in) {
        mRssi = in.readInt();
        mLac = in.readInt();
        mCid = in.readInt();
        mPsc = in.readInt();
        mNetworkType = in.readInt();
    }

    /**
     * @return received signal strength or UNKNOWN_RSSI if unknown
     *
     * For GSM, it is in "asu" ranging from 0 to 31 (dBm = -113 + 2*asu)
     * 0 means "-113 dBm or less" and 31 means "-51 dBm or greater"
     * For UMTS, it is the Level index of CPICH RSCP defined in TS 25.125
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * @return LAC in GSM, 0xffff max legal value
     *  UNKNOWN_CID if in UMTS or CMDA or unknown
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return cell id in GSM, 0xffff max legal value
     *  UNKNOWN_CID if in UMTS or CDMA or unknown
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return Primary Scrambling Code in 9 bits format in UMTS, 0x1ff max value
     *  UNKNOWN_CID if in GSM or CMDA or unknown
     */
    public int getPsc() {
        return mPsc;
    }

    /**
     * @return Radio network type while neighboring cell location is stored.
     *
     * Return {@link TelephonyManager#NETWORK_TYPE_UNKNOWN TelephonyManager.NETWORK_TYPE_UNKNOWN}
     * means that the location information is unavailable.
     *
     * Return {@link TelephonyManager#NETWORK_TYPE_GPRS TelephonyManager.NETWORK_TYPE_GPRS} or
     * {@link TelephonyManager#NETWORK_TYPE_EDGE TelephonyManager.NETWORK_TYPE_EDGE}
     * means that Neighboring Cell information is stored for GSM network, in
     * which {@link NeighboringCellInfo#getLac NeighboringCellInfo.getLac} and
     * {@link NeighboringCellInfo#getCid NeighboringCellInfo.getCid} should be
     * called to access location.
     *
     * Return {@link TelephonyManager#NETWORK_TYPE_UMTS TelephonyManager.NETWORK_TYPE_UMTS},
     * {@link TelephonyManager#NETWORK_TYPE_HSDPA TelephonyManager.NETWORK_TYPE_HSDPA},
     * {@link TelephonyManager#NETWORK_TYPE_HSUPA TelephonyManager.NETWORK_TYPE_HSUPA},
     * or {@link TelephonyManager#NETWORK_TYPE_HSPA TelephonyManager.NETWORK_TYPE_HSPA}
     * means that Neighboring Cell information is stored for UMTS network, in
     * which {@link NeighboringCellInfo#getPsc NeighboringCellInfo.getPsc}
     * should be called to access location.
     */
    public int getNetworkType() {
        return mNetworkType;
    }
    /**
     * Set the cell id.
     *
     * NeighboringCellInfo is a one time shot for the neighboring cells based on
     * the radio network type at that moment. It shouldn't be changed after
     * creation.
     *
     * @deprecated cid value passed as in location parameter passed to constructor
     *              {@link #NeighboringCellInfo(int, String, int)}
     */
    @Deprecated
    public void setCid(int cid) {
        mCid = cid;
    }

    /**
     * Set the signal strength of the cell.
     *
     * NeighboringCellInfo is a one time shot for the neighboring cells based on
     * the radio network type at that moment. It shouldn't be changed after
     * creation.
     *
     * @deprecated initial rssi value passed as parameter to constructor
     *              {@link #NeighboringCellInfo(int, String, int)}
     */
    @Deprecated
    public void setRssi(int rssi) {
        mRssi = rssi;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[");
        if (mPsc != UNKNOWN_CID) {
            sb.append(Integer.toHexString(mPsc))
                    .append("@").append(((mRssi == UNKNOWN_RSSI)? "-" : mRssi));
        } else if(mLac != UNKNOWN_CID && mCid != UNKNOWN_CID) {
            sb.append(Integer.toHexString(mLac))
                    .append(Integer.toHexString(mCid))
                    .append("@").append(((mRssi == UNKNOWN_RSSI)? "-" : mRssi));
        }
        sb.append("]");

        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRssi);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mPsc);
        dest.writeInt(mNetworkType);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<NeighboringCellInfo> CREATOR
    = new Parcelable.Creator<NeighboringCellInfo>() {
        public NeighboringCellInfo createFromParcel(Parcel in) {
            return new NeighboringCellInfo(in);
        }

        public NeighboringCellInfo[] newArray(int size) {
            return new NeighboringCellInfo[size];
        }
    };
}
