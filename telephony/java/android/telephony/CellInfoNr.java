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

import dalvik.annotation.codegen.CovariantReturnType;

import java.util.Objects;

/**
 * A {@link CellInfo} representing an 5G NR cell that provides identity and measurement info.
 */
public final class CellInfoNr extends CellInfo {
    private static final String TAG = "CellInfoNr";

    private final CellIdentityNr mCellIdentity;
    private final CellSignalStrengthNr mCellSignalStrength;

    private CellInfoNr(Parcel in) {
        super(in);
        mCellIdentity = CellIdentityNr.CREATOR.createFromParcel(in);
        mCellSignalStrength = CellSignalStrengthNr.CREATOR.createFromParcel(in);
    }

    private CellInfoNr(CellInfoNr other, boolean sanitizeLocationInfo) {
        super(other);
        mCellIdentity = sanitizeLocationInfo ? other.mCellIdentity.sanitizeLocationInfo()
                : other.mCellIdentity;
        mCellSignalStrength = other.mCellSignalStrength;
    }

    /**
     * @return a {@link CellIdentityNr} instance.
     */
    @CovariantReturnType(returnType = CellIdentityNr.class, presentAfter = 29)
    @Override
    @NonNull
    public CellIdentity getCellIdentity() {
        return mCellIdentity;
    }

    /**
     * @return a {@link CellSignalStrengthNr} instance.
     */
    @CovariantReturnType(returnType = CellSignalStrengthNr.class, presentAfter = 29)
    @Override
    @NonNull
    public CellSignalStrength getCellSignalStrength() {
        return mCellSignalStrength;
    }

    /** @hide */
    @Override
    public CellInfo sanitizeLocationInfo() {
        return new CellInfoNr(this, true);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mCellIdentity, mCellSignalStrength);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CellInfoNr)) {
            return false;
        }

        CellInfoNr o = (CellInfoNr) other;
        return super.equals(o) && mCellIdentity.equals(o.mCellIdentity)
                && mCellSignalStrength.equals(o.mCellSignalStrength);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append(TAG + ":{")
                .append(" " + super.toString())
                .append(" " + mCellIdentity)
                .append(" " + mCellSignalStrength)
                .append(" }")
                .toString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags, TYPE_NR);
        mCellIdentity.writeToParcel(dest, flags);
        mCellSignalStrength.writeToParcel(dest, flags);
    }

    public static final @android.annotation.NonNull Creator<CellInfoNr> CREATOR = new Creator<CellInfoNr>() {
        @Override
        public CellInfoNr createFromParcel(Parcel in) {
            // Skip the type info.
            in.readInt();
            return new CellInfoNr(in);
        }

        @Override
        public CellInfoNr[] newArray(int size) {
            return new CellInfoNr[size];
        }
    };

    /** @hide */
    protected static CellInfoNr createFromParcelBody(Parcel in) {
        return new CellInfoNr(in);
    }
}
