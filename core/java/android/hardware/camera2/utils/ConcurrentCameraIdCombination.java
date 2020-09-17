/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.hardware.camera2.utils;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashSet;
import java.util.Set;

/**
 * ConcurrentCameraIdCombination
 *
 * Includes a list of camera ids that may have sessions configured concurrently.
 * @hide
 */
public class ConcurrentCameraIdCombination implements Parcelable {

    private Set<String> mConcurrentCameraIds = new HashSet<>();

    public static final @NonNull
            Parcelable.Creator<ConcurrentCameraIdCombination> CREATOR =
            new Parcelable.Creator<ConcurrentCameraIdCombination>() {
        @Override
        public ConcurrentCameraIdCombination createFromParcel(Parcel in) {
            return new ConcurrentCameraIdCombination(in);
        }

        @Override
        public ConcurrentCameraIdCombination[] newArray(int size) {
            return new ConcurrentCameraIdCombination[size];
        }
    };

    private ConcurrentCameraIdCombination(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mConcurrentCameraIds.size());
        for (String cameraId : mConcurrentCameraIds) {
            dest.writeString(cameraId);
        }
    }

    /**
     * helper for CREATOR
     */
    public void readFromParcel(Parcel in) {
        mConcurrentCameraIds.clear();
        int cameraCombinationSize = in.readInt();
        if (cameraCombinationSize < 0) {
            throw new RuntimeException("cameraCombinationSize " + cameraCombinationSize
                    + " should not be negative");
        }
        for (int i = 0; i < cameraCombinationSize; i++) {
            String cameraId = in.readString();
            if (cameraId == null) {
                throw new RuntimeException("Failed to read camera id from Parcel");
            }
            mConcurrentCameraIds.add(cameraId);
        }
    }

    /**
     * Get this concurrent camera id combination.
     */
    public Set<String> getConcurrentCameraIdCombination() {
        return mConcurrentCameraIds;
    }
}
