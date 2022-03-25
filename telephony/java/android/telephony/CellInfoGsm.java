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
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.telephony.Rlog;

/**
 * A {@link CellInfo} representing a GSM cell that provides identity and measurement info.
 */
public final class CellInfoGsm extends CellInfo implements Parcelable {

    private static final String LOG_TAG = "CellInfoGsm";
    private static final boolean DBG = false;

    private CellIdentityGsm mCellIdentityGsm;
    private CellSignalStrengthGsm mCellSignalStrengthGsm;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CellInfoGsm() {
        super();
        mCellIdentityGsm = new CellIdentityGsm();
        mCellSignalStrengthGsm = new CellSignalStrengthGsm();
    }

    /** @hide */
    public CellInfoGsm(CellInfoGsm ci) {
        super(ci);
        mCellIdentityGsm = ci.mCellIdentityGsm.copy();
        mCellSignalStrengthGsm = ci.mCellSignalStrengthGsm.copy();
    }

    /** @hide */
    public CellInfoGsm(int cellConnectionStatus, boolean registered, long timeStamp,
            CellIdentityGsm cellIdentityGsm, CellSignalStrengthGsm cellSignalStrengthGsm) {
        super(cellConnectionStatus, registered, timeStamp);
        mCellIdentityGsm = cellIdentityGsm;
        mCellSignalStrengthGsm = cellSignalStrengthGsm;
    }

    /**
     * @return a {@link CellIdentityGsm} instance.
     */
    @Override
    public @NonNull CellIdentityGsm getCellIdentity() {
        return mCellIdentityGsm;
    }

    /** @hide */
    public void setCellIdentity(CellIdentityGsm cid) {
        mCellIdentityGsm = cid;
    }

    /**
     * @return a {@link CellSignalStrengthGsm} instance.
     */
    @Override
    public @NonNull CellSignalStrengthGsm getCellSignalStrength() {
        return mCellSignalStrengthGsm;
    }

    /** @hide */
    @Override
    public CellInfo sanitizeLocationInfo() {
        CellInfoGsm result = new CellInfoGsm(this);
        result.mCellIdentityGsm = mCellIdentityGsm.sanitizeLocationInfo();
        return result;
    }

    /** @hide */
    public void setCellSignalStrength(CellSignalStrengthGsm css) {
        mCellSignalStrengthGsm = css;
    }

    /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return super.hashCode() + mCellIdentityGsm.hashCode() + mCellSignalStrengthGsm.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) {
            return false;
        }
        try {
            CellInfoGsm o = (CellInfoGsm) other;
            return mCellIdentityGsm.equals(o.mCellIdentityGsm)
                    && mCellSignalStrengthGsm.equals(o.mCellSignalStrengthGsm);
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("CellInfoGsm:{");
        sb.append(super.toString());
        sb.append(" ").append(mCellIdentityGsm);
        sb.append(" ").append(mCellSignalStrengthGsm);
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
        super.writeToParcel(dest, flags, TYPE_GSM);
        mCellIdentityGsm.writeToParcel(dest, flags);
        mCellSignalStrengthGsm.writeToParcel(dest, flags);
    }

    /**
     * Construct a CellInfoGsm object from the given parcel
     * where the token is already been processed.
     */
    private CellInfoGsm(Parcel in) {
        super(in);
        mCellIdentityGsm = CellIdentityGsm.CREATOR.createFromParcel(in);
        mCellSignalStrengthGsm = CellSignalStrengthGsm.CREATOR.createFromParcel(in);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellInfoGsm> CREATOR = new Creator<CellInfoGsm>() {
        @Override
        public CellInfoGsm createFromParcel(Parcel in) {
            in.readInt(); // Skip past token, we know what it is
            return createFromParcelBody(in);
        }

        @Override
        public CellInfoGsm[] newArray(int size) {
            return new CellInfoGsm[size];
        }
    };

    /** @hide */
    protected static CellInfoGsm createFromParcelBody(Parcel in) {
        return new CellInfoGsm(in);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
