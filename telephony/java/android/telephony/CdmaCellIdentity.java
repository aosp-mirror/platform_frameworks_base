/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * CellIdentity is to represent a unique CDMA cell
 *
 * @hide pending API review
 */
public final class CdmaCellIdentity extends CellIdentity implements Parcelable {
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
     * public constructor
     * @param nid Network Id 0..65535
     * @param sid CDMA System Id 0..32767
     * @param bid Base Station Id 0..65535
     * @param lon Longitude is a decimal number ranges from -2592000
     *        to 2592000
     * @param lat Latitude is a decimal number ranges from -1296000
     *        to 1296000
     * @param attr is comma separated “key=value” attribute pairs.
     */
    public CdmaCellIdentity (int nid, int sid,
            int bid, int lon, int lat, String attr) {
        super(CELLID_TYPE_CDMA, attr);
        mNetworkId = nid;
        mSystemId = sid;
        mBasestationId = bid;
        mLongitude = lon;
        mLatitude = lat;
    }

    private CdmaCellIdentity(Parcel in) {
        super(in);
        mNetworkId = in.readInt();
        mSystemId = in.readInt();
        mBasestationId = in.readInt();
        mLongitude = in.readInt();
        mLatitude = in.readInt();
    }

    CdmaCellIdentity(CdmaCellIdentity cid) {
        super(cid);
        mNetworkId = cid.mNetworkId;
        mSystemId = cid.mSystemId;
        mBasestationId = cid.mBasestationId;
        mLongitude = cid.mLongitude;
        mLatitude = cid.mLatitude;
    }

    /**
     * @return Network Id 0..65535
     */
    public int getNetworkId() {
        return mNetworkId;
    }

    /**
     * @return System Id 0..32767
     */
    public int getSystemId() {
        return mSystemId;
    }

    /**
     * @return Base Station Id 0..65535
     */
    public int getBasestationId() {
        return mBasestationId;
    }

    /**
     * @return Base station longitude, which is a decimal number as
     * specified in 3GPP2 C.S0005-A v6.0. It is represented in units
     * of 0.25 seconds and ranges from -2592000 to 2592000, both
     * values inclusive (corresponding to a range of -180
     * to +180 degrees).
     */
    public int getLongitude() {
        return mLongitude;
    }

    /**
     * @return Base station
     */
    /**
     * @return Base station latitude, which is a decimal number as
     * specified in 3GPP2 C.S0005-A v6.0. It is represented in units
     * of 0.25 seconds and ranges from -1296000 to 1296000, both
     * values inclusive (corresponding to a range of -90
     * to +90 degrees).
     */
    public int getLatitude() {
        return mLatitude;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mNetworkId);
        dest.writeInt(mSystemId);
        dest.writeInt(mBasestationId);
        dest.writeInt(mLongitude);
        dest.writeInt(mLatitude);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<CdmaCellIdentity> CREATOR =
            new Creator<CdmaCellIdentity>() {
        @Override
        public CdmaCellIdentity createFromParcel(Parcel in) {
            return new CdmaCellIdentity(in);
        }

        @Override
        public CdmaCellIdentity[] newArray(int size) {
            return new CdmaCellIdentity[size];
        }
    };
}
