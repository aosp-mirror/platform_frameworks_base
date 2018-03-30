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

import android.annotation.Nullable;
import android.os.Parcel;
import android.text.TextUtils;

import java.util.Objects;

/**
 * CellIdentity to represent a unique UMTS cell
 */
public final class CellIdentityWcdma extends CellIdentity {
    private static final String TAG = CellIdentityWcdma.class.getSimpleName();
    private static final boolean DBG = false;

    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455
    private final int mCid;
    // 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511
    private final int mPsc;
    // 16-bit UMTS Absolute RF Channel Number
    private final int mUarfcn;

    /**
     * @hide
     */
    public CellIdentityWcdma() {
        super(TAG, TYPE_TDSCDMA, null, null, null, null);
        mLac = Integer.MAX_VALUE;
        mCid = Integer.MAX_VALUE;
        mPsc = Integer.MAX_VALUE;
        mUarfcn = Integer.MAX_VALUE;
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
        super(TAG, TYPE_WCDMA, mccStr, mncStr, alphal, alphas);
        mLac = lac;
        mCid = cid;
        mPsc = psc;
        mUarfcn = uarfcn;
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
     * @deprecated Use {@link #getMccString} instead.
     */
    @Deprecated
    public int getMcc() {
        return (mMccStr != null) ? Integer.valueOf(mMccStr) : Integer.MAX_VALUE;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999, Integer.MAX_VALUE if unknown
     * @deprecated Use {@link #getMncString} instead.
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
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string version, null if unknown
     */
    public String getMncString() {
        return mMncStr;
    }

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown
     */
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLac, mCid, mPsc, super.hashCode());
    }

    /**
     * @return 16-bit UMTS Absolute RF Channel Number, Integer.MAX_VALUE if unknown
     */
    public int getUarfcn() {
        return mUarfcn;
    }

    /** @hide */
    @Override
    public int getChannelNumber() {
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
        return mLac == o.mLac
                && mCid == o.mCid
                && mPsc == o.mPsc
                && mUarfcn == o.mUarfcn
                && TextUtils.equals(mMccStr, o.mMccStr)
                && TextUtils.equals(mMncStr, o.mMncStr)
                && super.equals(other);
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mLac=").append(mLac)
        .append(" mCid=").append(mCid)
        .append(" mPsc=").append(mPsc)
        .append(" mUarfcn=").append(mUarfcn)
        .append(" mMcc=").append(mMccStr)
        .append(" mMnc=").append(mMncStr)
        .append(" mAlphaLong=").append(mAlphaLong)
        .append(" mAlphaShort=").append(mAlphaShort)
        .append("}").toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        super.writeToParcel(dest, TYPE_WCDMA);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mPsc);
        dest.writeInt(mUarfcn);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityWcdma(Parcel in) {
        super(TAG, TYPE_WCDMA, in);
        mLac = in.readInt();
        mCid = in.readInt();
        mPsc = in.readInt();
        mUarfcn = in.readInt();
        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityWcdma> CREATOR =
            new Creator<CellIdentityWcdma>() {
                @Override
                public CellIdentityWcdma createFromParcel(Parcel in) {
                    in.readInt();   // skip
                    return createFromParcelBody(in);
                }

                @Override
                public CellIdentityWcdma[] newArray(int size) {
                    return new CellIdentityWcdma[size];
                }
            };

    /** @hide */
    protected static CellIdentityWcdma createFromParcelBody(Parcel in) {
        return new CellIdentityWcdma(in);
    }
}