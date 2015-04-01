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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container for fingerprint metadata.
 * @hide
 */
public final class Fingerprint implements Parcelable {
    private CharSequence mName;
    private int mGroupId;
    private int mFingerId;
    private long mDeviceId; // physical device this is associated with

    public Fingerprint(CharSequence name, int groupId, int fingerId, long deviceId) {
        mName = name;
        mGroupId = groupId;
        mFingerId = fingerId;
        mDeviceId = deviceId;
    }

    private Fingerprint(Parcel in) {
        mName = in.readString();
        mGroupId = in.readInt();
        mFingerId = in.readInt();
        mDeviceId = in.readLong();
    }

    /**
     * Gets the human-readable name for the given fingerprint.
     * @return name given to finger
     */
    public CharSequence getName() { return mName; }

    /**
     * Gets the device-specific finger id.  Used by Settings to map a name to a specific
     * fingerprint template.
     * @return device-specific id for this finger
     * @hide
     */
    public int getFingerId() { return mFingerId; }

    /**
     * Gets the group id specified when the fingerprint was enrolled.
     * @return group id for the set of fingerprints this one belongs to.
     * @hide
     */
    public int getGroupId() { return mGroupId; }

    /**
     * Device this fingerprint belongs to.
     * @hide
     */
    public long getDeviceId() { return mDeviceId; }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mName.toString());
        out.writeInt(mGroupId);
        out.writeInt(mFingerId);
        out.writeLong(mDeviceId);
    }

    public static final Parcelable.Creator<Fingerprint> CREATOR
            = new Parcelable.Creator<Fingerprint>() {
        public Fingerprint createFromParcel(Parcel in) {
            return new Fingerprint(in);
        }

        public Fingerprint[] newArray(int size) {
            return new Fingerprint[size];
        }
    };
};