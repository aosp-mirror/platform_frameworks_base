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

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A {@link CellInfo} representing an LTE cell that provides identity and measurement info.
 */
public final class CellInfoLte extends CellInfo implements Parcelable {

    private static final String LOG_TAG = "CellInfoLte";
    private static final boolean DBG = false;

    private CellIdentityLte mCellIdentityLte;
    private CellSignalStrengthLte mCellSignalStrengthLte;
    private CellConfigLte mCellConfig;

    /** @hide */
    @UnsupportedAppUsage
    public CellInfoLte() {
        super();
        mCellIdentityLte = new CellIdentityLte();
        mCellSignalStrengthLte = new CellSignalStrengthLte();
        mCellConfig = new CellConfigLte();
    }

    /** @hide */
    public CellInfoLte(CellInfoLte ci) {
        super(ci);
        this.mCellIdentityLte = ci.mCellIdentityLte.copy();
        this.mCellSignalStrengthLte = ci.mCellSignalStrengthLte.copy();
        this.mCellConfig = new CellConfigLte(ci.mCellConfig);
    }

    /** @hide */
    public CellInfoLte(android.hardware.radio.V1_0.CellInfo ci) {
        super(ci);
        final android.hardware.radio.V1_0.CellInfoLte cil = ci.lte.get(0);
        mCellIdentityLte = new CellIdentityLte(cil.cellIdentityLte);
        mCellSignalStrengthLte = new CellSignalStrengthLte(cil.signalStrengthLte);
        mCellConfig = new CellConfigLte();
    }

    /** @hide */
    public CellInfoLte(android.hardware.radio.V1_2.CellInfo ci) {
        super(ci);
        final android.hardware.radio.V1_2.CellInfoLte cil = ci.lte.get(0);
        mCellIdentityLte = new CellIdentityLte(cil.cellIdentityLte);
        mCellSignalStrengthLte = new CellSignalStrengthLte(cil.signalStrengthLte);
        mCellConfig = new CellConfigLte();
    }

    /** @hide */
    public CellInfoLte(android.hardware.radio.V1_4.CellInfo ci, long timeStamp) {
        super(ci, timeStamp);
        final android.hardware.radio.V1_4.CellInfoLte cil = ci.info.lte();
        mCellIdentityLte = new CellIdentityLte(cil.base.cellIdentityLte);
        mCellSignalStrengthLte = new CellSignalStrengthLte(cil.base.signalStrengthLte);
        mCellConfig = new CellConfigLte(cil.cellConfig);
    }

    @Override
    public CellIdentityLte getCellIdentity() {
        if (DBG) log("getCellIdentity: " + mCellIdentityLte);
        return mCellIdentityLte;
    }
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCellIdentity(CellIdentityLte cid) {
        if (DBG) log("setCellIdentity: " + cid);
        mCellIdentityLte = cid;
    }

    @Override
    public CellSignalStrengthLte getCellSignalStrength() {
        if (DBG) log("getCellSignalStrength: " + mCellSignalStrengthLte);
        return mCellSignalStrengthLte;
    }
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCellSignalStrength(CellSignalStrengthLte css) {
        if (DBG) log("setCellSignalStrength: " + css);
        mCellSignalStrengthLte = css;
    }

    /** @hide */
    public void setCellConfig(CellConfigLte cellConfig) {
        if (DBG) log("setCellConfig: " + cellConfig);
        mCellConfig = cellConfig;
    }

    /** @hide */
    public CellConfigLte getCellConfig() {
        if (DBG) log("getCellConfig: " + mCellConfig);
        return mCellConfig;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                mCellIdentityLte.hashCode(),
                mCellSignalStrengthLte.hashCode(),
                mCellConfig.hashCode());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CellInfoLte)) return false;
        CellInfoLte o = (CellInfoLte) other;
        return super.equals(o) && mCellIdentityLte.equals(o.mCellIdentityLte)
                && mCellSignalStrengthLte.equals(o.mCellSignalStrengthLte)
                && mCellConfig.equals(o.mCellConfig);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("CellInfoLte:{");
        sb.append(super.toString());
        sb.append(" ").append(mCellIdentityLte);
        sb.append(" ").append(mCellSignalStrengthLte);
        sb.append(" ").append(mCellConfig);
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
        super.writeToParcel(dest, flags, TYPE_LTE);
        mCellIdentityLte.writeToParcel(dest, flags);
        mCellSignalStrengthLte.writeToParcel(dest, flags);
        mCellConfig.writeToParcel(dest, flags);
    }

    /**
     * Construct a CellInfoLte object from the given parcel
     * where the TYPE_LTE token is already been processed.
     */
    private CellInfoLte(Parcel in) {
        super(in);
        mCellIdentityLte = CellIdentityLte.CREATOR.createFromParcel(in);
        mCellSignalStrengthLte = CellSignalStrengthLte.CREATOR.createFromParcel(in);
        mCellConfig = CellConfigLte.CREATOR.createFromParcel(in);
        if (DBG) log("CellInfoLte(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellInfoLte> CREATOR = new Creator<CellInfoLte>() {
        @Override
        public CellInfoLte createFromParcel(Parcel in) {
            in.readInt(); // Skip past token, we know what it is
            return createFromParcelBody(in);
        }

        @Override
        public CellInfoLte[] newArray(int size) {
            return new CellInfoLte[size];
        }
    };

    /** @hide */
    protected static CellInfoLte createFromParcelBody(Parcel in) {
        return new CellInfoLte(in);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
