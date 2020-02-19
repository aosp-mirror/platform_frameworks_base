/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.debug;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.Preconditions;

/**
 * Contains information about the client in an ADB connection.
 * @hide
 */
@Immutable
public class PairDevice implements Parcelable {
    /**
     * The human-readable name of the device.
     */
    @NonNull private final String mName;

    /**
     * The device's guid.
     */
    @NonNull private final String mGuid;

    /**
     * Indicates whether the device is currently connected to adbd.
     */
    private final boolean mConnected;

    public PairDevice(@NonNull String name, @NonNull String guid, boolean connected) {
        Preconditions.checkStringNotEmpty(name);
        Preconditions.checkStringNotEmpty(guid);
        mName = name;
        mGuid = guid;
        mConnected = connected;
    }

    /**
     * @return the device name.
     */
    @NonNull
    public String getDeviceName() {
        return mName;
    }

    /**
     * @return the device GUID.
     */
    @NonNull
    public String getGuid() {
        return mGuid;
    }

    /**
     * @return the adb connection state of the device.
     */
    public boolean isConnected() {
        return mConnected;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeString(mGuid);
        dest.writeBoolean(mConnected);
    }

    /**
     * @return Human-readable info about the object.
     */
    @Override
    public String toString() {
        return "\n" + mName + "\n" + mGuid + "\n" + mConnected;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<PairDevice> CREATOR =
            new Creator<PairDevice>() {
                @Override
                public PairDevice createFromParcel(Parcel source) {
                    return new PairDevice(source.readString(), source.readString(),
                            source.readBoolean());
                }

                @Override
                public PairDevice[] newArray(int size) {
                    return new PairDevice[size];
                }
            };
}
