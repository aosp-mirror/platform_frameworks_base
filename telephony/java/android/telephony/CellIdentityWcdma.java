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

import static android.text.TextUtils.formatSimple;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * CellIdentity to represent a unique UMTS cell
 */
public final class CellIdentityWcdma extends CellIdentity {
    private static final String TAG = CellIdentityWcdma.class.getSimpleName();
    private static final boolean DBG = false;

    private static final int MAX_LAC = 65535;
    private static final int MAX_CID = 268435455;
    private static final int MAX_PSC = 511;
    private static final int MAX_UARFCN = 16383; // a 14 bit number; TS 25.331 ex sec 10.3.8.15

    // 16-bit Location Area Code, 0..65535
    private final int mLac;
    // 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455
    private final int mCid;
    // 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511
    private final int mPsc;
    // 16-bit UMTS Absolute RF Channel Number described in TS 25.101 sec. 5.4.4
    @UnsupportedAppUsage
    private final int mUarfcn;

    // a list of additional PLMN-IDs reported for this cell
    private final ArraySet<String> mAdditionalPlmns;

    @Nullable
    private final ClosedSubscriberGroupInfo mCsgInfo;

    /**
     * @hide
     */
    public CellIdentityWcdma() {
        super(TAG, CellInfo.TYPE_WCDMA, null, null, null, null);
        mLac = CellInfo.UNAVAILABLE;
        mCid = CellInfo.UNAVAILABLE;
        mPsc = CellInfo.UNAVAILABLE;
        mUarfcn = CellInfo.UNAVAILABLE;
        mAdditionalPlmns = new ArraySet<>();
        mCsgInfo = null;
        mGlobalCellId = null;
    }

    /**
     * public constructor
     * @param lac 16-bit Location Area Code, 0..65535
     * @param cid 28-bit UMTS Cell Identity
     * @param psc 9-bit UMTS Primary Scrambling Code
     * @param uarfcn 16-bit UMTS Absolute RF Channel Number described in TS 25.101 sec. 5.4.3
     * @param mccStr 3-digit Mobile Country Code in string format
     * @param mncStr 2 or 3-digit Mobile Network Code in string format
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String
     * @param additionalPlmns a list of additional PLMN IDs broadcast by the cell
     * @param csgInfo info about the closed subscriber group broadcast by the cell
     *
     * @hide
     */
    public CellIdentityWcdma(int lac, int cid, int psc, int uarfcn, @Nullable String mccStr,
            @Nullable String mncStr, @Nullable String alphal, @Nullable String alphas,
            @NonNull Collection<String> additionalPlmns,
            @Nullable ClosedSubscriberGroupInfo csgInfo) {
        super(TAG, CellInfo.TYPE_WCDMA, mccStr, mncStr, alphal, alphas);
        mLac = inRangeOrUnavailable(lac, 0, MAX_LAC);
        mCid = inRangeOrUnavailable(cid, 0, MAX_CID);
        mPsc = inRangeOrUnavailable(psc, 0, MAX_PSC);
        mUarfcn = inRangeOrUnavailable(uarfcn, 0, MAX_UARFCN);
        mAdditionalPlmns = new ArraySet<>(additionalPlmns.size());
        for (String plmn : additionalPlmns) {
            if (isValidPlmn(plmn)) {
                mAdditionalPlmns.add(plmn);
            }
        }
        mCsgInfo = csgInfo;
        updateGlobalCellId();
    }

    /** @hide */
    public CellIdentityWcdma(@NonNull android.hardware.radio.V1_0.CellIdentityWcdma cid) {
        this(cid.lac, cid.cid, cid.psc, cid.uarfcn, cid.mcc, cid.mnc, "", "",
                new ArraySet<>(), null);
    }

    /** @hide */
    public CellIdentityWcdma(@NonNull android.hardware.radio.V1_2.CellIdentityWcdma cid) {
        this(cid.base.lac, cid.base.cid, cid.base.psc, cid.base.uarfcn,
                cid.base.mcc, cid.base.mnc, cid.operatorNames.alphaLong,
                cid.operatorNames.alphaShort, new ArraySet<>(), null);
    }

