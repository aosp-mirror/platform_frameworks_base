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
    private CharSequence mName;
    private int mFaceId;
    private long mDeviceId; // physical device this face is associated with

    public Face(CharSequence name, int faceId, long deviceId) {
        mName = name;
        mFaceId = faceId;
        mDeviceId = deviceId;
    }

    private Face(Parcel in) {
        mName = in.readString();
        mFaceId = in.readInt();
        mDeviceId = in.readLong();
    }

    /**
     * Gets the human-readable name for the given fingerprint.
     * @return name given to finger
     */
    public CharSequence getName() {
        return mName;
    }

    /**
     * Gets the device-specific finger id.  Used by Settings to map a name to a specific
     * fingerprint template.
     * @return device-specific id for this finger
     * @hide
     */
    public int getFaceId() {
        return mFaceId;
    }

    /**
     * Device this face belongs to.
     *
     * @hide
     */
    public long getDeviceId() {
        return mDeviceId;
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
        out.writeString(mName.toString());
        out.writeInt(mFaceId);
        out.writeLong(mDeviceId);
    }

    public static final Parcelable.Creator<Face> CREATOR = new Parcelable.Creator<Face>() {
            public Face createFromParcel(Parcel in) {
                return new Face(in);
            }

            public Face[] newArray(int size) {
                return new Face[size];
            }
    };
}
