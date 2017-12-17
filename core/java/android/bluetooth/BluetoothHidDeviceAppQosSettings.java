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

    public final int serviceType;
    public final int tokenRate;
    public final int tokenBucketSize;
    public final int peakBandwidth;
    public final int latency;
    public final int delayVariation;

    public static final int SERVICE_NO_TRAFFIC = 0x00;
    public static final int SERVICE_BEST_EFFORT = 0x01;
    public static final int SERVICE_GUARANTEED = 0x02;

    public static final int MAX = (int) 0xffffffff;

    /**
     * Create a BluetoothHidDeviceAppQosSettings object for the Bluetooth L2CAP channel. The QoS
     * Settings is optional. Recommended to use BluetoothHidDeviceAppQosSettings.Builder.
     * Please refer to Bluetooth HID Specfication v1.1.1 Section 5.2 and Appendix D for parameters.
     *
     * @param serviceType L2CAP service type
     * @param tokenRate L2CAP token rate
     * @param tokenBucketSize L2CAP token bucket size
     * @param peakBandwidth L2CAP peak bandwidth
     * @param latency L2CAP latency
     * @param delayVariation L2CAP delay variation
     */
    public BluetoothHidDeviceAppQosSettings(int serviceType, int tokenRate, int tokenBucketSize,
            int peakBandwidth, int latency, int delayVariation) {
        this.serviceType = serviceType;
        this.tokenRate = tokenRate;
        this.tokenBucketSize = tokenBucketSize;
        this.peakBandwidth = peakBandwidth;
        this.latency = latency;
        this.delayVariation = delayVariation;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothHidDeviceAppQosSettings) {
            BluetoothHidDeviceAppQosSettings qos = (BluetoothHidDeviceAppQosSettings) o;
            return this.serviceType == qos.serviceType
                    && this.tokenRate == qos.tokenRate
                    && this.tokenBucketSize == qos.tokenBucketSize
                    && this.peakBandwidth == qos.peakBandwidth
                    && this.latency == qos.latency
                    && this.delayVariation == qos.delayVariation;
        }
        return false;
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
        out.writeInt(serviceType);
        out.writeInt(tokenRate);
        out.writeInt(tokenBucketSize);
        out.writeInt(peakBandwidth);
        out.writeInt(latency);
        out.writeInt(delayVariation);
    }

    /** @return an int array representation of this instance */
    public int[] toArray() {
        return new int[] {
            serviceType, tokenRate, tokenBucketSize, peakBandwidth, latency, delayVariation
        };
    }

    /** A helper to build the BluetoothHidDeviceAppQosSettings object. */
    public static class Builder {
        // Optional parameters - initialized to default values
        private int mServiceType = SERVICE_BEST_EFFORT;
        private int mTokenRate = 0;
        private int mTokenBucketSize = 0;
        private int mPeakBandwidth = 0;
        private int mLatency = MAX;
        private int mDelayVariation = MAX;

        /**
         * Set the service type.
         *
         * @param val service type. Should be one of {SERVICE_NO_TRAFFIC, SERVICE_BEST_EFFORT,
         *     SERVICE_GUARANTEED}, with SERVICE_BEST_EFFORT being the default one.
         * @return BluetoothHidDeviceAppQosSettings Builder with specified service type.
         */
        public Builder serviceType(int val) {
            mServiceType = val;
            return this;
        }
        /**
         * Set the token rate.
         *
         * @param val token rate
         * @return BluetoothHidDeviceAppQosSettings Builder with specified token rate.
         */
        public Builder tokenRate(int val) {
            mTokenRate = val;
            return this;
        }

        /**
         * Set the bucket size.
         *
         * @param val bucket size
         * @return BluetoothHidDeviceAppQosSettings Builder with specified bucket size.
         */
        public Builder tokenBucketSize(int val) {
            mTokenBucketSize = val;
            return this;
        }

        /**
         * Set the peak bandwidth.
         *
         * @param val peak bandwidth
         * @return BluetoothHidDeviceAppQosSettings Builder with specified peak bandwidth.
         */
        public Builder peakBandwidth(int val) {
            mPeakBandwidth = val;
            return this;
        }
        /**
         * Set the latency.
         *
         * @param val latency
         * @return BluetoothHidDeviceAppQosSettings Builder with specified latency.
         */
        public Builder latency(int val) {
            mLatency = val;
            return this;
        }

        /**
         * Set the delay variation.
         *
         * @param val delay variation
         * @return BluetoothHidDeviceAppQosSettings Builder with specified delay variation.
         */
        public Builder delayVariation(int val) {
            mDelayVariation = val;
            return this;
        }

        /**
         * Build the BluetoothHidDeviceAppQosSettings object.
         *
         * @return BluetoothHidDeviceAppQosSettings object with current settings.
         */
        public BluetoothHidDeviceAppQosSettings build() {
            return new BluetoothHidDeviceAppQosSettings(mServiceType, mTokenRate, mTokenBucketSize,
                    mPeakBandwidth, mLatency, mDelayVariation);
        }
    }
}
