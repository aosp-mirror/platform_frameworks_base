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

import static android.net.wifi.WifiAnnotations.SecurityType;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.sharedconnectivity.service.SharedConnectivityService;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * A data class representing an Instant Tether network.
 * This class is used in IPC calls between the implementer of {@link SharedConnectivityService} and
 * the consumers of {@link com.android.wifitrackerlib}.
 *
 * @hide
 */
@SystemApi
public final class TetherNetwork implements Parcelable {
    /**
     * Remote device is connected to the internet via an unknown connection.
     */
    public static final int NETWORK_TYPE_UNKNOWN = 0;

    /**
     * Remote device is connected to the internet via a cellular connection.
     */
    public static final int NETWORK_TYPE_CELLULAR = 1;

    /**
     * Remote device is connected to the internet via a Wi-Fi connection.
     */
    public static final int NETWORK_TYPE_WIFI = 2;

    /**
     * Remote device is connected to the internet via an ethernet connection.
     */
    public static final int NETWORK_TYPE_ETHERNET = 3;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NETWORK_TYPE_UNKNOWN,
            NETWORK_TYPE_CELLULAR,
            NETWORK_TYPE_WIFI,
            NETWORK_TYPE_ETHERNET
    })
    public @interface NetworkType {}

    private final long mDeviceId;
    private final DeviceInfo mDeviceInfo;
    @NetworkType private final int mNetworkType;
    private final String mNetworkName;
    @Nullable private final String mHotspotSsid;
    @Nullable private final String mHotspotBssid;
    @Nullable @SecurityType private final ArraySet<Integer> mHotspotSecurityTypes;

    /**
     * Builder class for {@link TetherNetwork}.
     */
    public static final class Builder {
        private long mDeviceId = -1;
        private DeviceInfo mDeviceInfo;
        @NetworkType private int mNetworkType;
        private String mNetworkName;
        @Nullable private String mHotspotSsid;
        @Nullable private String mHotspotBssid;
        @Nullable @SecurityType private final ArraySet<Integer> mHotspotSecurityTypes =
                new ArraySet<>();

        /**
         * Set the remote device ID.
         *
         * @param deviceId Locally unique ID for this Instant Tether network.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setDeviceId(long deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /**
         * Sets information about the device providing connectivity.
         *
         * @param deviceInfo The device information object.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setDeviceInfo(@NonNull DeviceInfo deviceInfo) {
            mDeviceInfo = deviceInfo;
            return this;
        }

        /**
         * Sets the network type that the remote device is connected to.
         *
         * @param networkType Network type as represented by IntDef {@link NetworkType}.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setNetworkType(@NetworkType int networkType) {
            mNetworkType = networkType;
            return this;
        }

        /**
         * Sets the display name of the network the remote device is connected to.
         *
         * @param networkName Network display name. (e.g. "Google Fi", "Hotel WiFi", "Ethernet")
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setNetworkName(@NonNull String networkName) {
            mNetworkName = networkName;
            return this;
        }

        /**
         * Sets the hotspot SSID being broadcast by the remote device, or null if hotspot is off.
         *
         * @param hotspotSsid The SSID of the hotspot. Surrounded by double quotes if UTF-8.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setHotspotSsid(@NonNull String hotspotSsid) {
            mHotspotSsid = hotspotSsid;
            return this;
        }

        /**
         * Sets the hotspot BSSID being broadcast by the remote device, or null if hotspot is off.
         *
         * @param hotspotBssid The BSSID of the hotspot.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setHotspotBssid(@NonNull String hotspotBssid) {
            mHotspotBssid = hotspotBssid;
            return this;
        }

        /**
         * Adds a security type supported by the hotspot created by the remote device.
         *
         * @param hotspotSecurityType A security type supported by the hotspot.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder addHotspotSecurityType(@SecurityType int hotspotSecurityType) {
            mHotspotSecurityTypes.add(hotspotSecurityType);
            return this;
        }

        /**
         * Builds the {@link TetherNetwork} object.
         *
         * @return Returns the built {@link TetherNetwork} object.
         */
        @NonNull
        public TetherNetwork build() {
            return new TetherNetwork(
                    mDeviceId,
                    mDeviceInfo,
                    mNetworkType,
                    mNetworkName,
                    mHotspotSsid,
                    mHotspotBssid,
                    mHotspotSecurityTypes);
        }
    }

    private static void validate(long deviceId, int networkType, String networkName) {
        if (deviceId < 0) {
            throw new IllegalArgumentException("DeviceId must be set");
        }
        if (networkType != NETWORK_TYPE_CELLULAR && networkType != NETWORK_TYPE_WIFI
                && networkType != NETWORK_TYPE_ETHERNET && networkType != NETWORK_TYPE_UNKNOWN) {
            throw new IllegalArgumentException("Illegal network type");
        }
        if (Objects.isNull(networkName)) {
            throw new IllegalArgumentException("NetworkName must be set");
        }
    }

    private TetherNetwork(
            long deviceId,
            DeviceInfo deviceInfo,
            @NetworkType int networkType,
            @NonNull String networkName,
            @Nullable String hotspotSsid,
            @Nullable String hotspotBssid,
            @Nullable @SecurityType ArraySet<Integer> hotspotSecurityTypes) {
        validate(deviceId,
                networkType,
                networkName);
        mDeviceId = deviceId;
        mDeviceInfo = deviceInfo;
        mNetworkType = networkType;
        mNetworkName = networkName;
        mHotspotSsid = hotspotSsid;
        mHotspotBssid = hotspotBssid;
        mHotspotSecurityTypes = new ArraySet<>(hotspotSecurityTypes);
    }

    /**
     * Gets the remote device ID.
     *
     * @return Returns the locally unique ID for this Instant Tether network.
     */
    public long getDeviceId() {
        return mDeviceId;
    }

    /**
     * Gets information about the device providing connectivity.
     *
     * @return Returns the information of the device providing the Instant Tether network.
     */
    @NonNull
    public DeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    /**
     * Gets the network type that the remote device is connected to.
     *
     * @return Returns the network type as represented by IntDef {@link NetworkType}.
     */
    @NetworkType
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * Gets the display name of the network the remote device is connected to.
     *
     * @return Returns the network display name. (e.g. "Google Fi", "Hotel WiFi", "Ethernet")
     */
    @NonNull
    public String getNetworkName() {
        return mNetworkName;
    }

    /**
     * Gets the hotspot SSID being broadcast by the remote device, or null if hotspot is off.
     *
     * @return Returns the SSID of the hotspot. Surrounded by double quotes if UTF-8.
     */
    @Nullable
    public String getHotspotSsid() {
        return mHotspotSsid;
    }

    /**
     * Gets the hotspot BSSID being broadcast by the remote device, or null if hotspot is off.
     *
     * @return Returns the BSSID of the hotspot.
     */
    @Nullable
    public String getHotspotBssid() {
        return mHotspotBssid;
    }

    /**
     * Gets the hotspot security types supported by the remote device.
     *
     * @return Returns a set of the security types supported by the hotspot.
     */
    @NonNull
    @SecurityType
    public Set<Integer> getHotspotSecurityTypes() {
        return mHotspotSecurityTypes;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TetherNetwork)) return false;
        TetherNetwork other = (TetherNetwork) obj;
        return mDeviceId == other.getDeviceId()
                && Objects.equals(mDeviceInfo, other.getDeviceInfo())
                && mNetworkType == other.getNetworkType()
                && Objects.equals(mNetworkName, other.getNetworkName())
                && Objects.equals(mHotspotSsid, other.getHotspotSsid())
                && Objects.equals(mHotspotBssid, other.getHotspotBssid())
                && Objects.equals(mHotspotSecurityTypes, other.getHotspotSecurityTypes());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceId, mDeviceInfo, mNetworkName, mHotspotSsid, mHotspotBssid,
                mHotspotSecurityTypes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mDeviceId);
        mDeviceInfo.writeToParcel(dest, flags);
        dest.writeInt(mNetworkType);
        dest.writeString(mNetworkName);
        dest.writeString(mHotspotSsid);
        dest.writeString(mHotspotBssid);
        dest.writeArraySet(mHotspotSecurityTypes);
    }

    /**
     * Creates a {@link TetherNetwork} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static TetherNetwork readFromParcel(@NonNull Parcel in) {
        return new TetherNetwork(in.readLong(), DeviceInfo.readFromParcel(in),
                in.readInt(), in.readString(), in.readString(), in.readString(),
                (ArraySet<Integer>) in.readArraySet(null));
    }

    @NonNull
    public static final Creator<TetherNetwork> CREATOR = new Creator<>() {
        @Override
        public TetherNetwork createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public TetherNetwork[] newArray(int size) {
            return new TetherNetwork[size];
        }
    };

    @Override
    public String toString() {
        return new StringBuilder("TetherNetwork[")
                .append("deviceId=").append(mDeviceId)
                .append(", networkType=").append(mNetworkType)
                .append(", deviceInfo=").append(mDeviceInfo.toString())
                .append(", networkName=").append(mNetworkName)
                .append(", hotspotSsid=").append(mHotspotSsid)
                .append(", hotspotBssid=").append(mHotspotBssid)
                .append(", hotspotSecurityTypes=").append(mHotspotSecurityTypes.toString())
                .append("]").toString();
    }
}
