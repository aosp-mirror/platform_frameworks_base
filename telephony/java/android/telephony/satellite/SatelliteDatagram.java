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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public final class SatelliteDatagram implements Parcelable {
    /**
     * Datagram to be sent or received over satellite.
     */
    @NonNull private byte[] mData;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public SatelliteDatagram(@NonNull byte[] data) {
        mData = data;
    }

    private SatelliteDatagram(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeByteArray(mData);
    }

    @NonNull public static final Creator<SatelliteDatagram> CREATOR =
            new Creator<>() {
                @Override
                public SatelliteDatagram createFromParcel(Parcel in) {
                    return new SatelliteDatagram(in);
                }

                @Override
                public SatelliteDatagram[] newArray(int size) {
                    return new SatelliteDatagram[size];
                }
            };

    @NonNull public byte[] getSatelliteDatagram() {
        return mData;
    }

    private void readFromParcel(Parcel in) {
        mData = in.createByteArray();
    }
}
