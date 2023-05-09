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
import android.os.Bundle;
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
     * Network source is unknown.
     */
    public static final int NETWORK_SOURCE_UNKNOWN = 0;

    /**
     * Network is known by a nearby device with the same user account.
     */
    public static final int NETWORK_SOURCE_NEARBY_SELF = 1;

    /**
     * Network is known via cloud storage associated with this device's user account.
     */
    public static final int NETWORK_SOURCE_CLOUD_SELF = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NETWORK_SOURCE_UNKNOWN,
            NETWORK_SOURCE_NEARBY_SELF,
            NETWORK_SOURCE_CLOUD_SELF
    })
    public @interface NetworkSource {
    }

    @NetworkSource
    private final int mNetworkSource;
    private final String mSsid;
    @SecurityType
    private final ArraySet<Integer> mSecurityTypes;
    private final NetworkProviderInfo mNetworkProviderInfo;
    private final Bundle mExtras;

    /**
     * Builder class for {@link KnownNetwork}.
     */
    public static final class Builder {
        @NetworkSource
        private int mNetworkSource = -1;
        private String mSsid;
        @SecurityType
        private final ArraySet<Integer> mSecurityTypes = new ArraySet<>();
        private NetworkProviderInfo mNetworkProviderInfo;
        private Bundle mExtras = Bundle.EMPTY;

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
         * Must be set if network source is {@link KnownNetwork#NETWORK_SOURCE_NEARBY_SELF}.
         *
         * @param networkProviderInfo The device information object.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setNetworkProviderInfo(@Nullable NetworkProviderInfo networkProviderInfo) {
            mNetworkProviderInfo = networkProviderInfo;
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
                    mNetworkProviderInfo,
                    mExtras);
        }
    }

    private static void validate(@NetworkSource int networkSource, String ssid,
            @SecurityType Set<Integer> securityTypes,
            NetworkProviderInfo networkProviderInfo) {
        if (networkSource != NETWORK_SOURCE_UNKNOWN
                && networkSource != NETWORK_SOURCE_CLOUD_SELF
                && networkSource != NETWORK_SOURCE_NEARBY_SELF) {
            throw new IllegalArgumentException("Illegal network source");
        }
        if (TextUtils.isEmpty(ssid)) {
            throw new IllegalArgumentException("SSID must be set");
        }
        if (securityTypes.isEmpty()) {
            throw new IllegalArgumentException("SecurityTypes must be set");
        }
        if (networkSource == NETWORK_SOURCE_NEARBY_SELF && networkProviderInfo == null) {
            throw new IllegalArgumentException("Device info must be provided when network source"
                    + " is NETWORK_SOURCE_NEARBY_SELF");
        }
    }

    private KnownNetwork(
            @NetworkSource int networkSource,
            @NonNull String ssid,
            @NonNull @SecurityType ArraySet<Integer> securityTypes,
            @Nullable NetworkProviderInfo networkProviderInfo,
            @NonNull Bundle extras) {
        validate(networkSource, ssid, securityTypes, networkProviderInfo);
        mNetworkSource = networkSource;
        mSsid = ssid;
        mSecurityTypes = new ArraySet<>(securityTypes);
        mNetworkProviderInfo = networkProviderInfo;
        mExtras = extras;
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
     * @return Returns the information of the device providing the known network. Can be null if the
     * network source is cloud or unknown.
     */
    @Nullable
    public NetworkProviderInfo getNetworkProviderInfo() {
        return mNetworkProviderInfo;
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
        if (!(obj instanceof KnownNetwork)) return false;
        KnownNetwork other = (KnownNetwork) obj;
        return mNetworkSource == other.getNetworkSource()
                && Objects.equals(mSsid, other.getSsid())
                && Objects.equals(mSecurityTypes, other.getSecurityTypes())
                && Objects.equals(mNetworkProviderInfo, other.getNetworkProviderInfo());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetworkSource, mSsid, mSecurityTypes, mNetworkProviderInfo);
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
        if (mNetworkProviderInfo != null) {
            dest.writeBoolean(true);
            mNetworkProviderInfo.writeToParcel(dest, flags);
        } else {
            dest.writeBoolean(false);
        }
        dest.writeBundle(mExtras);
    }

    /**
     * Creates a {@link KnownNetwork} object from a parcel.
     *
     * @hide
     */
    @NonNull
    public static KnownNetwork readFromParcel(@NonNull Parcel in) {
        int networkSource = in.readInt();
        String mSsid = in.readString();
        ArraySet<Integer> securityTypes = (ArraySet<Integer>) in.readArraySet(null);
        if (in.readBoolean()) {
            return new KnownNetwork(networkSource, mSsid, securityTypes,
                    NetworkProviderInfo.readFromParcel(in), in.readBundle());
        }
        return new KnownNetwork(networkSource, mSsid, securityTypes, null,
                in.readBundle());
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
                .append(", networkProviderInfo=").append(mNetworkProviderInfo.toString())
                .append(", extras=").append(mExtras.toString())
                .append("]").toString();
    }
}
