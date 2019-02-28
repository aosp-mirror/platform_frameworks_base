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

package android.hardware.biometrics;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public enum BiometricSourceType implements Parcelable {
    FINGERPRINT,
    FACE,
    IRIS;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name());
    }

    public static final @android.annotation.NonNull Creator<BiometricSourceType> CREATOR = new Creator<BiometricSourceType>() {
        @Override
        public BiometricSourceType createFromParcel(final Parcel source) {
            return BiometricSourceType.valueOf(source.readString());
        }

        @Override
        public BiometricSourceType[] newArray(final int size) {
            return new BiometricSourceType[size];
        }
    };
}
