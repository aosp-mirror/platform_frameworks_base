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
import android.util.Log;

/**
 * CellIdentity to represent a unique GSM or UMTS cell
 */
public final class CellIdentityGsm implements Parcelable {

    private static final String LOG_TAG = "CellIdentityGsm";
    private static final boolean DBG = false;

    // 3-digit Mobile Country Code, 0..999
    private final int mMcc;
    // 2 or 3-digit Mobile Network Code, 0..999
    private final int mMnc;
    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 16-bit GSM Cell Identity described in TS 27.007, 0..65535
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455
    private final int mCid;
    // 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511
    private final int mPsc;

    /**
     * @hide
     */
    public CellIdentityGsm() {
        mMcc = Integer.MAX_VALUE;
        mMnc = Integer.MAX_VALUE;
        mLac = Integer.MAX_VALUE;
        mCid = Integer.MAX_VALUE;
        mPsc = Integer.MAX_VALUE;
    }
    /**
     * public constructor
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 16-bit GSM Cell Identity or 28-bit UMTS Cell Identity
     * @param psc 9-bit UMTS Primary Scrambling Code
     *
     * @hide
     */
    public CellIdentityGsm (int mcc, int mnc, int lac, int cid, int psc) {
        mMcc = mcc;
        mMnc = mnc;
        mLac = lac;
        mCid = cid;
        mPsc = psc;
    }

    private CellIdentityGsm(CellIdentityGsm cid) {
        mMcc = cid.mMcc;
        mMnc = cid.mMnc;
        mLac = cid.mLac;
        mCid = cid.mCid;
        mPsc = cid.mPsc;
    }

    CellIdentityGsm copy() {
       return new CellIdentityGsm(this);
    }

    /**
     * @return 3-digit Mobile Country Code, 0..999
     */
    public int getMcc() {
        return mMcc;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999
     */
    public int getMnc() {
        return mMnc;
    }

    /**
     * @return 16-bit Location Area Code, 0..65535
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return CID
     * Either 16-bit GSM Cell Identity described
     * in TS 27.007, 0..65535
     * or 28-bit UMTS Cell Identity described
     * in TS 25.331, 0..268435455
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 9-bit UMTS Primary Scrambling Code described in
     * TS 25.331, 0..511
     */
    public int getPsc() {
        return mPsc;
    }

    @Override
    public int hashCode() {
        int primeNum = 31;
        return (mMcc * primeNum) + (mMnc * primeNum) + (mLac * primeNum) + (mCid * primeNum) +
                (mPsc * primeNum);
    }

    @Override
    public boolean equals(Object other) {
        if (super.equals(other)) {
            try {
                CellIdentityGsm o = (CellIdentityGsm)other;
                return mMcc == o.mMcc &&
                        mMnc == o.mMnc &&
                        mLac == o.mLac &&
                        mCid == o.mCid &&
                        mPsc == o.mPsc;
            } catch (ClassCastException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GsmCellIdentitiy:");
        sb.append(super.toString());
        sb.append(" mMcc=").append(mMcc);
        sb.append(" mMnc=").append(mMcc);
        sb.append(" mLac=").append(mLac);
        sb.append(" mCid=").append(mCid);
        sb.append(" mPsc=").append(mPsc);

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
        dest.writeInt(mMcc);
        dest.writeInt(mMnc);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mPsc);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityGsm(Parcel in) {
        mMcc = in.readInt();
        mMnc = in.readInt();
        mLac = in.readInt();
        mCid = in.readInt();
        mPsc = in.readInt();
        if (DBG) log("CellIdentityGsm(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityGsm> CREATOR =
            new Creator<CellIdentityGsm>() {
        @Override
        public CellIdentityGsm createFromParcel(Parcel in) {
            return new CellIdentityGsm(in);
        }

        @Override
        public CellIdentityGsm[] newArray(int size) {
            return new CellIdentityGsm[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Log.w(LOG_TAG, s);
    }
}