    /** @hide */
    public CellIdentityWcdma(@NonNull android.hardware.radio.V1_5.CellIdentityWcdma cid) {
        this(cid.base.base.lac, cid.base.base.cid, cid.base.base.psc, cid.base.base.uarfcn,
                cid.base.base.mcc, cid.base.base.mnc, cid.base.operatorNames.alphaLong,
                cid.base.operatorNames.alphaShort, cid.additionalPlmns,
                cid.optionalCsgInfo.getDiscriminator()
                        == android.hardware.radio.V1_5.OptionalCsgInfo.hidl_discriminator.csgInfo
                                ? new ClosedSubscriberGroupInfo(cid.optionalCsgInfo.csgInfo())
                                        : null);
    }

    private CellIdentityWcdma(@NonNull CellIdentityWcdma cid) {
        this(cid.mLac, cid.mCid, cid.mPsc, cid.mUarfcn, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort, cid.mAdditionalPlmns, cid.mCsgInfo);
    }

    /** @hide */
    @Override
    public @NonNull CellIdentityWcdma sanitizeLocationInfo() {
        return new CellIdentityWcdma(CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, mMccStr, mMncStr,
                mAlphaLong, mAlphaShort, mAdditionalPlmns, null);
    }

    @NonNull CellIdentityWcdma copy() {
        return new CellIdentityWcdma(this);
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
     * @return CID
     * 28-bit UMTS Cell Identity described in TS 25.331, 0..268435455,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getCid() {
        return mCid;
    }

    /**
     * @return 9-bit UMTS Primary Scrambling Code described in TS 25.331, 0..511,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getPsc() {
        return mPsc;
    }

    /**
     * @return Mobile Country Code in string version, null if unavailable.
     */
    @Nullable
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string version, null if unavailable.
     */
    @Nullable
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

    @Override
    public int hashCode() {
        return Objects.hash(mLac, mCid, mPsc, mAdditionalPlmns.hashCode(), super.hashCode());
    }

    /**
     * @return 16-bit UMTS Absolute RF Channel Number,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getUarfcn() {
        return mUarfcn;
    }

    /** @hide */
    @Override
    public int getChannelNumber() {
        return mUarfcn;
    }

    /**
     * @return a list of additional PLMN IDs supported by this cell.
     */
    @NonNull
    public Set<String> getAdditionalPlmns() {
        return Collections.unmodifiableSet(mAdditionalPlmns);
    }

    /**
     * @return closed subscriber group information about the cell if available, otherwise null.
     */
    @Nullable
    public ClosedSubscriberGroupInfo getClosedSubscriberGroupInfo() {
        return mCsgInfo;
    }

    /** @hide */
    @NonNull
    @Override
    public GsmCellLocation asCellLocation() {
        GsmCellLocation cl = new GsmCellLocation();
        int lac = mLac != CellInfo.UNAVAILABLE ? mLac : -1;
        int cid = mCid != CellInfo.UNAVAILABLE ? mCid : -1;
        int psc = mPsc != CellInfo.UNAVAILABLE ? mPsc : -1;
        cl.setLacAndCid(lac, cid);
        cl.setPsc(psc);

        return cl;
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
                && mAdditionalPlmns.equals(o.mAdditionalPlmns)
                && Objects.equals(mCsgInfo, o.mCsgInfo)
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
        .append(" mAdditionalPlmns=").append(mAdditionalPlmns)
        .append(" mCsgInfo=").append(mCsgInfo)
        .append("}").toString();
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        super.writeToParcel(dest, CellInfo.TYPE_WCDMA);
        dest.writeInt(mLac);
        dest.writeInt(mCid);
        dest.writeInt(mPsc);
        dest.writeInt(mUarfcn);
        dest.writeArraySet(mAdditionalPlmns);
        dest.writeParcelable(mCsgInfo, flags);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityWcdma(Parcel in) {
        super(TAG, CellInfo.TYPE_WCDMA, in);
        mLac = in.readInt();
        mCid = in.readInt();
        mPsc = in.readInt();
        mUarfcn = in.readInt();
        mAdditionalPlmns = (ArraySet<String>) in.readArraySet(null);
        mCsgInfo = in.readParcelable(null);

        updateGlobalCellId();
        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Creator<CellIdentityWcdma> CREATOR =
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
