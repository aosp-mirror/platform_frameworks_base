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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

/**
 * SatelliteDatagram is used to store data that is to be sent or received over satellite.
 * Data is stored in byte array format.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public final class SatelliteDatagram implements Parcelable {
    /**
     * Datagram to be sent or received over satellite.
     */
    @NonNull private byte[] mData;

    /**
     * @hide
     */
    public SatelliteDatagram(@NonNull byte[] data) {
        mData = data;
    }

    private SatelliteDatagram(Parcel in) {
        readFromParcel(in);
    }

    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public int describeContents() {
        return 0;
    }

    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeByteArray(mData);
    }

    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
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

    /**
     * Get satellite datagram.
     * @return byte array. The format of the byte array is determined by the corresponding
     * satellite provider. Client application should be aware of how to encode the datagram based
     * upon the satellite provider.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @NonNull public byte[] getSatelliteDatagram() {
        return mData;
    }

    private void readFromParcel(Parcel in) {
        mData = in.createByteArray();
    }
}
