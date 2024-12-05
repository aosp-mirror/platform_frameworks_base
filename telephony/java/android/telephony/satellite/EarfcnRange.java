/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * EARFCN (E-UTRA Absolute Radio Frequency Channel Number):  A number that identifies a
 * specific frequency channel in LTE/5G NR, used to define the carrier frequency.
 * The range can be [0 ~ 65535] according to the 3GPP TS 36.101
 *
 * In satellite communication:
 * - Efficient frequency allocation across a wide coverage area.
 * - Handles Doppler shift due to satellite movement.
 * - Manages interference with terrestrial networks.
 *
 * See 3GPP TS 36.101 and 38.101-1 for details.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class EarfcnRange implements Parcelable {

    /**
     * The start frequency of the earfcn range and is inclusive in the range
     */
    private int mStartEarfcn;

    /**
     * The end frequency of the earfcn range and is inclusive in the range.
     */
    private int mEndEarfcn;

    private EarfcnRange(@NonNull Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStartEarfcn);
        dest.writeInt(mEndEarfcn);
    }

    private void readFromParcel(Parcel in) {
        mStartEarfcn = in.readInt();
        mEndEarfcn = in.readInt();
    }

    /**
     * Constructor for the EarfcnRange class.
     * The range can be [0 ~ 65535] according to the 3GPP TS 36.101
     *
     * @param startEarfcn The starting earfcn value.
     * @param endEarfcn   The ending earfcn value.
     * @hide
     */
    public EarfcnRange(@IntRange(from = 0, to = 65535) int startEarfcn,
            @IntRange(from = 0, to = 65535) int endEarfcn) {
        mStartEarfcn = startEarfcn;
        mEndEarfcn = endEarfcn;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @NonNull
    public String toString() {
        return "startEarfcn: " + mStartEarfcn + ", " + "endEarfcn: " + mEndEarfcn;
    }

    @NonNull
    public static final Creator<EarfcnRange> CREATOR = new Creator<EarfcnRange>() {
        @Override
        public EarfcnRange createFromParcel(Parcel in) {
            return new EarfcnRange(in);
        }

        @Override
        public EarfcnRange[] newArray(int size) {
            return new EarfcnRange[size];
        }
    };

    /**
     * Returns the starting earfcn value for this range.
     * It can be [0 ~ 65535] according to the 3GPP TS 36.101
     *
     * @return The starting earfcn.
     */
    public @IntRange(from = 0, to = 65535) int getStartEarfcn() {
        return mStartEarfcn;
    }

    /**
     * Returns the ending earfcn value for this range.
     * It can be [0 ~ 65535] according to the 3GPP TS 36.101
     *
     * @return The ending earfcn.
     */
    public @IntRange(from = 0, to = 65535) int getEndEarfcn() {
        return mEndEarfcn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EarfcnRange that)) return false;

        return (that.mStartEarfcn == mStartEarfcn) && (that.mEndEarfcn == mEndEarfcn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStartEarfcn, mEndEarfcn);
    }
}
