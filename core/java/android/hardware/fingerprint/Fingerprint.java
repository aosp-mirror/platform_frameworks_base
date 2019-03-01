/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.hardware.fingerprint;

import android.hardware.biometrics.BiometricAuthenticator;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container for fingerprint metadata.
 * @hide
 */
public final class Fingerprint extends BiometricAuthenticator.Identifier {
    private int mGroupId;

    public Fingerprint(CharSequence name, int groupId, int fingerId, long deviceId) {
        super(name, fingerId, deviceId);
        mGroupId = groupId;
    }

    private Fingerprint(Parcel in) {
        super(in.readString(), in.readInt(), in.readLong());
        mGroupId = in.readInt();
    }

    /**
     * Gets the group id specified when the fingerprint was enrolled.
     * @return group id for the set of fingerprints this one belongs to.
     */
    public int getGroupId() {
        return mGroupId;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(getName().toString());
        out.writeInt(getBiometricId());
        out.writeLong(getDeviceId());
        out.writeInt(mGroupId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Fingerprint> CREATOR
            = new Parcelable.Creator<Fingerprint>() {
        public Fingerprint createFromParcel(Parcel in) {
            return new Fingerprint(in);
        }

        public Fingerprint[] newArray(int size) {
            return new Fingerprint[size];
        }
    };
};