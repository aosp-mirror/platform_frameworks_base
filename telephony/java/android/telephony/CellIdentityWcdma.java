/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * CellIdentity to represent a unique UMTS cell
 */
public final class CellIdentityWcdma implements Parcelable {

    private static final String LOG_TAG = "CellIdentityWcdma";
    private static final boolean DBG = false;

    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455
    private final int mCid;
    // 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511
    private final int mPsc;
    // 16-bit UMTS Absolute RF Channel Number
    private final int mUarfcn;
    // 3-digit Mobile Country Code in string format
    private final String mMccStr;
    // 2 or 3-digit Mobile Network Code in string format
    private final String mMncStr;
    // long alpha Operator Name String or Enhanced Operator Name String
    private final String mAlphaLong;
    // short alpha Operator Name String or Enhanced Operator Name String
    private final String mAlphaShort;

    /**
     * @hide
     */
    public CellIdentityWcdma() {
        mLac = Integer.MAX_VALUE;
        mCid = Integer.MAX_VALUE;
        mPsc = Integer.MAX_VALUE;
        mUarfcn = Integer.MAX_VALUE;
        mMccStr = null;
        mMncStr = null;
        mAlphaLong = null;
        mAlphaShort = null;
    }
    /**
     * public constructor
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 28-bit UMTS Cell Identity
     * @param psc 9-bit UMTS Primary Scrambling Code
     *
     * @hide
     */
    public CellIdentityWcdma (int mcc, int mnc, int lac, int cid, int psc) {
        this(lac, cid, psc, Integer.MAX_VALUE, String.valueOf(mcc), String.valueOf(mnc),
                null, null);
    }

    /**
     * public constructor
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 28-bit UMTS Cell Identity
     * @param psc 9-bit UMTS Primary Scrambling Code
     * @param uarfcn 16-bit UMTS Absolute RF Channel Number
     *
     * @hide
     */
    public CellIdentityWcdma (int mcc, int mnc, int lac, int cid, int psc, int uarfcn) {
        this(lac, cid, psc, uarfcn, String.valueOf(mcc), String.valueOf(mnc), null, null);
    }

    /**
     * public constructor
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 28-bit UMTS Cell Identity
     * @param psc 9-bit UMTS Primary Scrambling Code
     * @param uarfcn 16-bit UMTS Absolute RF Channel Number
     * @param mccStr 3-digit Mobile Country Code in string format
     * @param mncStr 2 or 3-digit Mobile Network Code in string format
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityWcdma (int lac, int cid, int psc, int uarfcn,
                              String mccStr, String mncStr, String alphal, String alphas) {
        mLac = lac;
        mCid = cid;
        mPsc = psc;
        mUarfcn = uarfcn;

        // Only allow INT_MAX if unknown string mcc/mnc
        if (mccStr == null || mccStr.matches("^[0-9]{3}$")) {
            mMccStr = mccStr;
        } else if (mccStr.isEmpty() || mccStr.equals(String.valueOf(Integer.MAX_VALUE))) {
            // If the mccStr is empty or unknown, set it as null.
            mMccStr = null;
        } else {
            // TODO: b/69384059 Should throw IllegalArgumentException for the invalid MCC format
            // after the bug got fixed.
            mMccStr = null;
            log("invalid MCC format: " + mccStr);
        }

        if (mncStr == null || mncStr.matches("^[0-9]{2,3}$")) {
            mMncStr = mncStr;
        } else if (mncStr.isEmpty() || mncStr.equals(String.valueOf(Integer.MAX_VALUE))) {
            // If the mncStr is empty or unknown, set it as null.
            mMncStr = null;
        } else {
            // TODO: b/69384059 Should throw IllegalArgumentException for the invalid MNC format
            // after the bug got fixed.
            mMncStr = null;
            log("invalid MNC format: " + mncStr);
        }

        mAlphaLong = alphal;
        mAlphaShort = alphas;
    }

    private CellIdentityWcdma(CellIdentityWcdma cid) {
        this(cid.mLac, cid.mCid, cid.mPsc, cid.mUarfcn, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort);
    }

    CellIdentityWcdma copy() {
        return new CellIdentityWcdma(this);
    }

    /**
     * @return 3-digit Mobile Country Code, 0..999, Integer.MAX_VALUE if unknown
     * @deprecated Use {@link #getMccStr} instead.
     */
    @Deprecated
    public int getMcc() {
        return (mMccStr != null) ? Integer.valueOf(mMccStr) : Integer.MAX_VALUE;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999, Integer.MAX_VALUE if unknown
     * @deprecated Use {@link #getMncStr} instead.
     */
    @Deprecated
    public int getMnc() {
        return (mMncStr != null) ? Integer.valueOf(mMncStr) : Integer.MAX_VALUE;
    }

    /**
     * @return 16-bit Location Area Code, 0..65535, Integer.MAX_VALUE if unknown
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return CID
     * 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, Integer.MAX_VALUE if unknown
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511, Integer.MAX_VALUE
     * if unknown
     */
    public int getPsc() {
        return mPsc;
    }

    /**
     * @return Mobile Country Code in string version, null if unknown
     */
    public String getMccStr() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string version, null if unknown
     */
    public String getMncStr() {
        return mMncStr;
    }

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown
     */
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
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
        return Objects.hash(mMccStr, mMncStr, mLac, mCid, mPsc, mAlphaLong, mAlphaShort);
    }

    /**
     * @return 16-bit UMTS Absolute RF Channel Number, Integer.MAX_VALUE if unknown
     */
    public int getUarfcn() {
        return mUarfcn;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CellIdentityWcdma)) {
            return false;
        }

        CellIdentityWcdma o = (CellIdentityWcdma) other;
        return mLac == o.mLac &&
                mCid == o.mCid &&
                mPsc == o.mPsc &&
                mUarfcn == o.mUarfcn &&
                TextUtils.equals(mMccStr, o.mMccStr) &&
                TextUtils.equals(mMncStr, o.mMncStr) &&
                TextUtils.equals(mAlphaLong, o.mAlphaLong) &&
                TextUtils.equals(mAlphaShort, o.mAlphaShort);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CellIdentityWcdma:{");
        sb.append(" mLac=").append(mLac);
        sb.append(" mCid=").append(mCid);
        sb.append(" mPsc=").append(mPsc);
        sb.append(" mUarfcn=").append(mUarfcn);
        sb.append(" mMcc=").append(mMccStr);
        sb.append(" mMnc=").append(mMncStr);
        sb.append(" mAlphaLong=").append(mAlphaLong);
        sb.append(" mAlphaShort=").append(mAlphaShort);
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
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mPsc);
        dest.writeInt(mUarfcn);
        dest.writeString(mMccStr);
        dest.writeString(mMncStr);
        dest.writeString(mAlphaLong);
        dest.writeString(mAlphaShort);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityWcdma(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readString(),
                in.readString(), in.readString(), in.readString());

        if (DBG) log("CellIdentityWcdma(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityWcdma> CREATOR =
            new Creator<CellIdentityWcdma>() {
                @Override
                public CellIdentityWcdma createFromParcel(Parcel in) {
                    return new CellIdentityWcdma(in);
                }

                @Override
                public CellIdentityWcdma[] newArray(int size) {
                    return new CellIdentityWcdma[size];
                }
            };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}