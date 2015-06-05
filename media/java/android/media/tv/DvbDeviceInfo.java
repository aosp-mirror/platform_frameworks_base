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

package android.media.tv;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Simple container for information about DVB device.
 * Not for third-party developers.
 *
 * @hide
 */
public final class DvbDeviceInfo implements Parcelable {
    static final String TAG = "DvbDeviceInfo";

    public static final Parcelable.Creator<DvbDeviceInfo> CREATOR =
            new Parcelable.Creator<DvbDeviceInfo>() {
                @Override
                public DvbDeviceInfo createFromParcel(Parcel source) {
                    try {
                        return new DvbDeviceInfo(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating DvbDeviceInfo from parcel", e);
                        return null;
                    }
                }

                @Override
                public DvbDeviceInfo[] newArray(int size) {
                    return new DvbDeviceInfo[size];
                }
            };

    private final int mAdapterId;
    private final int mDeviceId;

    private DvbDeviceInfo(Parcel source) {
        mAdapterId = source.readInt();
        mDeviceId = source.readInt();
    }

    /**
     * Constructs a new {@link DvbDeviceInfo} with the given adapter ID and device ID.
     */
    public DvbDeviceInfo(int adapterId, int deviceId) {
        mAdapterId = adapterId;
        mDeviceId = deviceId;
    }

    /**
     * Returns the adapter ID of DVB device, in terms of enumerating the DVB device adapters
     * installed in the system. The adapter ID counts from zero.
     */
    public int getAdapterId() {
        return mAdapterId;
    }

    /**
     * Returns the device ID of DVB device, in terms of enumerating the DVB devices attached to
     * the same device adapter. The device ID counts from zero.
     */
    public int getDeviceId() {
        return mDeviceId;
    }

    // Parcelable
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAdapterId);
        dest.writeInt(mDeviceId);
    }
}
