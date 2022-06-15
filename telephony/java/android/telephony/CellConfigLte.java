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
 * The container of LTE cell related configs.
 * @hide
 */
public class CellConfigLte implements Parcelable {
    private final boolean mIsEndcAvailable;

    /** @hide */
    public CellConfigLte() {
        mIsEndcAvailable = false;
    }

    /** @hide */
    public CellConfigLte(android.hardware.radio.V1_4.CellConfigLte cellConfig) {
        mIsEndcAvailable = cellConfig.isEndcAvailable;
    }

    /** @hide */
    public CellConfigLte(boolean isEndcAvailable) {
        mIsEndcAvailable = isEndcAvailable;
    }

    /** @hide */
    public CellConfigLte(CellConfigLte config) {
        mIsEndcAvailable = config.mIsEndcAvailable;
    }

    /**
     * Indicates that if E-UTRA-NR Dual Connectivity (EN-DC) is supported by the LTE cell.
     *
     * Reference: 3GPP TS 36.331 v15.2.2 6.3.1 System information blocks.
     *
     * @return {@code true} if E-UTRA-NR Dual Connectivity (EN-DC) is supported by the LTE cell.
     *
     */
    boolean isEndcAvailable() {
        return mIsEndcAvailable;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsEndcAvailable);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CellConfigLte)) return false;

        CellConfigLte o = (CellConfigLte) other;
        return mIsEndcAvailable == o.mIsEndcAvailable;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsEndcAvailable);
    }

    @Override
    public String toString() {
        return new StringBuilder().append(this.getClass().getName())
                .append(" :{")
                .append(" isEndcAvailable = " + mIsEndcAvailable)
                .append(" }")
                .toString();
    }

    private CellConfigLte(Parcel in) {
        mIsEndcAvailable = in.readBoolean();
    }

    public static final @android.annotation.NonNull Creator<CellConfigLte> CREATOR = new Creator<CellConfigLte>() {
        @Override
        public CellConfigLte createFromParcel(Parcel in) {
            return new CellConfigLte(in);
        }

        @Override
        public CellConfigLte[] newArray(int size) {
            return new CellConfigLte[0];
        }
    };
}
