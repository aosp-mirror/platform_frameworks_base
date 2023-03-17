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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * A data class representing a hotspot network.
 * This class is used in IPC calls between the implementer of {@link SharedConnectivityService} and
 * the consumers of {@link com.android.wifitrackerlib}.
 *
 * @hide
 */
@SystemApi
public final class HotspotNetwork implements Parcelable {
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
    public @interface NetworkType {
    }

    private final long mDeviceId;
    private final NetworkProviderInfo mNetworkProviderInfo;
    @NetworkType
    private final int mNetworkType;
    private final String mNetworkName;
    @Nullable
    private final String mHotspotSsid;
    @Nullable
    private final String mHotspotBssid;
    @Nullable
    @SecurityType
    private final ArraySet<Integer> mHotspotSecurityTypes;
    private final Bundle mExtras;

    /**
     * Builder class for {@link HotspotNetwork}.
     */
    public static final class Builder {
        private long mDeviceId = -1;
        private NetworkProviderInfo mNetworkProviderInfo;
        @NetworkType
        private int mNetworkType;
        private String mNetworkName;
        @Nullable
        private String mHotspotSsid;
        @Nullable
        private String mHotspotBssid;
        @Nullable
        @SecurityType
        private final ArraySet<Integer> mHotspotSecurityTypes = new ArraySet<>();
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Set the remote device ID.
         *
         * @param deviceId Locally unique ID for this Hotspot network.
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
         * @param networkProviderInfo The device information object.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setNetworkProviderInfo(@NonNull NetworkProviderInfo networkProviderInfo) {
            mNetworkProviderInfo = networkProviderInfo;
            return this;
        }

        /**
         * Sets the network type that the remote device is connected to.
         *
         * @param networkType Network type as represented by IntDef {@link NetworkType}.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setHostNetworkType(@NetworkType int networkType) {
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
         * Sets the extras bundle
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link HotspotNetwork} object.
         *
         * @return Returns the built {@link HotspotNetwork} object.
         */
        @NonNull
        public HotspotNetwork build() {
            return new HotspotNetwork(
                    mDeviceId,
                    mNetworkProviderInfo,
                    mNetworkType,
                    mNetworkName,
                    mHotspotSsid,
                    mHotspotBssid,
                    mHotspotSecurityTypes,
                    mExtras);
        }
    }

    private static void validate(long deviceId, @NetworkType int networkType, String networkName,
            NetworkProviderInfo networkProviderInfo) {
        if (deviceId < 0) {
            throw new IllegalArgumentException("DeviceId must be set");
        }
        if (Objects.isNull(networkProviderInfo)) {
            throw new IllegalArgumentException("NetworkProviderInfo must be set");
        }
        if (networkType != NETWORK_TYPE_CELLULAR && networkType != NETWORK_TYPE_WIFI
                && networkType != NETWORK_TYPE_ETHERNET && networkType != NETWORK_TYPE_UNKNOWN) {
            throw new IllegalArgumentException("Illegal network type");
        }
        if (Objects.isNull(networkName)) {
            throw new IllegalArgumentException("NetworkName must be set");
        }
    }

    private HotspotNetwork(
            long deviceId,
            NetworkProviderInfo networkProviderInfo,
            @NetworkType int networkType,
            @NonNull String networkName,
            @Nullable String hotspotSsid,
            @Nullable String hotspotBssid,
            @Nullable @SecurityType ArraySet<Integer> hotspotSecurityTypes,
            @NonNull Bundle extras) {
        validate(deviceId,
                networkType,
                networkName,
                networkProviderInfo);
        mDeviceId = deviceId;
        mNetworkProviderInfo = networkProviderInfo;
        mNetworkType = networkType;
        mNetworkName = networkName;
        mHotspotSsid = hotspotSsid;
        mHotspotBssid = hotspotBssid;
        mHotspotSecurityTypes = new ArraySet<>(hotspotSecurityTypes);
        mExtras = extras;
    }

    /**
     * Gets the remote device ID.
     *
     * @return Returns the locally unique ID for this Hotspot network.
     */
    public long getDeviceId() {
        return mDeviceId;
    }

    /**
     * Gets information about the device providing connectivity.
     *
     * @return Returns the information of the device providing the Hotspot network.
     */
    @NonNull
    public NetworkProviderInfo getNetworkProviderInfo() {
        return mNetworkProviderInfo;
    }

    /**
     * Gets the network type that the remote device is connected to.
     *
     * @return Returns the network type as represented by IntDef {@link NetworkType}.
     */
    @NetworkType
    public int getHostNetworkType() {
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
        if (!(obj instanceof HotspotNetwork)) return false;
        HotspotNetwork other = (HotspotNetwork) obj;
        return mDeviceId == other.getDeviceId()
                && Objects.equals(mNetworkProviderInfo, other.getNetworkProviderInfo())
                && mNetworkType == other.getHostNetworkType()
                && Objects.equals(mNetworkName, other.getNetworkName())
                && Objects.equals(mHotspotSsid, other.getHotspotSsid())
                && Objects.equals(mHotspotBssid, other.getHotspotBssid())
                && Objects.equals(mHotspotSecurityTypes, other.getHotspotSecurityTypes());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceId, mNetworkProviderInfo, mNetworkName, mHotspotSsid,
                mHotspotBssid, mHotspotSecurityTypes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mDeviceId);
        mNetworkProviderInfo.writeToParcel(dest, flags);
        dest.writeInt(mNetworkType);
        dest.writeString(mNetworkName);
        dest.writeString(mHotspotSsid);
        dest.writeString(mHotspotBssid);
        dest.writeArraySet(mHotspotSecurityTypes);
        dest.writeBundle(mExtras);
    }

    /**
     * Creates a {@link HotspotNetwork} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static HotspotNetwork readFromParcel(@NonNull Parcel in) {
        return new HotspotNetwork(in.readLong(), NetworkProviderInfo.readFromParcel(in),
                in.readInt(), in.readString(), in.readString(), in.readString(),
                (ArraySet<Integer>) in.readArraySet(null), in.readBundle());
    }

    @NonNull
    public static final Creator<HotspotNetwork> CREATOR = new Creator<>() {
        @Override
        public HotspotNetwork createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public HotspotNetwork[] newArray(int size) {
            return new HotspotNetwork[size];
        }
    };

    @Override
    public String toString() {
        return new StringBuilder("HotspotNetwork[")
                .append("deviceId=").append(mDeviceId)
                .append(", networkType=").append(mNetworkType)
                .append(", networkProviderInfo=").append(mNetworkProviderInfo.toString())
                .append(", networkName=").append(mNetworkName)
                .append(", hotspotSsid=").append(mHotspotSsid)
                .append(", hotspotBssid=").append(mHotspotBssid)
                .append(", hotspotSecurityTypes=").append(mHotspotSecurityTypes.toString())
                .append(", extras=").append(mExtras.toString())
                .append("]").toString();
    }
}
