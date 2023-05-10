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

package android.nfc;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;


/**
 * Contains information on all available Nfc
 * antennas on an Android device as well as information
 * on the device itself in relation positioning of the
 * antennas.
 */
public final class NfcAntennaInfo implements Parcelable {
    // Width of the device in millimeters.
    private final int mDeviceWidth;
    // Height of the device in millimeters.
    private final int mDeviceHeight;
    // Whether the device is foldable.
    private final boolean mDeviceFoldable;
    // All available Nfc Antennas on the device.
    private final List<AvailableNfcAntenna> mAvailableNfcAntennas;

    public NfcAntennaInfo(int deviceWidth, int deviceHeight, boolean deviceFoldable,
            @NonNull List<AvailableNfcAntenna> availableNfcAntennas) {
        this.mDeviceWidth = deviceWidth;
        this.mDeviceHeight = deviceHeight;
        this.mDeviceFoldable = deviceFoldable;
        this.mAvailableNfcAntennas = availableNfcAntennas;
    }

    /**
     * Width of the device in millimeters.
     */
    public int getDeviceWidth() {
        return mDeviceWidth;
    }

    /**
     * Height of the device in millimeters.
     */
    public int getDeviceHeight() {
        return mDeviceHeight;
    }

    /**
     * Whether the device is foldable. When the device is foldable,
     * the 0, 0 is considered to be bottom-left when the device is unfolded and
     * the screens are facing the user. For non-foldable devices 0, 0
     * is bottom-left when the user is facing the screen.
     */
    public boolean isDeviceFoldable() {
        return mDeviceFoldable;
    }

    /**
     * Get all NFC antennas that exist on the device.
     */
    @NonNull
    public List<AvailableNfcAntenna> getAvailableNfcAntennas() {
        return mAvailableNfcAntennas;
    }

    private NfcAntennaInfo(Parcel in) {
        this.mDeviceWidth = in.readInt();
        this.mDeviceHeight = in.readInt();
        this.mDeviceFoldable = in.readByte() != 0;
        this.mAvailableNfcAntennas = new ArrayList<>();
        in.readTypedList(this.mAvailableNfcAntennas,
                AvailableNfcAntenna.CREATOR);
    }

    public static final @NonNull Parcelable.Creator<NfcAntennaInfo> CREATOR =
            new Parcelable.Creator<NfcAntennaInfo>() {
        @Override
        public NfcAntennaInfo createFromParcel(Parcel in) {
            return new NfcAntennaInfo(in);
        }

        @Override
        public NfcAntennaInfo[] newArray(int size) {
            return new NfcAntennaInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDeviceWidth);
        dest.writeInt(mDeviceHeight);
        dest.writeByte((byte) (mDeviceFoldable ? 1 : 0));
        dest.writeTypedList(mAvailableNfcAntennas, 0);
    }
}
