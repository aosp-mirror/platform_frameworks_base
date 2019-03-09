/*
 * Copyright (C) 2018 The Android Open Source Project
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

import java.util.Objects;

/**
 * A {@link CellInfo} representing a TD-SCDMA cell that provides identity and measurement info.
 *
 * @hide
 */
public final class CellInfoTdscdma extends CellInfo implements Parcelable {

    private static final String LOG_TAG = "CellInfoTdscdma";
    private static final boolean DBG = false;

    private CellIdentityTdscdma mCellIdentityTdscdma;
    private CellSignalStrengthTdscdma mCellSignalStrengthTdscdma;

    /** @hide */
    public CellInfoTdscdma() {
        super();
        mCellIdentityTdscdma = new CellIdentityTdscdma();
        mCellSignalStrengthTdscdma = new CellSignalStrengthTdscdma();
    }

    /** @hide */
    public CellInfoTdscdma(CellInfoTdscdma ci) {
        super(ci);
        this.mCellIdentityTdscdma = ci.mCellIdentityTdscdma.copy();
        this.mCellSignalStrengthTdscdma = ci.mCellSignalStrengthTdscdma.copy();
    }

    /** @hide */
    public CellInfoTdscdma(android.hardware.radio.V1_0.CellInfo ci) {
        super(ci);
        final android.hardware.radio.V1_0.CellInfoTdscdma cit = ci.tdscdma.get(0);
        mCellIdentityTdscdma = new CellIdentityTdscdma(cit.cellIdentityTdscdma);
        mCellSignalStrengthTdscdma = new CellSignalStrengthTdscdma(cit.signalStrengthTdscdma);
    }

    /** @hide */
    public CellInfoTdscdma(android.hardware.radio.V1_2.CellInfo ci) {
        super(ci);
        final android.hardware.radio.V1_2.CellInfoTdscdma cit = ci.tdscdma.get(0);
        mCellIdentityTdscdma = new CellIdentityTdscdma(cit.cellIdentityTdscdma);
        mCellSignalStrengthTdscdma = new CellSignalStrengthTdscdma(cit.signalStrengthTdscdma);
    }

    /** @hide */
    public CellInfoTdscdma(android.hardware.radio.V1_4.CellInfo ci, long timeStamp) {
        super(ci, timeStamp);
        final android.hardware.radio.V1_2.CellInfoTdscdma cit = ci.info.tdscdma();
        mCellIdentityTdscdma = new CellIdentityTdscdma(cit.cellIdentityTdscdma);
        mCellSignalStrengthTdscdma = new CellSignalStrengthTdscdma(cit.signalStrengthTdscdma);
    }

    @Override public CellIdentityTdscdma getCellIdentity() {
        return mCellIdentityTdscdma;
    }
    /** @hide */
    public void setCellIdentity(CellIdentityTdscdma cid) {
        mCellIdentityTdscdma = cid;
    }

    @Override
    public CellSignalStrengthTdscdma getCellSignalStrength() {
        return mCellSignalStrengthTdscdma;
    }
    /** @hide */
    public void setCellSignalStrength(CellSignalStrengthTdscdma css) {
        mCellSignalStrengthTdscdma = css;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mCellIdentityTdscdma, mCellSignalStrengthTdscdma);
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        try {
            CellInfoTdscdma o = (CellInfoTdscdma) other;
            return mCellIdentityTdscdma.equals(o.mCellIdentityTdscdma)
                    && mCellSignalStrengthTdscdma.equals(o.mCellSignalStrengthTdscdma);
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("CellInfoTdscdma:{");
        sb.append(super.toString());
        sb.append(" ").append(mCellIdentityTdscdma);
        sb.append(" ").append(mCellSignalStrengthTdscdma);
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
        super.writeToParcel(dest, flags, TYPE_TDSCDMA);
        mCellIdentityTdscdma.writeToParcel(dest, flags);
        mCellSignalStrengthTdscdma.writeToParcel(dest, flags);
    }

    /**
     * Construct a CellInfoTdscdma object from the given parcel
     * where the token is already been processed.
     */
    private CellInfoTdscdma(Parcel in) {
        super(in);
        mCellIdentityTdscdma = CellIdentityTdscdma.CREATOR.createFromParcel(in);
        mCellSignalStrengthTdscdma = CellSignalStrengthTdscdma.CREATOR.createFromParcel(in);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellInfoTdscdma> CREATOR = new Creator<CellInfoTdscdma>() {
        @Override
        public CellInfoTdscdma createFromParcel(Parcel in) {
            in.readInt(); // Skip past token, we know what it is
            return createFromParcelBody(in);
        }

        @Override
        public CellInfoTdscdma[] newArray(int size) {
            return new CellInfoTdscdma[size];
        }
    };

    /** @hide */
    protected static CellInfoTdscdma createFromParcelBody(Parcel in) {
        return new CellInfoTdscdma(in);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
