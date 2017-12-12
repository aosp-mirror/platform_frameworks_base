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
 * CellIdentity to represent a unique GSM cell
 */
public final class CellIdentityGsm implements Parcelable {

    private static final String LOG_TAG = "CellIdentityGsm";
    private static final boolean DBG = false;

    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 16-bit GSM Cell Identity described in TS 27.007, 0..65535
    private final int mCid;
    // 16-bit GSM Absolute RF Channel Number
    private final int mArfcn;
    // 6-bit Base Station Identity Code
    private final int mBsic;
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
    public CellIdentityGsm() {
        mLac = Integer.MAX_VALUE;
        mCid = Integer.MAX_VALUE;
        mArfcn = Integer.MAX_VALUE;
        mBsic = Integer.MAX_VALUE;
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
     * @param cid 16-bit GSM Cell Identity or 28-bit UMTS Cell Identity
     *
     * @hide
     */
    public CellIdentityGsm (int mcc, int mnc, int lac, int cid) {
        this(lac, cid, Integer.MAX_VALUE, Integer.MAX_VALUE,
                String.valueOf(mcc), String.valueOf(mnc), null, null);
    }

    /**
     * public constructor
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 16-bit GSM Cell Identity or 28-bit UMTS Cell Identity
     * @param arfcn 16-bit GSM Absolute RF Channel Number
     * @param bsic 6-bit Base Station Identity Code
     *
     * @hide
     */
    public CellIdentityGsm (int mcc, int mnc, int lac, int cid, int arfcn, int bsic) {
        this(lac, cid, arfcn, bsic, String.valueOf(mcc), String.valueOf(mnc), null, null);
    }

    /**
     * public constructor
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 16-bit GSM Cell Identity or 28-bit UMTS Cell Identity
     * @param arfcn 16-bit GSM Absolute RF Channel Number
     * @param bsic 6-bit Base Station Identity Code
     * @param mccStr 3-digit Mobile Country Code in string format
     * @param mncStr 2 or 3-digit Mobile Network Code in string format
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityGsm (int lac, int cid, int arfcn, int bsic, String mccStr,
                            String mncStr, String alphal, String alphas) {
        mLac = lac;
        mCid = cid;
        mArfcn = arfcn;
        // In RIL BSIC is a UINT8, so 0xFF is the 'INVALID' designator
        // for inbound parcels
        mBsic = (bsic == 0xFF) ? Integer.MAX_VALUE : bsic;

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

    private CellIdentityGsm(CellIdentityGsm cid) {
        this(cid.mLac, cid.mCid, cid.mArfcn, cid.mBsic, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort);
    }

    CellIdentityGsm copy() {
        return new CellIdentityGsm(this);
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
     * Either 16-bit GSM Cell Identity described
     * in TS 27.007, 0..65535, Integer.MAX_VALUE if unknown
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 16-bit GSM Absolute RF Channel Number, Integer.MAX_VALUE if unknown
     */
    public int getArfcn() {
        return mArfcn;
    }

    /**
     * @return 6-bit Base Station Identity Code, Integer.MAX_VALUE if unknown
     */
    public int getBsic() {
        return mBsic;
    }

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown
     */
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
    }

    /**
     * @return Mobile Country Code in string format, null if unknown
     */
    public String getMccStr() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string format, null if unknown
     */
    public String getMncStr() {
        return mMncStr;
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


    /**
     * @return Integer.MAX_VALUE, undefined for GSM
     */
    @Deprecated
    public int getPsc() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMccStr, mMncStr, mLac, mCid, mAlphaLong, mAlphaShort);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CellIdentityGsm)) {
            return false;
        }

        CellIdentityGsm o = (CellIdentityGsm) other;
        return mLac == o.mLac &&
                mCid == o.mCid &&
                mArfcn == o.mArfcn &&
                mBsic == o.mBsic &&
                TextUtils.equals(mMccStr, o.mMccStr) &&
                TextUtils.equals(mMncStr, o.mMncStr) &&
                TextUtils.equals(mAlphaLong, o.mAlphaLong) &&
                TextUtils.equals(mAlphaShort, o.mAlphaShort);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CellIdentityGsm:{");
        sb.append(" mLac=").append(mLac);
        sb.append(" mCid=").append(mCid);
        sb.append(" mArfcn=").append(mArfcn);
        sb.append(" mBsic=").append("0x").append(Integer.toHexString(mBsic));
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
        dest.writeInt(mArfcn);
        dest.writeInt(mBsic);
        dest.writeString(mMccStr);
        dest.writeString(mMncStr);
        dest.writeString(mAlphaLong);
        dest.writeString(mAlphaShort);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityGsm(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readString(),
                in.readString(), in.readString(), in.readString());

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
        Rlog.w(LOG_TAG, s);
    }
}