/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.hardware;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Status information about a camera.
 *
 * Contains the name of the camera device, and its current status, one of the
 * ICameraServiceListener.STATUS_ values.
 *
 * @hide
 */
public class CameraStatus implements Parcelable {
    public String cameraId;
    public int status;
    public String[] unavailablePhysicalCameras;
    public String clientPackage;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(cameraId);
        out.writeInt(status);
        out.writeStringArray(unavailablePhysicalCameras);
        out.writeString(clientPackage);
    }

    public void readFromParcel(Parcel in) {
        cameraId = in.readString();
        status = in.readInt();
        unavailablePhysicalCameras = in.readStringArray();
        clientPackage = in.readString();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CameraStatus> CREATOR =
            new Parcelable.Creator<CameraStatus>() {
        @Override
        public CameraStatus createFromParcel(Parcel in) {
            CameraStatus status = new CameraStatus();
            status.readFromParcel(in);

            return status;
        }

        @Override
        public CameraStatus[] newArray(int size) {
            return new CameraStatus[size];
        }
    };
}
