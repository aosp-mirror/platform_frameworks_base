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

import static android.text.TextUtils.formatSimple;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * CellIdentity to represent a unique GSM cell
 */
public final class CellIdentityGsm extends CellIdentity {
    private static final String TAG = CellIdentityGsm.class.getSimpleName();
    private static final boolean DBG = false;

    private static final int MAX_LAC = 65535;
    private static final int MAX_CID = 65535;
    private static final int MAX_ARFCN = 65535;
    private static final int MAX_BSIC = 63;

    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 16-bit GSM Cell Identity described in TS 27.007, 0..65535
    private final int mCid;
    // 16-bit GSM Absolute RF Channel Number
    private final int mArfcn;
    // 6-bit Base Station Identity Code
    private final int mBsic;

    // a list of additional PLMN-IDs reported for this cell
    private final ArraySet<String> mAdditionalPlmns;

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CellIdentityGsm() {
        super(TAG, CellInfo.TYPE_GSM, null, null, null, null);
        mLac = CellInfo.UNAVAILABLE;
        mCid = CellInfo.UNAVAILABLE;
        mArfcn = CellInfo.UNAVAILABLE;
        mBsic = CellInfo.UNAVAILABLE;
        mAdditionalPlmns = new ArraySet<>();
        mGlobalCellId = null;
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
     * @param additionalPlmns a list of additional PLMN IDs broadcast by the cell
     *
     * @hide
     */
    public CellIdentityGsm(int lac, int cid, int arfcn, int bsic, @Nullable String mccStr,
            @Nullable String mncStr, @Nullable String alphal, @Nullable String alphas,
            @NonNull Collection<String> additionalPlmns) {
        super(TAG, CellInfo.TYPE_GSM, mccStr, mncStr, alphal, alphas);
        mLac = inRangeOrUnavailable(lac, 0, MAX_LAC);
        mCid = inRangeOrUnavailable(cid, 0, MAX_CID);
        mArfcn = inRangeOrUnavailable(arfcn, 0, MAX_ARFCN);
        mBsic = inRangeOrUnavailable(bsic, 0, MAX_BSIC);
        mAdditionalPlmns = new ArraySet<>(additionalPlmns.size());
        for (String plmn : additionalPlmns) {
            if (isValidPlmn(plmn)) {
                mAdditionalPlmns.add(plmn);
            }
        }
        updateGlobalCellId();
    }

    private CellIdentityGsm(@NonNull CellIdentityGsm cid) {
        this(cid.mLac, cid.mCid, cid.mArfcn, cid.mBsic, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort, cid.mAdditionalPlmns);
    }

    @NonNull CellIdentityGsm copy() {
        return new CellIdentityGsm(this);
    }

    /** @hide */
    @Override
    public @NonNull CellIdentityGsm sanitizeLocationInfo() {
        return new CellIdentityGsm(CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                CellInfo.UNAVAILABLE, mMccStr, mMncStr, mAlphaLong, mAlphaShort, mAdditionalPlmns);
    }

    /** @hide */
    @Override
    protected void updateGlobalCellId() {
        mGlobalCellId = null;
        String plmn = getPlmn();
        if (plmn == null) return;

        if (mLac == CellInfo.UNAVAILABLE || mCid == CellInfo.UNAVAILABLE) return;

        mGlobalCellId = plmn + formatSimple("%04x%04x", mLac, mCid);
    }

    /**
     * @return 3-digit Mobile Country Code, 0..999,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     * @deprecated Use {@link #getMccString} instead.
     */
    @Deprecated
    public int getMcc() {
        return (mMccStr != null) ? Integer.valueOf(mMccStr) : CellInfo.UNAVAILABLE;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     * @deprecated Use {@link #getMncString} instead.
     */
    @Deprecated
    public int getMnc() {
        return (mMncStr != null) ? Integer.valueOf(mMncStr) : CellInfo.UNAVAILABLE;
    }

    /**
     * @return 16-bit Location Area Code, 0..65535,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getLac() {
        return mLac;
    }

    /**
     * @return 16-bit GSM Cell Identity described in TS 27.007, 0..65535,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 16-bit GSM Absolute RF Channel Number,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getArfcn() {
        return mArfcn;
    }

    /**
     * @return 6-bit Base Station Identity Code,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getBsic() {
        return mBsic;
    }

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown.
     */
    @Nullable
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
    }

    /**
     * @return Mobile Country Code in string format, null if unavailable.
     */
    @Nullable
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string format, null if unavailable.
     */
    @Nullable
    public String getMncString() {
        return mMncStr;
    }

    /** @hide */
    @Override
    public int getChannelNumber() {
        return mArfcn;
    }

    /**
     * @return a list of additional PLMN IDs supported by this cell.
     */
    @NonNull
    public Set<String> getAdditionalPlmns() {
        return Collections.unmodifiableSet(mAdditionalPlmns);
    }

    /**
     * @deprecated Primary Scrambling Code is not applicable to GSM.
     * @return {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} - undefined for GSM
     */
    @Deprecated
    public int getPsc() {
        return CellInfo.UNAVAILABLE;
    }

    /** @hide */
    @NonNull
    @Override
    public GsmCellLocation asCellLocation() {
        GsmCellLocation cl = new GsmCellLocation();
        int lac = mLac != CellInfo.UNAVAILABLE ? mLac : -1;
        int cid = mCid != CellInfo.UNAVAILABLE ? mCid : -1;
        cl.setLacAndCid(lac, cid);
        cl.setPsc(-1);
        return cl;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLac, mCid, mAdditionalPlmns.hashCode(), super.hashCode());
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
                && mAdditionalPlmns.equals(o.mAdditionalPlmns)
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
        .append(" mAdditionalPlmns=").append(mAdditionalPlmns)
        .append("}").toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        super.writeToParcel(dest, CellInfo.TYPE_GSM);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mArfcn);
        dest.writeInt(mBsic);
        dest.writeArraySet(mAdditionalPlmns);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityGsm(Parcel in) {
        super(TAG, CellInfo.TYPE_GSM, in);
        mLac = in.readInt();
        mCid = in.readInt();
        mArfcn = in.readInt();
        mBsic = in.readInt();
        mAdditionalPlmns = (ArraySet<String>) in.readArraySet(null);

        updateGlobalCellId();
        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Creator<CellIdentityGsm> CREATOR =
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
