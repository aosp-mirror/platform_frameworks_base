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

import static android.app.time.DetectorStatusTypes.detectionAlgorithmStatusToString;
import static android.app.time.DetectorStatusTypes.requireValidDetectionAlgorithmStatus;

import android.annotation.NonNull;
import android.app.time.DetectorStatusTypes.DetectionAlgorithmStatus;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Information about the status of the telephony-based time zone detection algorithm.
 *
 * @hide
 */
public final class TelephonyTimeZoneAlgorithmStatus implements Parcelable {

    private final @DetectionAlgorithmStatus int mAlgorithmStatus;

    public TelephonyTimeZoneAlgorithmStatus(@DetectionAlgorithmStatus int algorithmStatus) {
        mAlgorithmStatus = requireValidDetectionAlgorithmStatus(algorithmStatus);
    }

    /**
     * Returns the status of the detection algorithm.
     */
    public @DetectionAlgorithmStatus int getAlgorithmStatus() {
        return mAlgorithmStatus;
    }

    @Override
    public String toString() {
        return "TelephonyTimeZoneAlgorithmStatus{"
                + "mAlgorithmStatus=" + detectionAlgorithmStatusToString(mAlgorithmStatus)
                + '}';
    }

    @NonNull
    public static final Creator<TelephonyTimeZoneAlgorithmStatus> CREATOR = new Creator<>() {
        @Override
        public TelephonyTimeZoneAlgorithmStatus createFromParcel(Parcel in) {
            @DetectionAlgorithmStatus int algorithmStatus = in.readInt();
            return new TelephonyTimeZoneAlgorithmStatus(algorithmStatus);
        }

        @Override
        public TelephonyTimeZoneAlgorithmStatus[] newArray(int size) {
            return new TelephonyTimeZoneAlgorithmStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mAlgorithmStatus);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TelephonyTimeZoneAlgorithmStatus that = (TelephonyTimeZoneAlgorithmStatus) o;
        return mAlgorithmStatus == that.mAlgorithmStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAlgorithmStatus);
    }
}
