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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.telephony.Rlog;

import java.util.Objects;

/**
 * A {@link CellInfo} representing a TD-SCDMA cell that provides identity and measurement info.
 *
 * @see android.telephony.CellInfo
 * @see android.telephony.CellSignalStrengthTdscdma
 * @see android.telephony.CellIdentityTdscdma
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
    public CellInfoTdscdma(int connectionStatus, boolean registered, long timeStamp,
            CellIdentityTdscdma cellIdentityTdscdma,
            CellSignalStrengthTdscdma cellSignalStrengthTdscdma) {
        super(connectionStatus, registered, timeStamp);
        mCellIdentityTdscdma = cellIdentityTdscdma;
        mCellSignalStrengthTdscdma = cellSignalStrengthTdscdma;
    }

    /**
     * @return a {@link CellIdentityTdscdma} instance.
     */
    @Override
    public @NonNull CellIdentityTdscdma getCellIdentity() {
        return mCellIdentityTdscdma;
    }

    /** @hide */
    public void setCellIdentity(CellIdentityTdscdma cid) {
        mCellIdentityTdscdma = cid;
    }

    /**
     * @return a {@link CellSignalStrengthTdscdma} instance.
     */
    @Override
    public @NonNull CellSignalStrengthTdscdma getCellSignalStrength() {
        return mCellSignalStrengthTdscdma;
    }

    /** @hide */
    @Override
    public CellInfo sanitizeLocationInfo() {
        CellInfoTdscdma result = new CellInfoTdscdma(this);
        result.mCellIdentityTdscdma = mCellIdentityTdscdma.sanitizeLocationInfo();
        return result;
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
    @NonNull
    public static final Creator<CellInfoTdscdma> CREATOR = new Creator<CellInfoTdscdma>() {
        @Override
        public @NonNull CellInfoTdscdma createFromParcel(Parcel in) {
            in.readInt(); // Skip past token, we know what it is
            return createFromParcelBody(in);
        }

        @Override
        public @NonNull CellInfoTdscdma[] newArray(int size) {
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
