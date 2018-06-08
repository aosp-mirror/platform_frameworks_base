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
 * CellIdentity to represent a unique GSM cell
 */
public final class CellIdentityGsm extends CellIdentity {
    private static final String TAG = CellIdentityGsm.class.getSimpleName();
    private static final boolean DBG = false;

    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 16-bit GSM Cell Identity described in TS 27.007, 0..65535
    private final int mCid;
    // 16-bit GSM Absolute RF Channel Number
    private final int mArfcn;
    // 6-bit Base Station Identity Code
    private final int mBsic;

    /**
     * @hide
     */
    public CellIdentityGsm() {
        super(TAG, TYPE_GSM, null, null, null, null);
        mLac = Integer.MAX_VALUE;
        mCid = Integer.MAX_VALUE;
        mArfcn = Integer.MAX_VALUE;
        mBsic = Integer.MAX_VALUE;
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
    public CellIdentityGsm(int mcc, int mnc, int lac, int cid) {
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
    public CellIdentityGsm(int mcc, int mnc, int lac, int cid, int arfcn, int bsic) {
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
    public CellIdentityGsm(int lac, int cid, int arfcn, int bsic, String mccStr,
                            String mncStr, String alphal, String alphas) {
        super(TAG, TYPE_GSM, mccStr, mncStr, alphal, alphas);
        mLac = lac;
        mCid = cid;
        mArfcn = arfcn;
        // In RIL BSIC is a UINT8, so 0xFF is the 'INVALID' designator
        // for inbound parcels
        mBsic = (bsic == 0xFF) ? Integer.MAX_VALUE : bsic;
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
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string format, null if unknown
     */
    public String getMncString() {
        return mMncStr;
    }

    /** @hide */
    @Override
    public int getChannelNumber() {
        return mArfcn;
    }

    /**
     * @deprecated Primary Scrambling Code is not applicable to GSM.
     * @return Integer.MAX_VALUE, undefined for GSM
     */
    @Deprecated
    public int getPsc() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLac, mCid, super.hashCode());
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
        return mLac == o.mLac
                && mCid == o.mCid
                && mArfcn == o.mArfcn
                && mBsic == o.mBsic
                && TextUtils.equals(mMccStr, o.mMccStr)
                && TextUtils.equals(mMncStr, o.mMncStr)
                && super.equals(other);
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mLac=").append(mLac)
        .append(" mCid=").append(mCid)
        .append(" mArfcn=").append(mArfcn)
        .append(" mBsic=").append("0x").append(Integer.toHexString(mBsic))
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
        super.writeToParcel(dest, TYPE_GSM);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mArfcn);
        dest.writeInt(mBsic);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityGsm(Parcel in) {
        super(TAG, TYPE_GSM, in);
        mLac = in.readInt();
        mCid = in.readInt();
        mArfcn = in.readInt();
        mBsic = in.readInt();

        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityGsm> CREATOR =
            new Creator<CellIdentityGsm>() {
                @Override
                public CellIdentityGsm createFromParcel(Parcel in) {
                    in.readInt();   // skip
                    return createFromParcelBody(in);
                }

                @Override
                public CellIdentityGsm[] newArray(int size) {
                    return new CellIdentityGsm[size];
                }
            };

    /** @hide */
    protected static CellIdentityGsm createFromParcelBody(Parcel in) {
        return new CellIdentityGsm(in);
    }
}
