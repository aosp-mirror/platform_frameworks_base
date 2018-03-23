/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the Quality of Service (QoS) settings for a Bluetooth HID Device application.
 *
 * <p>The BluetoothHidDevice framework will update the L2CAP QoS settings for the app during
 * registration.
 *
 * <p>{@see BluetoothHidDevice}
 */
public final class BluetoothHidDeviceAppQosSettings implements Parcelable {

    private final int mServiceType;
    private final int mTokenRate;
    private final int mTokenBucketSize;
    private final int mPeakBandwidth;
    private final int mLatency;
    private final int mDelayVariation;

    public static final int SERVICE_NO_TRAFFIC = 0x00;
    public static final int SERVICE_BEST_EFFORT = 0x01;
    public static final int SERVICE_GUARANTEED = 0x02;

    public static final int MAX = (int) 0xffffffff;

    /**
     * Create a BluetoothHidDeviceAppQosSettings object for the Bluetooth L2CAP channel. The QoS
     * Settings is optional. Please refer to Bluetooth HID Specfication v1.1.1 Section 5.2 and
     * Appendix D for parameters.
     *
     * @param serviceType L2CAP service type, default = SERVICE_BEST_EFFORT
     * @param tokenRate L2CAP token rate, default = 0
     * @param tokenBucketSize L2CAP token bucket size, default = 0
     * @param peakBandwidth L2CAP peak bandwidth, default = 0
     * @param latency L2CAP latency, default = MAX
     * @param delayVariation L2CAP delay variation, default = MAX
     */
    public BluetoothHidDeviceAppQosSettings(
            int serviceType,
            int tokenRate,
            int tokenBucketSize,
            int peakBandwidth,
            int latency,
            int delayVariation) {
        mServiceType = serviceType;
        mTokenRate = tokenRate;
        mTokenBucketSize = tokenBucketSize;
        mPeakBandwidth = peakBandwidth;
        mLatency = latency;
        mDelayVariation = delayVariation;
    }

    public int getServiceType() {
        return mServiceType;
    }

    public int getTokenRate() {
        return mTokenRate;
    }

    public int getTokenBucketSize() {
        return mTokenBucketSize;
    }

    public int getPeakBandwidth() {
        return mPeakBandwidth;
    }

    public int getLatency() {
        return mLatency;
    }

    public int getDelayVariation() {
        return mDelayVariation;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothHidDeviceAppQosSettings> CREATOR =
            new Parcelable.Creator<BluetoothHidDeviceAppQosSettings>() {

                @Override
                public BluetoothHidDeviceAppQosSettings createFromParcel(Parcel in) {

                    return new BluetoothHidDeviceAppQosSettings(
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt());
                }

                @Override
                public BluetoothHidDeviceAppQosSettings[] newArray(int size) {
                    return new BluetoothHidDeviceAppQosSettings[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mServiceType);
        out.writeInt(mTokenRate);
        out.writeInt(mTokenBucketSize);
        out.writeInt(mPeakBandwidth);
        out.writeInt(mLatency);
        out.writeInt(mDelayVariation);
    }
}
