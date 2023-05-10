/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Antenna Position received from satellite modem which gives information about antenna
 * direction to be used with satellite communication and suggested device hold positions.
 * @hide
 */
public final class AntennaPosition implements Parcelable {
    /** Antenna direction used for satellite communication. */
    @NonNull AntennaDirection mAntennaDirection;

    /** Enum corresponding to device hold position to be used by the end user. */
    @SatelliteManager.DeviceHoldPosition int mSuggestedHoldPosition;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public AntennaPosition(@NonNull AntennaDirection antennaDirection, int suggestedHoldPosition) {
        mAntennaDirection = antennaDirection;
        mSuggestedHoldPosition = suggestedHoldPosition;
    }

    private AntennaPosition(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mAntennaDirection, flags);
        out.writeInt(mSuggestedHoldPosition);
    }

    @NonNull
    public static final Creator<AntennaPosition> CREATOR =
            new Creator<>() {
                @Override
                public AntennaPosition createFromParcel(Parcel in) {
                    return new AntennaPosition(in);
                }

                @Override
                public AntennaPosition[] newArray(int size) {
                    return new AntennaPosition[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AntennaPosition that = (AntennaPosition) o;
        return Objects.equals(mAntennaDirection, that.mAntennaDirection)
                && mSuggestedHoldPosition == that.mSuggestedHoldPosition;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAntennaDirection, mSuggestedHoldPosition);
    }

    @Override
    @NonNull public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("antennaDirection:");
        sb.append(mAntennaDirection);
        sb.append(",");

        sb.append("suggestedHoldPosition:");
        sb.append(mSuggestedHoldPosition);
        return sb.toString();
    }

    @NonNull
    public AntennaDirection getAntennaDirection() {
        return mAntennaDirection;
    }

    @SatelliteManager.DeviceHoldPosition
    public int getSuggestedHoldPosition() {
        return mSuggestedHoldPosition;
    }

    private void readFromParcel(Parcel in) {
        mAntennaDirection = in.readParcelable(AntennaDirection.class.getClassLoader(),
                AntennaDirection.class);
        mSuggestedHoldPosition = in.readInt();
    }
}
