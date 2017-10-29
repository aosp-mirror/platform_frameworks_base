/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.telephony.Rlog;
import android.text.TextUtils;

import java.util.Objects;

/**
 * CellIdentity is to represent a unique CDMA cell
 */
public final class CellIdentityCdma implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthCdma";
    private static final boolean DBG = false;

    // Network Id 0..65535
    private final int mNetworkId;
    // CDMA System Id 0..32767
    private final int mSystemId;
    // Base Station Id 0..65535
    private final int mBasestationId;
    /**
     * Longitude is a decimal number as specified in 3GPP2 C.S0005-A v6.0.
     * It is represented in units of 0.25 seconds and ranges from -2592000
     * to 2592000, both values inclusive (corresponding to a range of -180
     * to +180 degrees).
     */
    private final int mLongitude;
    /**
     * Latitude is a decimal number as specified in 3GPP2 C.S0005-A v6.0.
     * It is represented in units of 0.25 seconds and ranges from -1296000
     * to 1296000, both values inclusive (corresponding to a range of -90
     * to +90 degrees).
     */
    private final int mLatitude;
    // long alpha Operator Name String or Enhanced Operator Name String
    private final String mAlphaLong;
    // short alpha Operator Name String or Enhanced Operator Name String
    private final String mAlphaShort;

    /**
     * @hide
     */
    public CellIdentityCdma() {
        mNetworkId = Integer.MAX_VALUE;
        mSystemId = Integer.MAX_VALUE;
        mBasestationId = Integer.MAX_VALUE;
        mLongitude = Integer.MAX_VALUE;
        mLatitude = Integer.MAX_VALUE;
        mAlphaLong = null;
        mAlphaShort = null;
    }

    /**
     * public constructor
     * @param nid Network Id 0..65535
     * @param sid CDMA System Id 0..32767
     * @param bid Base Station Id 0..65535
     * @param lon Longitude is a decimal number ranges from -2592000
     *        to 2592000
     * @param lat Latitude is a decimal number ranges from -1296000
     *        to 1296000
     *
     * @hide
     */
    public CellIdentityCdma (int nid, int sid, int bid, int lon, int lat) {
        this(nid, sid, bid, lon, lat, null, null);
    }

    /**
     * public constructor
     * @param nid Network Id 0..65535
     * @param sid CDMA System Id 0..32767
     * @param bid Base Station Id 0..65535
     * @param lon Longitude is a decimal number ranges from -2592000
     *        to 2592000
     * @param lat Latitude is a decimal number ranges from -1296000
     *        to 1296000
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityCdma (int nid, int sid, int bid, int lon, int lat, String alphal,
                             String alphas) {
        mNetworkId = nid;
        mSystemId = sid;
        mBasestationId = bid;
        mLongitude = lon;
        mLatitude = lat;
        mAlphaLong = alphal;
        mAlphaShort = alphas;
    }

    private CellIdentityCdma(CellIdentityCdma cid) {
        this(cid.mNetworkId, cid.mSystemId, cid.mBasestationId, cid.mLongitude, cid.mLatitude,
                cid.mAlphaLong, cid.mAlphaShort);
    }

    CellIdentityCdma copy() {
        return new CellIdentityCdma(this);
    }

    /**
     * @return Network Id 0..65535, Integer.MAX_VALUE if unknown
     */
    public int getNetworkId() {
        return mNetworkId;
    }

    /**
     * @return System Id 0..32767, Integer.MAX_VALUE if unknown
     */
    public int getSystemId() {
        return mSystemId;
    }

    /**
     * @return Base Station Id 0..65535, Integer.MAX_VALUE if unknown
     */
    public int getBasestationId() {
        return mBasestationId;
    }

    /**
     * @return Base station longitude, which is a decimal number as
     * specified in 3GPP2 C.S0005-A v6.0. It is represented in units
     * of 0.25 seconds and ranges from -2592000 to 2592000, both
     * values inclusive (corresponding to a range of -180
     * to +180 degrees). Integer.MAX_VALUE if unknown.
     */
    public int getLongitude() {
        return mLongitude;
    }

    /**
     * @return Base station latitude, which is a decimal number as
     * specified in 3GPP2 C.S0005-A v6.0. It is represented in units
     * of 0.25 seconds and ranges from -1296000 to 1296000, both
     * values inclusive (corresponding to a range of -90
     * to +90 degrees). Integer.MAX_VALUE if unknown.
     */
    public int getLatitude() {
        return mLatitude;
    }

    /**
     * @return The long alpha tag associated with the current scan result (may be the operator
     * name string or extended operator name string). May be null if unknown.
     */
    public CharSequence getOperatorAlphaLong() {
        return mAlphaLong;
    }

    /**
     * @return The short alpha tag associated with the current scan result (may be the operator
     * name string or extended operator name string).  May be null if unknown.
     */
    public CharSequence getOperatorAlphaShort() {
        return mAlphaShort;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkId, mSystemId, mBasestationId, mLatitude, mLongitude,
                mAlphaLong, mAlphaShort);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CellIdentityCdma)) {
            return false;
        }

        CellIdentityCdma o = (CellIdentityCdma) other;

        return mNetworkId == o.mNetworkId &&
                mSystemId == o.mSystemId &&
                mBasestationId == o.mBasestationId &&
                mLatitude == o.mLatitude &&
                mLongitude == o.mLongitude &&
                TextUtils.equals(mAlphaLong, o.mAlphaLong) &&
                TextUtils.equals(mAlphaShort, o.mAlphaShort);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CellIdentityCdma:{");
        sb.append(" mNetworkId="); sb.append(mNetworkId);
        sb.append(" mSystemId="); sb.append(mSystemId);
        sb.append(" mBasestationId="); sb.append(mBasestationId);
        sb.append(" mLongitude="); sb.append(mLongitude);
        sb.append(" mLatitude="); sb.append(mLatitude);
        sb.append(" mAlphaLong="); sb.append(mAlphaLong);
        sb.append(" mAlphaShort="); sb.append(mAlphaShort);
        sb.append("}");

        return sb.toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mNetworkId);
        dest.writeInt(mSystemId);
        dest.writeInt(mBasestationId);
        dest.writeInt(mLongitude);
        dest.writeInt(mLatitude);
        dest.writeString(mAlphaLong);
        dest.writeString(mAlphaShort);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityCdma(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                in.readString(), in.readString());

        if (DBG) log("CellIdentityCdma(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityCdma> CREATOR =
            new Creator<CellIdentityCdma>() {
        @Override
        public CellIdentityCdma createFromParcel(Parcel in) {
            return new CellIdentityCdma(in);
        }

        @Override
        public CellIdentityCdma[] newArray(int size) {
            return new CellIdentityCdma[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
