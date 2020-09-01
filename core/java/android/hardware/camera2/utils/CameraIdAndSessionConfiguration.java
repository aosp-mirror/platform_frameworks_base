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
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * CameraIdAndSessionConfiguration
 *
 * Includes the pair of a cameraId and its corresponding SessionConfiguration, to be used with
 * ICameraService.isConcurrentSessionConfigurationSupported.
 * @hide
 */
public class CameraIdAndSessionConfiguration implements Parcelable {

    private String mCameraId;
    private SessionConfiguration mSessionConfiguration;

    public CameraIdAndSessionConfiguration(@NonNull String cameraId,
            @NonNull SessionConfiguration sessionConfiguration) {
        mCameraId = cameraId;
        mSessionConfiguration = sessionConfiguration;
    }

    public static final @NonNull
            Parcelable.Creator<CameraIdAndSessionConfiguration> CREATOR =
            new Parcelable.Creator<CameraIdAndSessionConfiguration>() {
        @Override
        public CameraIdAndSessionConfiguration createFromParcel(Parcel in) {
            return new CameraIdAndSessionConfiguration(in);
        }

        @Override
        public CameraIdAndSessionConfiguration[] newArray(int size) {
            return new CameraIdAndSessionConfiguration[size];
        }
    };

    private CameraIdAndSessionConfiguration(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mCameraId);
        mSessionConfiguration.writeToParcel(dest, flags);
    }

    /**
     * helper for CREATOR
     */
    public void readFromParcel(Parcel in) {
        mCameraId = in.readString();
        mSessionConfiguration = SessionConfiguration.CREATOR.createFromParcel(in);
    }

    public @NonNull String getCameraId() {
        return mCameraId;
    }

    public @NonNull SessionConfiguration getSessionConfiguration() {
        return mSessionConfiguration;
    }
}
