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

package android.net.wifi.sharedconnectivity.app;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.wifi.flags.Flags;
import android.net.wifi.sharedconnectivity.service.SharedConnectivityService;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A data class representing a device providing connectivity.
 * This class is used in IPC calls between the implementer of {@link SharedConnectivityService} and
 * the consumers of {@link com.android.wifitrackerlib}.
 *
 * @hide
 */
@SystemApi
public final class NetworkProviderInfo implements Parcelable {

    /**
     * Device type providing connectivity is unknown.
     */
    public static final int DEVICE_TYPE_UNKNOWN = 0;

    /**
     * Device providing connectivity is a mobile phone.
     */
    public static final int DEVICE_TYPE_PHONE = 1;

    /**
     * Device providing connectivity is a tablet.
     */
    public static final int DEVICE_TYPE_TABLET = 2;

    /**
     * Device providing connectivity is a laptop.
     */
    public static final int DEVICE_TYPE_LAPTOP = 3;

    /**
     * Device providing connectivity is a watch.
     */
    public static final int DEVICE_TYPE_WATCH = 4;

    /**
     * Device providing connectivity is a watch.
     */
    public static final int DEVICE_TYPE_AUTO = 5;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DEVICE_TYPE_UNKNOWN,
            DEVICE_TYPE_PHONE,
            DEVICE_TYPE_TABLET,
            DEVICE_TYPE_LAPTOP,
            DEVICE_TYPE_WATCH,
            DEVICE_TYPE_AUTO
    })
    public @interface DeviceType {
    }

    @DeviceType
    private final int mDeviceType;
    private final String mDeviceName;
    private final String mModelName;
    private final int mBatteryPercentage;
    private final boolean mIsBatteryCharging;
    private final int mConnectionStrength;
    private final Bundle mExtras;

    /**
     * Builder class for {@link NetworkProviderInfo}.
     */
    public static final class Builder {
        private int mDeviceType;
        private String mDeviceName;
        private String mModelName;
        private int mBatteryPercentage;
        private boolean mIsBatteryCharging;
        private int mConnectionStrength;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder(@NonNull String deviceName, @NonNull String modelName) {
            Objects.requireNonNull(deviceName);
            Objects.requireNonNull(modelName);
            mDeviceName = deviceName;
            mModelName = modelName;
        }

        /**
         * Sets the device type that provides connectivity.
         *
         * @param deviceType Device type as represented by IntDef {@link DeviceType}.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setDeviceType(@DeviceType int deviceType) {
            mDeviceType = deviceType;
            return this;
        }

        /**
         * Sets the device name of the remote device.
         *
         * @param deviceName The user configurable device name.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setDeviceName(@NonNull String deviceName) {
            Objects.requireNonNull(deviceName);
            mDeviceName = deviceName;
            return this;
        }

        /**
         * Sets the model name of the remote device.
         *
         * @param modelName The OEM configured name for the device model.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setModelName(@NonNull String modelName) {
            Objects.requireNonNull(modelName);
            mModelName = modelName;
            return this;
        }

        /**
         * Sets the battery charge percentage of the remote device.
         *
         * @param batteryPercentage The battery charge percentage in the range 0 to 100.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setBatteryPercentage(@IntRange(from = 0, to = 100) int batteryPercentage) {
            mBatteryPercentage = batteryPercentage;
            return this;
        }

        /**
         * Sets if the battery of the remote device is charging.
         *
         * @param isBatteryCharging True if battery is charging.
         * @return Returns the Builder object.
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_NETWORK_PROVIDER_BATTERY_CHARGING_STATUS)
        public Builder setBatteryCharging(boolean isBatteryCharging) {
            mIsBatteryCharging = isBatteryCharging;
            return this;
        }

        /**
         * Sets the displayed connection strength of the remote device to the internet.
         *
         * @param connectionStrength Connection strength in range 0 to 4.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setConnectionStrength(@IntRange(from = 0, to = 4) int connectionStrength) {
            mConnectionStrength = connectionStrength;
            return this;
        }

        /**
         * Sets the extras bundle
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            Objects.requireNonNull(extras);
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link NetworkProviderInfo} object.
         *
         * @return Returns the built {@link NetworkProviderInfo} object.
         */
        @NonNull
        public NetworkProviderInfo build() {
            return new NetworkProviderInfo(mDeviceType, mDeviceName, mModelName, mBatteryPercentage,
                    mIsBatteryCharging, mConnectionStrength, mExtras);
        }
    }

    private static void validate(@DeviceType int deviceType, String deviceName, String modelName,
            int batteryPercentage, int connectionStrength) {
        if (deviceType != DEVICE_TYPE_UNKNOWN && deviceType != DEVICE_TYPE_PHONE
                && deviceType != DEVICE_TYPE_TABLET && deviceType != DEVICE_TYPE_LAPTOP
                && deviceType != DEVICE_TYPE_WATCH && deviceType != DEVICE_TYPE_AUTO) {
            throw new IllegalArgumentException("Illegal device type");
        }
        if (batteryPercentage < 0 || batteryPercentage > 100) {
            throw new IllegalArgumentException("BatteryPercentage must be in range 0-100");
        }
        if (connectionStrength < 0 || connectionStrength > 4) {
            throw new IllegalArgumentException("ConnectionStrength must be in range 0-4");
        }
    }

    private NetworkProviderInfo(@DeviceType int deviceType, @NonNull String deviceName,
            @NonNull String modelName, int batteryPercentage, boolean isBatteryCharging,
            int connectionStrength, @NonNull Bundle extras) {
        validate(deviceType, deviceName, modelName, batteryPercentage, connectionStrength);
        mDeviceType = deviceType;
        mDeviceName = deviceName;
        mModelName = modelName;
        mBatteryPercentage = batteryPercentage;
        mIsBatteryCharging = isBatteryCharging;
        mConnectionStrength = connectionStrength;
        mExtras = extras;
    }

    /**
     * Gets the device type that provides connectivity.
     *
     * @return Returns the device type as represented by IntDef {@link DeviceType}.
     */
    @DeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    /**
     * Gets the device name of the remote device.
     *
     * @return Returns the user configurable device name.
     */
    @NonNull
    public String getDeviceName() {
        return mDeviceName;
    }

    /**
     * Gets the model name of the remote device.
     *
     * @return Returns the OEM configured name for the device model.
     */
    @NonNull
    public String getModelName() {
        return mModelName;
    }

    /**
     * Gets the battery charge percentage of the remote device.
     *
     * @return Returns the battery charge percentage in the range 0 to 100.
     */
    @IntRange(from = 0, to = 100)
    public int getBatteryPercentage() {
        return mBatteryPercentage;
    }

    /**
     * Gets the charging state of the battery on the remote device.
     *
     * @return Returns true if the battery of the remote device is charging.
     */
    @FlaggedApi(Flags.FLAG_NETWORK_PROVIDER_BATTERY_CHARGING_STATUS)
    public boolean isBatteryCharging() {
        return mIsBatteryCharging;
    }

    /**
     * Gets the displayed connection strength of the remote device to the internet.
     *
     * @return Returns the connection strength in range 0 to 4.
     */
    @IntRange(from = 0, to = 4)
    public int getConnectionStrength() {
        return mConnectionStrength;
    }

    /**
     * Gets the extras Bundle.
     *
     * @return Returns a Bundle object.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof NetworkProviderInfo)) return false;
        NetworkProviderInfo other = (NetworkProviderInfo) obj;
        return mDeviceType == other.getDeviceType()
                && Objects.equals(mDeviceName, other.mDeviceName)
                && Objects.equals(mModelName, other.mModelName)
                && mBatteryPercentage == other.mBatteryPercentage
                && mIsBatteryCharging == other.mIsBatteryCharging
                && mConnectionStrength == other.mConnectionStrength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceType, mDeviceName, mModelName, mBatteryPercentage,
                mIsBatteryCharging, mConnectionStrength);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDeviceType);
        dest.writeString(mDeviceName);
        dest.writeString(mModelName);
        dest.writeInt(mBatteryPercentage);
        dest.writeBoolean(mIsBatteryCharging);
        dest.writeInt(mConnectionStrength);
        dest.writeBundle(mExtras);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Creates a {@link NetworkProviderInfo} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static NetworkProviderInfo readFromParcel(@NonNull Parcel in) {
        return new NetworkProviderInfo(in.readInt(), in.readString(), in.readString(), in.readInt(),
                in.readBoolean(), in.readInt(), in.readBundle());
    }

    @NonNull
    public static final Creator<NetworkProviderInfo> CREATOR = new Creator<NetworkProviderInfo>() {
        @Override
        public NetworkProviderInfo createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public NetworkProviderInfo[] newArray(int size) {
            return new NetworkProviderInfo[size];
        }
    };

    @Override
    public String toString() {
        return new StringBuilder("NetworkProviderInfo[")
                .append("deviceType=").append(mDeviceType)
                .append(", deviceName=").append(mDeviceName)
                .append(", modelName=").append(mModelName)
                .append(", batteryPercentage=").append(mBatteryPercentage)
                .append(", isBatteryCharging=").append(mIsBatteryCharging)
                .append(", connectionStrength=").append(mConnectionStrength)
                .append(", extras=").append(mExtras.toString())
                .append("]").toString();
    }
}
