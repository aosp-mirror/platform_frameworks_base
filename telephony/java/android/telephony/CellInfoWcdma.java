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

import com.android.telephony.Rlog;

import java.util.Objects;

/**
 * A {@link CellInfo} representing a WCDMA cell that provides identity and measurement info.
 */
public final class CellInfoWcdma extends CellInfo implements Parcelable {

    private static final String LOG_TAG = "CellInfoWcdma";
    private static final boolean DBG = false;

    private CellIdentityWcdma mCellIdentityWcdma;
    private CellSignalStrengthWcdma mCellSignalStrengthWcdma;

    /** @hide */
    public CellInfoWcdma() {
        super();
        mCellIdentityWcdma = new CellIdentityWcdma();
        mCellSignalStrengthWcdma = new CellSignalStrengthWcdma();
    }

    /** @hide */
    public CellInfoWcdma(CellInfoWcdma ci) {
        super(ci);
        this.mCellIdentityWcdma = ci.mCellIdentityWcdma.copy();
        this.mCellSignalStrengthWcdma = ci.mCellSignalStrengthWcdma.copy();
    }

    /** @hide */
    public CellInfoWcdma(int connectionStatus, boolean registered, long timeStamp,
            CellIdentityWcdma cellIdentityWcdma, CellSignalStrengthWcdma cellSignalStrengthWcdma) {
        super(connectionStatus, registered, timeStamp);
        mCellIdentityWcdma = cellIdentityWcdma;
        mCellSignalStrengthWcdma = cellSignalStrengthWcdma;
    }

    /**
     * @return a {@link CellIdentityWcdma} instance.
     */
    @Override
    public CellIdentityWcdma getCellIdentity() {
        return mCellIdentityWcdma;
    }

    /** @hide */
    public void setCellIdentity(CellIdentityWcdma cid) {
        mCellIdentityWcdma = cid;
    }

    /**
     * @return a {@link CellSignalStrengthWcdma} instance.
     */
    @Override
    public CellSignalStrengthWcdma getCellSignalStrength() {
        return mCellSignalStrengthWcdma;
    }

    /** @hide */
    @Override
    public CellInfo sanitizeLocationInfo() {
        CellInfoWcdma result = new CellInfoWcdma(this);
        result.mCellIdentityWcdma = mCellIdentityWcdma.sanitizeLocationInfo();
        return result;
    }

    /** @hide */
    public void setCellSignalStrength(CellSignalStrengthWcdma css) {
        mCellSignalStrengthWcdma = css;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mCellIdentityWcdma, mCellSignalStrengthWcdma);
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        try {
            CellInfoWcdma o = (CellInfoWcdma) other;
            return mCellIdentityWcdma.equals(o.mCellIdentityWcdma)
                    && mCellSignalStrengthWcdma.equals(o.mCellSignalStrengthWcdma);
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("CellInfoWcdma:{");
        sb.append(super.toString());
        sb.append(" ").append(mCellIdentityWcdma);
        sb.append(" ").append(mCellSignalStrengthWcdma);
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
        super.writeToParcel(dest, flags, TYPE_WCDMA);
        mCellIdentityWcdma.writeToParcel(dest, flags);
        mCellSignalStrengthWcdma.writeToParcel(dest, flags);
    }

    /**
     * Construct a CellInfoWcdma object from the given parcel
     * where the token is already been processed.
     */
    private CellInfoWcdma(Parcel in) {
        super(in);
        mCellIdentityWcdma = CellIdentityWcdma.CREATOR.createFromParcel(in);
        mCellSignalStrengthWcdma = CellSignalStrengthWcdma.CREATOR.createFromParcel(in);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellInfoWcdma> CREATOR = new Creator<CellInfoWcdma>() {
        @Override
        public CellInfoWcdma createFromParcel(Parcel in) {
            in.readInt(); // Skip past token, we know what it is
            return createFromParcelBody(in);
        }

        @Override
        public CellInfoWcdma[] newArray(int size) {
            return new CellInfoWcdma[size];
        }
    };

    /** @hide */
    protected static CellInfoWcdma createFromParcelBody(Parcel in) {
        return new CellInfoWcdma(in);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
