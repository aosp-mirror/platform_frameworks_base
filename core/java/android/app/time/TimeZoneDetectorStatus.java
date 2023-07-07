/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.time;

import static android.app.time.DetectorStatusTypes.DetectorStatus;
import static android.app.time.DetectorStatusTypes.requireValidDetectorStatus;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Information about the status of the automatic time zone detector. Used by SettingsUI to display
 * status information to the user.
 *
 * @hide
 */
public final class TimeZoneDetectorStatus implements Parcelable {

    private final @DetectorStatus int mDetectorStatus;
    @NonNull private final TelephonyTimeZoneAlgorithmStatus mTelephonyTimeZoneAlgorithmStatus;
    @NonNull private final LocationTimeZoneAlgorithmStatus mLocationTimeZoneAlgorithmStatus;

    public TimeZoneDetectorStatus(
            @DetectorStatus int detectorStatus,
            @NonNull TelephonyTimeZoneAlgorithmStatus telephonyTimeZoneAlgorithmStatus,
            @NonNull LocationTimeZoneAlgorithmStatus locationTimeZoneAlgorithmStatus) {
        mDetectorStatus = requireValidDetectorStatus(detectorStatus);
        mTelephonyTimeZoneAlgorithmStatus =
                Objects.requireNonNull(telephonyTimeZoneAlgorithmStatus);
        mLocationTimeZoneAlgorithmStatus = Objects.requireNonNull(locationTimeZoneAlgorithmStatus);
    }

    public @DetectorStatus int getDetectorStatus() {
        return mDetectorStatus;
    }

    @NonNull
    public TelephonyTimeZoneAlgorithmStatus getTelephonyTimeZoneAlgorithmStatus() {
        return mTelephonyTimeZoneAlgorithmStatus;
    }

    @NonNull
    public LocationTimeZoneAlgorithmStatus getLocationTimeZoneAlgorithmStatus() {
        return mLocationTimeZoneAlgorithmStatus;
    }

    @Override
    public String toString() {
        return "TimeZoneDetectorStatus{"
                + "mDetectorStatus=" + DetectorStatusTypes.detectorStatusToString(mDetectorStatus)
                + ", mTelephonyTimeZoneAlgorithmStatus=" + mTelephonyTimeZoneAlgorithmStatus
                + ", mLocationTimeZoneAlgorithmStatus=" + mLocationTimeZoneAlgorithmStatus
                + '}';
    }

    public static final @NonNull Creator<TimeZoneDetectorStatus> CREATOR = new Creator<>() {
        @Override
        public TimeZoneDetectorStatus createFromParcel(Parcel in) {
            @DetectorStatus int detectorStatus = in.readInt();
            TelephonyTimeZoneAlgorithmStatus telephonyTimeZoneAlgorithmStatus =
                    in.readParcelable(getClass().getClassLoader(),
                            TelephonyTimeZoneAlgorithmStatus.class);
            LocationTimeZoneAlgorithmStatus locationTimeZoneAlgorithmStatus =
                    in.readParcelable(getClass().getClassLoader(),
                            LocationTimeZoneAlgorithmStatus.class);
            return new TimeZoneDetectorStatus(detectorStatus,
                    telephonyTimeZoneAlgorithmStatus, locationTimeZoneAlgorithmStatus);
        }

        @Override
        public TimeZoneDetectorStatus[] newArray(int size) {
            return new TimeZoneDetectorStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mDetectorStatus);
        parcel.writeParcelable(mTelephonyTimeZoneAlgorithmStatus, flags);
        parcel.writeParcelable(mLocationTimeZoneAlgorithmStatus, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneDetectorStatus that = (TimeZoneDetectorStatus) o;
        return mDetectorStatus == that.mDetectorStatus
                && mTelephonyTimeZoneAlgorithmStatus.equals(that.mTelephonyTimeZoneAlgorithmStatus)
                && mLocationTimeZoneAlgorithmStatus.equals(that.mLocationTimeZoneAlgorithmStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDetectorStatus, mTelephonyTimeZoneAlgorithmStatus,
                mLocationTimeZoneAlgorithmStatus);
    }
}
