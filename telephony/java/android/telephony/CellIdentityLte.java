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
import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.telephony.gsm.GsmCellLocation;
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
    @UnsupportedAppUsage
    public CellIdentityLte() {
        super(TAG, CellInfo.TYPE_LTE, null, null, null, null);
        mCi = CellInfo.UNAVAILABLE;
        mPci = CellInfo.UNAVAILABLE;
        mTac = CellInfo.UNAVAILABLE;
        mEarfcn = CellInfo.UNAVAILABLE;
        mBandwidth = CellInfo.UNAVAILABLE;
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
        this(ci, pci, tac, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, String.valueOf(mcc),
                String.valueOf(mnc), null, null);
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
        super(TAG, CellInfo.TYPE_LTE, mccStr, mncStr, alphal, alphas);
        mCi = ci;
        mPci = pci;
        mTac = tac;
        mEarfcn = earfcn;
        mBandwidth = bandwidth;
    }

    /** @hide */
    public CellIdentityLte(android.hardware.radio.V1_0.CellIdentityLte cid) {
        this(cid.ci, cid.pci, cid.tac, cid.earfcn, CellInfo.UNAVAILABLE, cid.mcc, cid.mnc, "", "");
    }

    /** @hide */
    public CellIdentityLte(android.hardware.radio.V1_2.CellIdentityLte cid) {
        this(cid.base.ci, cid.base.pci, cid.base.tac, cid.base.earfcn, cid.bandwidth,
                cid.base.mcc, cid.base.mnc, cid.operatorNames.alphaLong,
                cid.operatorNames.alphaShort);
    }

    private CellIdentityLte(CellIdentityLte cid) {
        this(cid.mCi, cid.mPci, cid.mTac, cid.mEarfcn, cid.mBandwidth, cid.mMccStr,
                cid.mMncStr, cid.mAlphaLong, cid.mAlphaShort);
    }

    CellIdentityLte copy() {
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
     * @return Cell bandwidth in kHz,
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getBandwidth() {
        return mBandwidth;
    }

    /**
     * @return Mobile Country Code in string format, null if unavailable.
     */
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string format, null if unavailable.
     */
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
        super.writeToParcel(dest, CellInfo.TYPE_LTE);
        dest.writeInt(mCi);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
        dest.writeInt(mEarfcn);
        dest.writeInt(mBandwidth);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityLte(Parcel in) {
        super(TAG, CellInfo.TYPE_LTE, in);
        mCi = in.readInt();
        mPci = in.readInt();
        mTac = in.readInt();
        mEarfcn = in.readInt();
        mBandwidth = in.readInt();

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
