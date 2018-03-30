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
import android.text.TextUtils;

import java.util.Objects;

/**
 * CellIdentity is to represent a unique TD-SCDMA cell
 */
public final class CellIdentityTdscdma extends CellIdentity {
    private static final String TAG = CellIdentityTdscdma.class.getSimpleName();
    private static final boolean DBG = false;

    // 16-bit Location Area Code, 0..65535, INT_MAX if unknown.
    private final int mLac;
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, INT_MAX if unknown.
    private final int mCid;
    // 8-bit Cell Parameters ID described in TS 25.331, 0..127, INT_MAX if unknown.
    private final int mCpid;

    /**
     * @hide
     */
    public CellIdentityTdscdma() {
        super(TAG, TYPE_TDSCDMA, null, null, null, null);
        mLac = Integer.MAX_VALUE;
        mCid = Integer.MAX_VALUE;
        mCpid = Integer.MAX_VALUE;
    }

    /**
     * @param mcc 3-digit Mobile Country Code, 0..999
     * @param mnc 2 or 3-digit Mobile Network Code, 0..999
     * @param lac 16-bit Location Area Code, 0..65535, INT_MAX if unknown
     * @param cid 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, INT_MAX if unknown
     * @param cpid 8-bit Cell Parameters ID described in TS 25.331, 0..127, INT_MAX if unknown
     *
     * @hide
     */
    public CellIdentityTdscdma(int mcc, int mnc, int lac, int cid, int cpid) {
        this(String.valueOf(mcc), String.valueOf(mnc), lac, cid, cpid, null, null);
    }

    /**
     * @param mcc 3-digit Mobile Country Code in string format
     * @param mnc 2 or 3-digit Mobile Network Code in string format
     * @param lac 16-bit Location Area Code, 0..65535, INT_MAX if unknown
     * @param cid 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, INT_MAX if unknown
     * @param cpid 8-bit Cell Parameters ID described in TS 25.331, 0..127, INT_MAX if unknown
     *
     * FIXME: This is a temporary constructor to facilitate migration.
     * @hide
     */
    public CellIdentityTdscdma(String mcc, String mnc, int lac, int cid, int cpid) {
        super(TAG, TYPE_TDSCDMA, mcc, mnc, null, null);
        mLac = lac;
        mCid = cid;
        mCpid = cpid;
    }

    /**
     * @param mcc 3-digit Mobile Country Code in string format
     * @param mnc 2 or 3-digit Mobile Network Code in string format
     * @param lac 16-bit Location Area Code, 0..65535, INT_MAX if unknown
     * @param cid 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, INT_MAX if unknown
     * @param cpid 8-bit Cell Parameters ID described in TS 25.331, 0..127, INT_MAX if unknown
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     *
     * @hide
     */
    public CellIdentityTdscdma(String mcc, String mnc, int lac, int cid, int cpid,
            String alphal, String alphas) {
        super(TAG, TYPE_TDSCDMA, mcc, mnc, alphal, alphas);
        mLac = lac;
        mCid = cid;
        mCpid = cpid;
    }

    private CellIdentityTdscdma(CellIdentityTdscdma cid) {
        this(cid.mMccStr, cid.mMncStr, cid.mLac, cid.mCid,
                cid.mCpid, cid.mAlphaLong, cid.mAlphaShort);
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
     * @return 16-bit Location Area Code, 0..65535, INT_MAX if unknown
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455, INT_MAX if unknown
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 8-bit Cell Parameters ID described in TS 25.331, 0..127, INT_MAX if unknown
     */
    public int getCpid() {
        return mCpid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLac, mCid, mCpid, super.hashCode());
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
        return TextUtils.equals(mMccStr, o.mMccStr)
                && TextUtils.equals(mMncStr, o.mMncStr)
                && mLac == o.mLac
                && mCid == o.mCid
                && mCpid == o.mCpid
                && super.equals(other);
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mMcc=").append(mMccStr)
        .append(" mMnc=").append(mMncStr)
        .append(" mLac=").append(mLac)
        .append(" mCid=").append(mCid)
        .append(" mCpid=").append(mCpid)
        .append(" mAlphaLong=").append(mAlphaLong)
        .append(" mAlphaShort=").append(mAlphaShort)
        .append("}").toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        super.writeToParcel(dest, TYPE_TDSCDMA);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mCpid);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityTdscdma(Parcel in) {
        super(TAG, TYPE_TDSCDMA, in);
        mLac = in.readInt();
        mCid = in.readInt();
        mCpid = in.readInt();

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
