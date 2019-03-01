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

package android.hardware.face;

import android.hardware.biometrics.BiometricAuthenticator;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container for face metadata.
 *
 * @hide
 */
public final class Face extends BiometricAuthenticator.Identifier {

    public Face(CharSequence name, int faceId, long deviceId) {
        super(name, faceId, deviceId);
    }

    private Face(Parcel in) {
        super(in.readString(), in.readInt(), in.readLong());
    }

    /**
     * Describes the contents.
     * @return
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Writes to a parcel.
     * @param out
     * @param flags Additional flags about how the object should be written.
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(getName().toString());
        out.writeInt(getBiometricId());
        out.writeLong(getDeviceId());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Face> CREATOR = new Parcelable.Creator<Face>() {
            public Face createFromParcel(Parcel in) {
                return new Face(in);
            }

            public Face[] newArray(int size) {
                return new Face[size];
            }
    };
}
