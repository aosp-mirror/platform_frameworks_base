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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * CellIdentity is to represent a unique LTE cell
 */
public final class CellIdentityLte extends CellIdentity {
    private static final String TAG = CellIdentityLte.class.getSimpleName();
    private static final boolean DBG = false;

    private static final int MAX_CI = 268435455;
    private static final int MAX_PCI = 503;
    private static final int MAX_TAC = 65535;
    private static final int MAX_EARFCN = 262143;
    private static final int MAX_BANDWIDTH = 20000;

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
    // cell bands
    private final int[] mBands;

    // a list of additional PLMN-IDs reported for this cell
    private final ArraySet<String> mAdditionalPlmns;

    private ClosedSubscriberGroupInfo mCsgInfo;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public CellIdentityLte() {
        super(TAG, CellInfo.TYPE_LTE, null, null, null, null);
        mCi = CellInfo.UNAVAILABLE;
        mPci = CellInfo.UNAVAILABLE;
        mTac = CellInfo.UNAVAILABLE;
        mEarfcn = CellInfo.UNAVAILABLE;
        mBands = new int[] {};
        mBandwidth = CellInfo.UNAVAILABLE;
        mAdditionalPlmns = new ArraySet<>();
        mCsgInfo = null;
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public CellIdentityLte(int mcc, int mnc, int ci, int pci, int tac) {
        this(ci, pci, tac, CellInfo.UNAVAILABLE, new int[] {}, CellInfo.UNAVAILABLE,
                String.valueOf(mcc), String.valueOf(mnc), null, null, new ArraySet<>(),
                null);
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
     * @param additionalPlmns a list of additional PLMN IDs broadcast by the cell
     * @param csgInfo info about the closed subscriber group broadcast by the cell
     *
     * @hide
     */
    public CellIdentityLte(int ci, int pci, int tac, int earfcn, @NonNull int[] bands,
            int bandwidth, @Nullable String mccStr, @Nullable String mncStr,
            @Nullable String alphal, @Nullable String alphas,
            @NonNull Collection<String> additionalPlmns,
            @Nullable ClosedSubscriberGroupInfo csgInfo) {
        super(TAG, CellInfo.TYPE_LTE, mccStr, mncStr, alphal, alphas);
        mCi = inRangeOrUnavailable(ci, 0, MAX_CI);
        mPci = inRangeOrUnavailable(pci, 0, MAX_PCI);
        mTac = inRangeOrUnavailable(tac, 0, MAX_TAC);
        mEarfcn = inRangeOrUnavailable(earfcn, 0, MAX_EARFCN);
        mBands = bands;
        mBandwidth = inRangeOrUnavailable(bandwidth, 0, MAX_BANDWIDTH);
        mAdditionalPlmns = new ArraySet<>(additionalPlmns.size());
        for (String plmn : additionalPlmns) {
            if (isValidPlmn(plmn)) {
                mAdditionalPlmns.add(plmn);
            }
        }
        mCsgInfo = csgInfo;
    }

    /** @hide */
    public CellIdentityLte(@NonNull android.hardware.radio.V1_0.CellIdentityLte cid) {
        this(cid.ci, cid.pci, cid.tac, cid.earfcn, new int[] {},
                CellInfo.UNAVAILABLE, cid.mcc, cid.mnc, "", "", new ArraySet<>(), null);
    }

    /** @hide */
    public CellIdentityLte(@NonNull android.hardware.radio.V1_2.CellIdentityLte cid) {
        this(cid.base.ci, cid.base.pci, cid.base.tac, cid.base.earfcn, new int[] {},
                cid.bandwidth, cid.base.mcc, cid.base.mnc, cid.operatorNames.alphaLong,
                cid.operatorNames.alphaShort, new ArraySet<>(), null);
    }

    /** @hide */
    public CellIdentityLte(@NonNull android.hardware.radio.V1_5.CellIdentityLte cid) {
        this(cid.base.base.ci, cid.base.base.pci, cid.base.base.tac, cid.base.base.earfcn,
                cid.bands.stream().mapToInt(Integer::intValue).toArray(), cid.base.bandwidth,
                cid.base.base.mcc, cid.base.base.mnc, cid.base.operatorNames.alphaLong,
                cid.base.operatorNames.alphaShort, cid.additionalPlmns,
                cid.optionalCsgInfo.getDiscriminator()
                        == android.hardware.radio.V1_5.OptionalCsgInfo.hidl_discriminator.csgInfo
                                ? new ClosedSubscriberGroupInfo(cid.optionalCsgInfo.csgInfo())
                                        : null);
    }

    private CellIdentityLte(@NonNull CellIdentityLte cid) {
        this(cid.mCi, cid.mPci, cid.mTac, cid.mEarfcn, cid.mBands, cid.mBandwidth, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort, cid.mAdditionalPlmns, cid.mCsgInfo);
    }

    /** @hide */
    @Override
    public @NonNull CellIdentityLte sanitizeLocationInfo() {
        return new CellIdentityLte(CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                CellInfo.UNAVAILABLE, mBands, CellInfo.UNAVAILABLE,
                mMccStr, mMncStr, mAlphaLong, mAlphaShort, mAdditionalPlmns, null);
    }

    @NonNull CellIdentityLte copy() {
        return new CellIdentityLte(this);
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
     * @return 28-bit Cell Identity,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getCi() {
        return mCi;
    }

    /**
     * @return Physical Cell Id 0..503,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getPci() {
        return mPci;
    }

    /**
     * @return 16-bit Tracking Area Code,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getTac() {
        return mTac;
    }

    /**
     * @return 18-bit Absolute RF Channel Number,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getEarfcn() {
        return mEarfcn;
    }

    /**
     * Get bands of the cell
     *
     * Reference: 3GPP TS 36.101 section 5.5
     *
     * @return Array of band number or empty array if not available.
     */
    @NonNull
    public int[] getBands() {
        return Arrays.copyOf(mBands, mBands.length);
    }

    /**
     * @return Cell bandwidth in kHz,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getBandwidth() {
        return mBandwidth;
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

    /**
     * @return a 5 or 6 character string (MCC+MNC), null if any field is unknown.
     */
    @Nullable
    public String getMobileNetworkOperator() {
        return (mMccStr == null || mMncStr == null) ? null : mMccStr + mMncStr;
    }

    /** @hide */
    @Override
    public int getChannelNumber() {
        return mEarfcn;
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

    /**
     * A hack to allow tunneling of LTE information via GsmCellLocation
     * so that older Network Location Providers can return some information
     * on LTE only networks, see bug 9228974.
     *
     * The tunnel'd LTE information is returned as follows:
     *   LAC = TAC field
     *   CID = CI field
     *   PSC = 0.
     *
     * @hide
     */
    @NonNull
    @Override
    public GsmCellLocation asCellLocation() {
        GsmCellLocation cl = new GsmCellLocation();
        int tac = mTac != CellInfo.UNAVAILABLE ? mTac : -1;
        int cid = mCi != CellInfo.UNAVAILABLE ? mCi : -1;
        cl.setLacAndCid(tac, cid);
        cl.setPsc(0);
        return cl;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCi, mPci, mTac, mEarfcn, Arrays.hashCode(mBands),
                mBandwidth, mAdditionalPlmns.hashCode(), mCsgInfo, super.hashCode());
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
                && Arrays.equals(mBands, o.mBands)
                && mBandwidth == o.mBandwidth
                && TextUtils.equals(mMccStr, o.mMccStr)
                && TextUtils.equals(mMncStr, o.mMncStr)
                && mAdditionalPlmns.equals(o.mAdditionalPlmns)
                && Objects.equals(mCsgInfo, o.mCsgInfo)
                && super.equals(other);
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG)
        .append(":{ mCi=").append(mCi)
        .append(" mPci=").append(mPci)
        .append(" mTac=").append(mTac)
        .append(" mEarfcn=").append(mEarfcn)
        .append(" mBands=").append(mBands)
        .append(" mBandwidth=").append(mBandwidth)
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
        super.writeToParcel(dest, CellInfo.TYPE_LTE);
        dest.writeInt(mCi);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
        dest.writeInt(mEarfcn);
        dest.writeIntArray(mBands);
        dest.writeInt(mBandwidth);
        dest.writeArraySet(mAdditionalPlmns);
        dest.writeParcelable(mCsgInfo, flags);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityLte(Parcel in) {
        super(TAG, CellInfo.TYPE_LTE, in);
        mCi = in.readInt();
        mPci = in.readInt();
        mTac = in.readInt();
        mEarfcn = in.readInt();
        mBands = in.createIntArray();
        mBandwidth = in.readInt();
        mAdditionalPlmns = (ArraySet<String>) in.readArraySet(null);
        mCsgInfo = in.readParcelable(null);
        if (DBG) log(toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Creator<CellIdentityLte> CREATOR =
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
