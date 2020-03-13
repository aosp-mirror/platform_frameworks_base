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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.telephony.cdma.CdmaCellLocation;

import java.util.Objects;

/**
 * CellIdentity is to represent a unique CDMA cell
 */
public final class CellIdentityCdma extends CellIdentity {
    private static final String TAG = CellIdentityCdma.class.getSimpleName();
    private static final boolean DBG = false;

    private static final int NETWORK_ID_MAX = 65535;
    private static final int SYSTEM_ID_MAX = 32767;
    private static final int BASESTATION_ID_MAX = 65535;

    private static final int LONGITUDE_MIN = -2592000;
    private static final int LONGITUDE_MAX = 2592000;

    private static final int LATITUDE_MIN = -1296000;
    private static final int LATITUDE_MAX = 1296000;

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

    /**
     * @hide
     */
    public CellIdentityCdma() {
        super(TAG, CellInfo.TYPE_CDMA, null, null, null, null);
        mNetworkId = CellInfo.UNAVAILABLE;
        mSystemId = CellInfo.UNAVAILABLE;
        mBasestationId = CellInfo.UNAVAILABLE;
        mLongitude = CellInfo.UNAVAILABLE;
        mLatitude = CellInfo.UNAVAILABLE;
        mGlobalCellId = null;
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
    public CellIdentityCdma(int nid, int sid, int bid, int lon, int lat,
            @Nullable String alphal, @Nullable String alphas) {
        super(TAG, CellInfo.TYPE_CDMA, null, null, alphal, alphas);
        mNetworkId = inRangeOrUnavailable(nid, 0, NETWORK_ID_MAX);
        mSystemId = inRangeOrUnavailable(sid, 0, SYSTEM_ID_MAX);
        mBasestationId = inRangeOrUnavailable(bid, 0, BASESTATION_ID_MAX);
        lat = inRangeOrUnavailable(lat, LATITUDE_MIN, LATITUDE_MAX);
        lon = inRangeOrUnavailable(lon, LONGITUDE_MIN, LONGITUDE_MAX);

        if (!isNullIsland(lat, lon)) {
            mLongitude = lon;
            mLatitude = lat;
        } else {
            mLongitude = mLatitude = CellInfo.UNAVAILABLE;
        }
        updateGlobalCellId();
    }

    /** @hide */
    public CellIdentityCdma(@NonNull android.hardware.radio.V1_0.CellIdentityCdma cid) {
        this(cid.networkId, cid.systemId, cid.baseStationId, cid.longitude, cid.latitude, "", "");
    }

    /** @hide */
    public CellIdentityCdma(@NonNull android.hardware.radio.V1_2.CellIdentityCdma cid) {
        this(cid.base.networkId, cid.base.systemId, cid.base.baseStationId, cid.base.longitude,
                cid.base.latitude, cid.operatorNames.alphaLong, cid.operatorNames.alphaShort);
    }

    private CellIdentityCdma(@NonNull CellIdentityCdma cid) {
        this(cid.mNetworkId, cid.mSystemId, cid.mBasestationId, cid.mLongitude, cid.mLatitude,
                cid.mAlphaLong, cid.mAlphaShort);
    }

    @NonNull CellIdentityCdma copy() {
        return new CellIdentityCdma(this);
    }

    /** @hide */
    @Override
    public @NonNull CellIdentityCdma sanitizeLocationInfo() {
        return new CellIdentityCdma(CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                mAlphaLong, mAlphaShort);
    }

    /** @hide */
    @Override
    protected void updateGlobalCellId() {
        mGlobalCellId = null;
        if (mNetworkId == CellInfo.UNAVAILABLE || mSystemId == CellInfo.UNAVAILABLE
                || mBasestationId == CellInfo.UNAVAILABLE) return;

        mGlobalCellId = String.format("%04x%04x%04x", mSystemId, mNetworkId,  mBasestationId);
    }

    /**
     * Take the latitude and longitude in 1/4 seconds and see if
     * the reported location is on Null Island.
     *
     * @return whether the reported Lat/Long are for Null Island
     *
     * @hide
     */
    private boolean isNullIsland(int lat, int lon) {
        return Math.abs(lat) <= 1 && Math.abs(lon) <= 1;
    }

    /**
     * @return Network Id 0..65535, {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE}
     *         if unavailable.
     */
    public int getNetworkId() {
        return mNetworkId;
    }

    /**
     * @return System Id 0..32767, {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE}
     *         if unavailable.
     */
    public int getSystemId() {
        return mSystemId;
    }

    /**
     * @return Base Station Id 0..65535, {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE}
     *         if unavailable.
     */
    public int getBasestationId() {
        return mBasestationId;
    }

    /**
     * @return Base station longitude, which is a decimal number as
     * specified in 3GPP2 C.S0005-A v6.0. It is represented in units
     * of 0.25 seconds and ranges from -2592000 to 2592000, both
     * values inclusive (corresponding to a range of -180
     * to +180 degrees). {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getLongitude() {
        return mLongitude;
    }

    /**
     * @return Base station latitude, which is a decimal number as
     * specified in 3GPP2 C.S0005-A v6.0. It is represented in units
     * of 0.25 seconds and ranges from -1296000 to 1296000, both
     * values inclusive (corresponding to a range of -90
     * to +90 degrees). {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getLatitude() {
        return mLatitude;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkId, mSystemId, mBasestationId, mLatitude, mLongitude,
                super.hashCode());
    }

    /** @hide */
    @NonNull
    @Override
    public CdmaCellLocation asCellLocation() {
        CdmaCellLocation cl = new CdmaCellLocation();
        int bsid = mBasestationId != CellInfo.UNAVAILABLE ? mBasestationId : -1;
        int sid = mSystemId != CellInfo.UNAVAILABLE ? mSystemId : -1;
        int nid = mNetworkId != CellInfo.UNAVAILABLE ? mNetworkId : -1;
        // lat and long already use CellInfo.UNAVAILABLE for invalid/unknown
        cl.setCellLocationData(bsid, mLatitude, mLongitude, sid, nid);
        return cl;
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

        return mNetworkId == o.mNetworkId
                && mSystemId == o.mSystemId
                && mBasestationId == o.mBasestationId
                && mLatitude == o.mLatitude
                && mLongitude == o.mLongitude
                && super.equals(other);
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mNetworkId=").append(mNetworkId)
        .append(" mSystemId=").append(mSystemId)
        .append(" mBasestationId=").append(mBasestationId)
        .append(" mLongitude=").append(mLongitude)
        .append(" mLatitude=").append(mLatitude)
        .append(" mAlphaLong=").append(mAlphaLong)
        .append(" mAlphaShort=").append(mAlphaShort)
        .append("}").toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        super.writeToParcel(dest, CellInfo.TYPE_CDMA);
        dest.writeInt(mNetworkId);
        dest.writeInt(mSystemId);
        dest.writeInt(mBasestationId);
        dest.writeInt(mLongitude);
        dest.writeInt(mLatitude);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityCdma(Parcel in) {
        super(TAG, CellInfo.TYPE_CDMA, in);
        mNetworkId = in.readInt();
        mSystemId = in.readInt();
        mBasestationId = in.readInt();
        mLongitude = in.readInt();
        mLatitude = in.readInt();

        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Creator<CellIdentityCdma> CREATOR =
            new Creator<CellIdentityCdma>() {
        @Override
        public CellIdentityCdma createFromParcel(Parcel in) {
            in.readInt();   // skip
            return createFromParcelBody(in);
        }

        @Override
        public CellIdentityCdma[] newArray(int size) {
            return new CellIdentityCdma[size];
        }
    };

    /** @hide */
    protected static CellIdentityCdma createFromParcelBody(Parcel in) {
        return new CellIdentityCdma(in);
    }
}
