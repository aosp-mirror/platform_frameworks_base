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

import java.util.Objects;

/**
 * CellIdentity is to represent a unique LTE cell
 */
public final class CellIdentityLte implements Parcelable {

    private static final String LOG_TAG = "CellIdentityLte";
    private static final boolean DBG = false;

    // 3-digit Mobile Country Code, 0..999
    private final int mMcc;
    // 2 or 3-digit Mobile Network Code, 0..999
    private final int mMnc;
    // 28-bit cell identity
    private final int mCi;
    // physical cell id 0..503
    private final int mPci;
    // 16-bit tracking area code
    private final int mTac;
    // 18-bit Absolute RF Channel Number
    private final int mEarfcn;

    /**
     * @hide
     */
    public CellIdentityLte() {
        mMcc = Integer.MAX_VALUE;
        mMnc = Integer.MAX_VALUE;
        mCi = Integer.MAX_VALUE;
        mPci = Integer.MAX_VALUE;
        mTac = Integer.MAX_VALUE;
        mEarfcn = Integer.MAX_VALUE;
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
    public CellIdentityLte (int mcc, int mnc, int ci, int pci, int tac) {
        this(mcc, mnc, ci, pci, tac, Integer.MAX_VALUE);
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
    public CellIdentityLte (int mcc, int mnc, int ci, int pci, int tac, int earfcn) {
        mMcc = mcc;
        mMnc = mnc;
        mCi = ci;
        mPci = pci;
        mTac = tac;
        mEarfcn = earfcn;
    }

    private CellIdentityLte(CellIdentityLte cid) {
        mMcc = cid.mMcc;
        mMnc = cid.mMnc;
        mCi = cid.mCi;
        mPci = cid.mPci;
        mTac = cid.mTac;
        mEarfcn = cid.mEarfcn;
    }

    CellIdentityLte copy() {
        return new CellIdentityLte(this);
    }

    /**
     * @return 3-digit Mobile Country Code, 0..999, Integer.MAX_VALUE if unknown
     */
    public int getMcc() {
        return mMcc;
    }

    /**
     * @return 2 or 3-digit Mobile Network Code, 0..999, Integer.MAX_VALUE if unknown
     */
    public int getMnc() {
        return mMnc;
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

    @Override
    public int hashCode() {
        return Objects.hash(mMcc, mMnc, mCi, mPci, mTac);
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
        return mMcc == o.mMcc &&
                mMnc == o.mMnc &&
                mCi == o.mCi &&
                mPci == o.mPci &&
                mTac == o.mTac &&
                mEarfcn == o.mEarfcn;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CellIdentityLte:{");
        sb.append(" mMcc="); sb.append(mMcc);
        sb.append(" mMnc="); sb.append(mMnc);
        sb.append(" mCi="); sb.append(mCi);
        sb.append(" mPci="); sb.append(mPci);
        sb.append(" mTac="); sb.append(mTac);
        sb.append(" mEarfcn="); sb.append(mEarfcn);
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
        dest.writeInt(mMcc);
        dest.writeInt(mMnc);
        dest.writeInt(mCi);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
        dest.writeInt(mEarfcn);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityLte(Parcel in) {
        mMcc = in.readInt();
        mMnc = in.readInt();
        mCi = in.readInt();
        mPci = in.readInt();
        mTac = in.readInt();
        mEarfcn = in.readInt();
        if (DBG) log("CellIdentityLte(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Creator<CellIdentityLte> CREATOR =
            new Creator<CellIdentityLte>() {
        @Override
        public CellIdentityLte createFromParcel(Parcel in) {
            return new CellIdentityLte(in);
        }

        @Override
        public CellIdentityLte[] newArray(int size) {
            return new CellIdentityLte[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
