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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * A data class representing a known Wi-Fi network.
 *
 * @hide
 */
@SystemApi
public final class KnownNetwork implements Parcelable {
    /**
     * Network is known by a nearby device with the same user account.
     */
    public static final int NETWORK_SOURCE_NEARBY_SELF = 0;

    /**
     * Network is known via cloud storage associated with this device's user account.
     */
    public static final int NETWORK_SOURCE_CLOUD_SELF = 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NETWORK_SOURCE_NEARBY_SELF,
            NETWORK_SOURCE_CLOUD_SELF
    })
    public @interface NetworkSource {}

    @NetworkSource private final int mNetworkSource;
    private final String mSsid;
    @SecurityType private final ArraySet<Integer> mSecurityTypes;
    private final DeviceInfo mDeviceInfo;

    /**
     * Builder class for {@link KnownNetwork}.
     */
    public static final class Builder {
        @NetworkSource private int mNetworkSource = -1;
        private String mSsid;
        @SecurityType private final ArraySet<Integer> mSecurityTypes = new ArraySet<>();
        private android.net.wifi.sharedconnectivity.app.DeviceInfo mDeviceInfo;

        /**
         * Sets the indicated source of the known network.
         *
         * @param networkSource The network source as defined by IntDef {@link NetworkSource}.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setNetworkSource(@NetworkSource int networkSource) {
            mNetworkSource = networkSource;
            return this;
        }

        /**
         * Sets the SSID of the known network.
         *
         * @param ssid The SSID of the known network. Surrounded by double quotes if UTF-8.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setSsid(@NonNull String ssid) {
            mSsid = ssid;
            return this;
        }

        /**
         * Adds a security type of the known network.
         *
         * @param securityType A security type supported by the known network.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder addSecurityType(@SecurityType int securityType) {
            mSecurityTypes.add(securityType);
            return this;
        }

        /**
         * Sets the device information of the device providing connectivity.
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
         * Builds the {@link KnownNetwork} object.
         *
         * @return Returns the built {@link KnownNetwork} object.
         */
        @NonNull
        public KnownNetwork build() {
            return new KnownNetwork(
                    mNetworkSource,
                    mSsid,
                    mSecurityTypes,
                    mDeviceInfo);
        }
    }

    private static void validate(int networkSource, String ssid, Set<Integer> securityTypes) {
        if (networkSource != NETWORK_SOURCE_CLOUD_SELF && networkSource
                != NETWORK_SOURCE_NEARBY_SELF) {
            throw new IllegalArgumentException("Illegal network source");
        }
        if (TextUtils.isEmpty(ssid)) {
            throw new IllegalArgumentException("SSID must be set");
        }
        if (securityTypes.isEmpty()) {
            throw new IllegalArgumentException("SecurityTypes must be set");
        }
    }

    private KnownNetwork(
            @NetworkSource int networkSource,
            @NonNull String ssid,
            @NonNull @SecurityType ArraySet<Integer> securityTypes,
            @NonNull DeviceInfo deviceInfo) {
        validate(networkSource, ssid, securityTypes);
        mNetworkSource = networkSource;
        mSsid = ssid;
        mSecurityTypes = new ArraySet<>(securityTypes);
        mDeviceInfo = deviceInfo;
    }

    /**
     * Gets the indicated source of the known network.
     *
     * @return Returns the network source as defined by IntDef {@link NetworkSource}.
     */
    @NetworkSource
    public int getNetworkSource() {
        return mNetworkSource;
    }

    /**
     * Gets the SSID of the known network.
     *
     * @return Returns the SSID of the known network. Surrounded by double quotes if UTF-8.
     */
    @NonNull
    public String getSsid() {
        return mSsid;
    }

    /**
     * Gets the security types of the known network.
     *
     * @return Returns a set with security types supported by the known network.
     */
    @NonNull
    @SecurityType
    public Set<Integer> getSecurityTypes() {
        return mSecurityTypes;
    }

    /**
     * Gets the device information of the device providing connectivity.
     *
     * @return Returns the information of the device providing the known network.
     */
    @NonNull
    public DeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof KnownNetwork)) return false;
        KnownNetwork other = (KnownNetwork) obj;
        return mNetworkSource == other.getNetworkSource()
                && Objects.equals(mSsid, other.getSsid())
                && Objects.equals(mSecurityTypes, other.getSecurityTypes())
                && Objects.equals(mDeviceInfo, other.getDeviceInfo());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkSource, mSsid, mSecurityTypes, mDeviceInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNetworkSource);
        dest.writeString(mSsid);
        dest.writeArraySet(mSecurityTypes);
        mDeviceInfo.writeToParcel(dest, flags);
    }

    /**
     * Creates a {@link KnownNetwork} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static KnownNetwork readFromParcel(@NonNull Parcel in) {
        return new KnownNetwork(in.readInt(), in.readString(),
                (ArraySet<Integer>) in.readArraySet(null),
                DeviceInfo.readFromParcel(in));
    }

    @NonNull
    public static final Creator<KnownNetwork> CREATOR = new Creator<>() {
        @Override
        public KnownNetwork createFromParcel(Parcel in) {
            return readFromParcel(in);
        }

        @Override
        public KnownNetwork[] newArray(int size) {
            return new KnownNetwork[size];
        }
    };

    @Override
    public String toString() {
        return new StringBuilder("KnownNetwork[")
                .append("NetworkSource=").append(mNetworkSource)
                .append(", ssid=").append(mSsid)
                .append(", securityTypes=").append(mSecurityTypes.toString())
                .append(", deviceInfo=").append(mDeviceInfo.toString())
                .append("]").toString();
    }
}
