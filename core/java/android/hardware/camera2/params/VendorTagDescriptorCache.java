/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.camera2.params;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A class for describing the vendor tag cache declared by a camera HAL module.
 * Generally only used by the native side of
 * android.hardware.camera2.impl.CameraMetadataNative
 *
 * @hide
 */
public final class VendorTagDescriptorCache implements Parcelable {

    private VendorTagDescriptorCache(Parcel source) {
    }

    public static final @android.annotation.NonNull Parcelable.Creator<VendorTagDescriptorCache> CREATOR =
            new Parcelable.Creator<VendorTagDescriptorCache>() {
        @Override
        public VendorTagDescriptorCache createFromParcel(Parcel source) {
            return new VendorTagDescriptorCache(source);
        }

        @Override
        public VendorTagDescriptorCache[] newArray(int size) {
            return new VendorTagDescriptorCache[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
    }

    private static final String TAG = "VendorTagDescriptorCache";
}
