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

import android.annotation.Nullable;
import android.os.Parcel;
import android.text.TextUtils;

import java.util.Objects;

/**
 * CellIdentity is to represent a unique LTE cell
 */
public final class CellIdentityLte extends CellIdentity {
    private static final String TAG = CellIdentityLte.class.getSimpleName();
    private static final boolean DBG = false;

    // 28-bit cell identity
    private final int mCi;
    // physical cell id 0..503
    private final int mPci;
    // 16-bit tracking area code
    private final int mTac;
    // 18-bit Absolute RF Channel Number
    private final int mEarfcn;
    // cell bandwidth, in kHz
    private final int mBandwidth;

    /**
     * @hide
     */
    public CellIdentityLte() {
        super(TAG, TYPE_LTE, null, null, null, null);
        mCi = Integer.MAX_VALUE;
        mPci = Integer.MAX_VALUE;
        mTac = Integer.MAX_VALUE;
        mEarfcn = Integer.MAX_VALUE;
        mBandwidth = Integer.MAX_VALUE;
    }

    /**
     *
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     *
     * @hide
     */
    public CellIdentityLte(int mcc, int mnc, int ci, int pci, int tac) {
        this(ci, pci, tac, Integer.MAX_VALUE, Integer.MAX_VALUE, String.valueOf(mcc),
                String.valueOf(mnc), null, null);
    }

    /**
     *
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     * @param earfcn 18-bit LTE Absolute RF Channel Number
     *
     * @hide
     */
    public CellIdentityLte(int mcc, int mnc, int ci, int pci, int tac, int earfcn) {
        this(ci, pci, tac, earfcn, Integer.MAX_VALUE, String.valueOf(mcc), String.valueOf(mnc),
                null, null);
    }

    /**
     *
     * @param ci 28-bit Cell Identity
     * @param pci Physical Cell Id 0..503
     * @param tac 16-bit Tracking Area Code
     * @param earfcn 18-bit LTE Absolute RF Channel Number
     * @param bandwidth cell bandwidth in kHz
     * @param mccStr 3-digit Mobile Country Code in string format
     * @param mncStr 2 or 3-digit Mobile Network Code in string format
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityLte(int ci, int pci, int tac, int earfcn, int bandwidth, String mccStr,
            String mncStr, String alphal, String alphas) {
        super(TAG, TYPE_LTE, mccStr, mncStr, alphal, alphas);
        mCi = ci;
        mPci = pci;
        mTac = tac;
        mEarfcn = earfcn;
        mBandwidth = bandwidth;
    }

    private CellIdentityLte(CellIdentityLte cid) {
        this(cid.mCi, cid.mPci, cid.mTac, cid.mEarfcn, cid.mBandwidth, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort);
    }

    CellIdentityLte copy() {
        return new CellIdentityLte(this);
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
     * @return 28-bit Cell Identity, Integer.MAX_VALUE if unknown
     */
    public int getCi() {
        return mCi;
    }

    /**
     * @return Physical Cell Id 0..503, Integer.MAX_VALUE if unknown
     */
    public int getPci() {
        return mPci;
    }

    /**
     * @return 16-bit Tracking Area Code, Integer.MAX_VALUE if unknown
     */
    public int getTac() {
        return mTac;
    }

    /**
     * @return 18-bit Absolute RF Channel Number, Integer.MAX_VALUE if unknown
     */
    public int getEarfcn() {
        return mEarfcn;
    }

    /**
     * @return Cell bandwidth in kHz, Integer.MAX_VALUE if unknown
     */
    public int getBandwidth() {
        return mBandwidth;
    }

    /**
     * @return Mobile Country Code in string format, null if unknown
     */
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string format, null if unknown
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

    /** @hide */
    @Override
    public int getChannelNumber() {
        return mEarfcn;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCi, mPci, mTac, super.hashCode());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CellIdentityLte)) {
            return false;
        }

        CellIdentityLte o = (CellIdentityLte) other;
        return mCi == o.mCi
                && mPci == o.mPci
                && mTac == o.mTac
                && mEarfcn == o.mEarfcn
                && mBandwidth == o.mBandwidth
                && TextUtils.equals(mMccStr, o.mMccStr)
                && TextUtils.equals(mMncStr, o.mMncStr)
                && super.equals(other);
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mCi=").append(mCi)
        .append(" mPci=").append(mPci)
        .append(" mTac=").append(mTac)
        .append(" mEarfcn=").append(mEarfcn)
        .append(" mBandwidth=").append(mBandwidth)
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
        super.writeToParcel(dest, TYPE_LTE);
        dest.writeInt(mCi);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
        dest.writeInt(mEarfcn);
        dest.writeInt(mBandwidth);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityLte(Parcel in) {
        super(TAG, TYPE_LTE, in);
        mCi = in.readInt();
        mPci = in.readInt();
        mTac = in.readInt();
        mEarfcn = in.readInt();
        mBandwidth = in.readInt();

        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityLte> CREATOR =
            new Creator<CellIdentityLte>() {
                @Override
                public CellIdentityLte createFromParcel(Parcel in) {
                    in.readInt();   // skip;
                    return createFromParcelBody(in);
                }

                @Override
                public CellIdentityLte[] newArray(int size) {
                    return new CellIdentityLte[size];
                }
            };

    /** @hide */
    protected static CellIdentityLte createFromParcelBody(Parcel in) {
        return new CellIdentityLte(in);
    }
}
