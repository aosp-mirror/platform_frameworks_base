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
package android.hardware.camera2.impl;

import android.hardware.camera2.CameraMetadataInfo;
import android.hardware.camera2.impl.CameraMetadataNative;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class PhysicalCaptureResultInfo implements Parcelable {
    private String cameraId;
    private CameraMetadataInfo cameraMetadataInfo;

    public static final @android.annotation.NonNull Parcelable.Creator<PhysicalCaptureResultInfo> CREATOR =
            new Parcelable.Creator<PhysicalCaptureResultInfo>() {
        @Override
        public PhysicalCaptureResultInfo createFromParcel(Parcel in) {
            return new PhysicalCaptureResultInfo(in);
        }

        @Override
        public PhysicalCaptureResultInfo[] newArray(int size) {
            return new PhysicalCaptureResultInfo[size];
        }
    };

    private PhysicalCaptureResultInfo(Parcel in) {
        readFromParcel(in);
    }

    public PhysicalCaptureResultInfo(String cameraId, CameraMetadataNative cameraMetadata) {
        this.cameraId = cameraId;
        this.cameraMetadataInfo = CameraMetadataInfo.metadata(cameraMetadata);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(cameraId);
        cameraMetadataInfo.writeToParcel(dest, flags);
    }

    public void readFromParcel(Parcel in) {
        cameraId = in.readString();
        cameraMetadataInfo = CameraMetadataInfo.CREATOR.createFromParcel(in);
    }

    public String getCameraId() {
        return cameraId;
    }

    public CameraMetadataNative getCameraMetadata() {
        return cameraMetadataInfo.getMetadata();
    }

    public CameraMetadataInfo getCameraMetadataInfo() {
        return cameraMetadataInfo;
    }

}