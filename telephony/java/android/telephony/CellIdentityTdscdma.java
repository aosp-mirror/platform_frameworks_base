/*
 * Copyright 2017 The Android Open Source Project
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
import android.telephony.gsm.GsmCellLocation;

import java.util.Objects;

/**
 * CellIdentity is to represent a unique TD-SCDMA cell
 */
public final class CellIdentityTdscdma extends CellIdentity {
    private static final String TAG = CellIdentityTdscdma.class.getSimpleName();
    private static final boolean DBG = false;

    // 16-bit Location Area Code, 0..65535, CellInfo.UNAVAILABLE if unknown.
    private final int mLac;
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, CellInfo.UNAVAILABLE
    // if unknown.
    private final int mCid;
    // 8-bit Cell Parameters ID described in TS 25.331, 0..127, CellInfo.UNAVAILABLE if unknown.
    private final int mCpid;
    // 16-bit UMTS Absolute RF Channel Number described in TS 25.101 sec. 5.4.3
    private final int mUarfcn;

    /**
     * @hide
     */
    public CellIdentityTdscdma() {
        super(TAG, CellInfo.TYPE_TDSCDMA, null, null, null, null);
        mLac = CellInfo.UNAVAILABLE;
        mCid = CellInfo.UNAVAILABLE;
        mCpid = CellInfo.UNAVAILABLE;
        mUarfcn = CellInfo.UNAVAILABLE;
    }

    /**
     * @param mcc 3-digit Mobile Country Code in string format
     * @param mnc 2 or 3-digit Mobile Network Code in string format
     * @param lac 16-bit Location Area Code, 0..65535, CellInfo.UNAVAILABLE if unknown
     * @param cid 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455,
     *        CellInfo.UNAVAILABLE if unknown
     * @param cpid 8-bit Cell Parameters ID described in TS 25.331, 0..127,
     *        CellInfo.UNAVAILABLE if unknown
     * @param uarfcn 16-bit UMTS Absolute RF Channel Number described in TS 25.101 sec. 5.4.3
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityTdscdma(String mcc, String mnc, int lac, int cid, int cpid, int uarfcn,
            String alphal, String alphas) {
        super(TAG, CellInfo.TYPE_TDSCDMA, mcc, mnc, alphal, alphas);
        mLac = lac;
        mCid = cid;
        mCpid = cpid;
        mUarfcn = uarfcn;
    }

    private CellIdentityTdscdma(CellIdentityTdscdma cid) {
        this(cid.mMccStr, cid.mMncStr, cid.mLac, cid.mCid,
                cid.mCpid, cid.mUarfcn, cid.mAlphaLong, cid.mAlphaShort);
    }

    /** @hide */
    public CellIdentityTdscdma(android.hardware.radio.V1_0.CellIdentityTdscdma cid) {
        this(cid.mcc, cid.mnc, cid.lac, cid.cid, cid.cpid, CellInfo.UNAVAILABLE, "", "");
    }

    /** @hide */
    public CellIdentityTdscdma(android.hardware.radio.V1_2.CellIdentityTdscdma cid) {
        this(cid.base.mcc, cid.base.mnc, cid.base.lac, cid.base.cid, cid.base.cpid,
                cid.uarfcn, cid.operatorNames.alphaLong, cid.operatorNames.alphaShort);
    }

    CellIdentityTdscdma copy() {
        return new CellIdentityTdscdma(this);
    }

    /**
     * Get Mobile Country Code in string format
     * @return Mobile Country Code in string format, null if unknown
     */
    public String getMccString() {
        return mMccStr;
    }

    /**
     * Get Mobile Network Code in string format
     * @return Mobile Network Code in string format, null if unknown
     */
    public String getMncString() {
        return mMncStr;
    }

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown
     */
    @Nullable
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
    }

    /**
     * @return 16-bit Location Area Code, 0..65535,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 8-bit Cell Parameters ID described in TS 25.331, 0..127,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getCpid() {
        return mCpid;
    }

    /** @hide */
    @Override
    public int getChannelNumber() {
        return mUarfcn;
    }

    /** @hide */
    @Override
    public GsmCellLocation asCellLocation() {
        GsmCellLocation cl = new GsmCellLocation();
        int lac = mLac != CellInfo.UNAVAILABLE ? mLac : -1;
        int cid = mCid != CellInfo.UNAVAILABLE ? mCid : -1;
        cl.setLacAndCid(lac, cid);
        cl.setPsc(-1); // There is no PSC for TD-SCDMA; not using this for CPI to stem shenanigans
        return cl;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof CellIdentityTdscdma)) {
            return false;
        }

        CellIdentityTdscdma o = (CellIdentityTdscdma) other;
        return  mLac == o.mLac
                && mCid == o.mCid
                && mCpid == o.mCpid
                && mUarfcn == o.mUarfcn
                && super.equals(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLac, mCid, mCpid, mUarfcn, super.hashCode());
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mMcc=").append(mMccStr)
        .append(" mMnc=").append(mMncStr)
        .append(" mAlphaLong=").append(mAlphaLong)
        .append(" mAlphaShort=").append(mAlphaShort)
        .append(" mLac=").append(mLac)
        .append(" mCid=").append(mCid)
        .append(" mCpid=").append(mCpid)
        .append(" mUarfcn=").append(mUarfcn)
        .append("}").toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        super.writeToParcel(dest, CellInfo.TYPE_TDSCDMA);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mCpid);
        dest.writeInt(mUarfcn);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityTdscdma(Parcel in) {
        super(TAG, CellInfo.TYPE_TDSCDMA, in);
        mLac = in.readInt();
        mCid = in.readInt();
        mCpid = in.readInt();
        mUarfcn = in.readInt();
        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityTdscdma> CREATOR =
            new Creator<CellIdentityTdscdma>() {
                @Override
                public CellIdentityTdscdma createFromParcel(Parcel in) {
                    in.readInt();   // skip
                    return createFromParcelBody(in);
                }

                @Override
                public CellIdentityTdscdma[] newArray(int size) {
                    return new CellIdentityTdscdma[size];
                }
            };

    /** @hide */
    protected static CellIdentityTdscdma createFromParcelBody(Parcel in) {
        return new CellIdentityTdscdma(in);
    }
}
