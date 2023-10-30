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

package android.companion.virtual.camera;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data structure used to store camera metadata compatible with VirtualCamera.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
public final class VirtualCameraMetadata implements Parcelable {

    /** @hide */
    public VirtualCameraMetadata(@NonNull Parcel in) {}

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Creator<VirtualCameraMetadata> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public VirtualCameraMetadata createFromParcel(Parcel in) {
                    return new VirtualCameraMetadata(in);
                }

                @Override
                @NonNull
                public VirtualCameraMetadata[] newArray(int size) {
                    return new VirtualCameraMetadata[size];
                }
            };
}
