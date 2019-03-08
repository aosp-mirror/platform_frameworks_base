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
import android.telephony.Rlog;

/**
 * A {@link CellInfo} representing a CDMA cell that provides identity and measurement info.
 */
public final class CellInfoCdma extends CellInfo implements Parcelable {

    private static final String LOG_TAG = "CellInfoCdma";
    private static final boolean DBG = false;

    private CellIdentityCdma mCellIdentityCdma;
    private CellSignalStrengthCdma mCellSignalStrengthCdma;

    /** @hide */
    @UnsupportedAppUsage
    public CellInfoCdma() {
        super();
        mCellIdentityCdma = new CellIdentityCdma();
        mCellSignalStrengthCdma = new CellSignalStrengthCdma();
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public CellInfoCdma(CellInfoCdma ci) {
        super(ci);
        this.mCellIdentityCdma = ci.mCellIdentityCdma.copy();
        this.mCellSignalStrengthCdma = ci.mCellSignalStrengthCdma.copy();
    }

    /** @hide */
    public CellInfoCdma(android.hardware.radio.V1_0.CellInfo ci) {
        super(ci);
        final android.hardware.radio.V1_0.CellInfoCdma cic = ci.cdma.get(0);
        mCellIdentityCdma = new CellIdentityCdma(cic.cellIdentityCdma);
        mCellSignalStrengthCdma =
            new CellSignalStrengthCdma(cic.signalStrengthCdma, cic.signalStrengthEvdo);
    }

    /** @hide */
    public CellInfoCdma(android.hardware.radio.V1_2.CellInfo ci) {
        super(ci);
        final android.hardware.radio.V1_2.CellInfoCdma cic = ci.cdma.get(0);
        mCellIdentityCdma = new CellIdentityCdma(cic.cellIdentityCdma);
        mCellSignalStrengthCdma =
            new CellSignalStrengthCdma(cic.signalStrengthCdma, cic.signalStrengthEvdo);
    }

    /** @hide */
    public CellInfoCdma(android.hardware.radio.V1_4.CellInfo ci, long timeStamp) {
        super(ci, timeStamp);
        final android.hardware.radio.V1_2.CellInfoCdma cic = ci.info.cdma();
        mCellIdentityCdma = new CellIdentityCdma(cic.cellIdentityCdma);
        mCellSignalStrengthCdma =
                new CellSignalStrengthCdma(cic.signalStrengthCdma, cic.signalStrengthEvdo);
    }

    @Override
    public CellIdentityCdma getCellIdentity() {
        return mCellIdentityCdma;
    }
    /** @hide */
    @UnsupportedAppUsage
    public void setCellIdentity(CellIdentityCdma cid) {
        mCellIdentityCdma = cid;
    }

    @Override
    public CellSignalStrengthCdma getCellSignalStrength() {
        return mCellSignalStrengthCdma;
    }
    /** @hide */
    public void setCellSignalStrength(CellSignalStrengthCdma css) {
        mCellSignalStrengthCdma = css;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return super.hashCode() + mCellIdentityCdma.hashCode() + mCellSignalStrengthCdma.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        try {
            CellInfoCdma o = (CellInfoCdma) other;
            return mCellIdentityCdma.equals(o.mCellIdentityCdma)
                    && mCellSignalStrengthCdma.equals(o.mCellSignalStrengthCdma);
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("CellInfoCdma:{");
        sb.append(super.toString());
        sb.append(" ").append(mCellIdentityCdma);
        sb.append(" ").append(mCellSignalStrengthCdma);
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
        super.writeToParcel(dest, flags, TYPE_CDMA);
        mCellIdentityCdma.writeToParcel(dest, flags);
        mCellSignalStrengthCdma.writeToParcel(dest, flags);
    }

    /**
     * Construct a CellInfoCdma object from the given parcel
     * where the token is already been processed.
     */
    private CellInfoCdma(Parcel in) {
        super(in);
        mCellIdentityCdma = CellIdentityCdma.CREATOR.createFromParcel(in);
        mCellSignalStrengthCdma = CellSignalStrengthCdma.CREATOR.createFromParcel(in);
        if (DBG) log("CellInfoCdma(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    public static final Creator<CellInfoCdma> CREATOR = new Creator<CellInfoCdma>() {
        @Override
        public CellInfoCdma createFromParcel(Parcel in) {
            in.readInt(); // Skip past token, we know what it is
            return createFromParcelBody(in);
        }

        @Override
        public CellInfoCdma[] newArray(int size) {
            return new CellInfoCdma[size];
        }
    };

    /** @hide */
    protected static CellInfoCdma createFromParcelBody(Parcel in) {
        return new CellInfoCdma(in);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
