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

package android.nfc;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Contains information of the nfc wireless charging listener device information.
 */
@FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
public final class WlcLDeviceInfo implements Parcelable {
    public static final int DISCONNECTED = 1;

    public static final int CONNECTED_CHARGING = 2;

    public static final int CONNECTED_DISCHARGING = 3;

    private double mProductId;
    private double mTemperature;
    private double mBatteryLevel;
    private int mState;

    public WlcLDeviceInfo(double productId, double temperature, double batteryLevel, int state) {
        this.mProductId = productId;
        this.mTemperature = temperature;
        this.mBatteryLevel = batteryLevel;
        this.mState = state;
    }

    /**
     * ProductId of the WLC listener device.
     */
    public double getProductId() {
        return mProductId;
    }

    /**
     * Temperature of the WLC listener device.
     */
    public double getTemperature() {
        return mTemperature;
    }

    /**
     * BatteryLevel of the WLC listener device.
     */
    public double getBatteryLevel() {
        return mBatteryLevel;
    }

    /**
     * State of the WLC listener device.
     */
    public int getState() {
        return mState;
    }

    private WlcLDeviceInfo(Parcel in) {
        this.mProductId = in.readDouble();
        this.mTemperature = in.readDouble();
        this.mBatteryLevel = in.readDouble();
        this.mState = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<WlcLDeviceInfo> CREATOR =
            new Parcelable.Creator<WlcLDeviceInfo>() {
                @Override
                public WlcLDeviceInfo createFromParcel(Parcel in) {
                    return new WlcLDeviceInfo(in);
                }

                @Override
                public WlcLDeviceInfo[] newArray(int size) {
                    return new WlcLDeviceInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mProductId);
        dest.writeDouble(mTemperature);
        dest.writeDouble(mBatteryLevel);
        dest.writeDouble(mState);
    }
}
