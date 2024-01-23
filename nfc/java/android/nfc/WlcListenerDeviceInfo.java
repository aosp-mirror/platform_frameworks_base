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
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains information of the nfc wireless charging listener device information.
 */
@FlaggedApi(Flags.FLAG_ENABLE_NFC_CHARGING)
public final class WlcListenerDeviceInfo implements Parcelable {
    /**
     * Device is currently not connected with any WlcListenerDevice.
     */
    public static final int STATE_DISCONNECTED = 1;

    /**
     * Device is currently connected with a WlcListenerDevice and is charging it.
     */
    public static final int STATE_CONNECTED_CHARGING = 2;

    /**
     * Device is currently connected with a WlcListenerDevice without charging it.
     */
    public static final int STATE_CONNECTED_DISCHARGING = 3;

    /**
     * Possible states from {@link #getState}.
     * @hide
     */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_DISCONNECTED,
            STATE_CONNECTED_CHARGING,
            STATE_CONNECTED_DISCHARGING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WlcListenerState{}

    private int mProductId;
    private double mTemperature;
    private double mBatteryLevel;
    private int mState;

     /**
     * Create a new object containing wlc listener information.
     *
     * @param productId code for the device vendor
     * @param temperature current temperature
     * @param batteryLevel current battery level
     * @param state current state
     */
    public WlcListenerDeviceInfo(int productId, double temperature, double batteryLevel,
            @WlcListenerState int state) {
        this.mProductId = productId;
        this.mTemperature = temperature;
        this.mBatteryLevel = batteryLevel;
        this.mState = state;
    }

    /**
     * ProductId of the WLC listener device.
     * @return integer that is converted from USI Stylus VendorID[11:0].
     */
    public int getProductId() {
        return mProductId;
    }

    /**
     * Temperature of the WLC listener device.
     * @return the value represents the temperature in °C.
     */
    public double getTemperature() {
        return mTemperature;
    }

    /**
     * BatteryLevel of the WLC listener device.
     * @return battery level in percentage [0-100]
     */
    public @FloatRange(from = 0.0, to = 100.0) double getBatteryLevel() {
        return mBatteryLevel;
    }

    /**
     * State of the WLC listener device.
     */
    public @WlcListenerState int getState() {
        return mState;
    }

    private WlcListenerDeviceInfo(Parcel in) {
        this.mProductId = in.readInt();
        this.mTemperature = in.readDouble();
        this.mBatteryLevel = in.readDouble();
        this.mState = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<WlcListenerDeviceInfo> CREATOR =
            new Parcelable.Creator<WlcListenerDeviceInfo>() {
                @Override
                public WlcListenerDeviceInfo createFromParcel(Parcel in) {
                    return new WlcListenerDeviceInfo(in);
                }

                @Override
                public WlcListenerDeviceInfo[] newArray(int size) {
                    return new WlcListenerDeviceInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mProductId);
        dest.writeDouble(mTemperature);
        dest.writeDouble(mBatteryLevel);
        dest.writeInt(mState);
    }
}
