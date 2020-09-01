/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A class representing capability of the SoftAp.
 * {@see WifiManager}
 *
 * @hide
 */
@SystemApi
public final class SoftApCapability implements Parcelable {

    /**
     * Support for automatic channel selection in driver (ACS).
     * Driver will auto select best channel based on interference to optimize performance.
     *
     * flag when {@link R.bool.config_wifi_softap_acs_supported)} is true.
     *
     * <p>
     * Use {@link WifiManager.SoftApCallback#onInfoChanged(SoftApInfo)} and
     * {@link SoftApInfo#getFrequency} and {@link SoftApInfo#getBandwidth} to get
     * driver channel selection result.
     */
    public static final long SOFTAP_FEATURE_ACS_OFFLOAD = 1 << 0;

    /**
     * Support for client force disconnect.
     * flag when {@link R.bool.config_wifi_sofap_client_force_disconnect_supported)} is true
     *
     * <p>
     * Several Soft AP client control features, e.g. specifying the maximum number of
     * Soft AP clients, only work when this feature support is present.
     * Check feature support before invoking
     * {@link SoftApConfiguration.Builder#setMaxNumberOfClients(int)}
     */
    public static final long SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT = 1 << 1;


    /**
     * Support for WPA3 Simultaneous Authentication of Equals (WPA3-SAE).
     *
     * flag when {@link config_wifi_softap_sae_supported)} is true.
     */
    public static final long SOFTAP_FEATURE_WPA3_SAE = 1 << 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @LongDef(flag = true, prefix = { "SOFTAP_FEATURE_" }, value = {
            SOFTAP_FEATURE_ACS_OFFLOAD,
            SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT,
            SOFTAP_FEATURE_WPA3_SAE,
    })
    public @interface HotspotFeatures {}

    private @HotspotFeatures long mSupportedFeatures = 0;

    private int mMaximumSupportedClientNumber;

    /**
     * Get the maximum supported client numbers which AP resides on.
     */
    public int getMaxSupportedClients() {
        return mMaximumSupportedClientNumber;
    }

    /**
     * Set the maximum supported client numbers which AP resides on.
     *
     * @param maxClient maximum supported client numbers for the softap.
     * @hide
     */
    public void setMaxSupportedClients(int maxClient) {
        mMaximumSupportedClientNumber = maxClient;
    }

    /**
     * Returns true when all of the queried features are supported, otherwise false.
     *
     * @param features One or combination of the following features:
     * {@link #SOFTAP_FEATURE_ACS_OFFLOAD}, {@link #SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT} or
     * {@link #SOFTAP_FEATURE_WPA3_SAE}.
     */
    public boolean areFeaturesSupported(@HotspotFeatures long features) {
        return (mSupportedFeatures & features) == features;
    }

    /**
     * @hide
     */
    public SoftApCapability(@Nullable SoftApCapability source) {
        if (source != null) {
            mSupportedFeatures = source.mSupportedFeatures;
            mMaximumSupportedClientNumber = source.mMaximumSupportedClientNumber;
        }
    }

    /**
     * Constructor with combination of the feature.
     * Zero to no supported feature.
     *
     * @param features One or combination of the following features:
     * {@link #SOFTAP_FEATURE_ACS_OFFLOAD}, {@link #SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT} or
     * {@link #SOFTAP_FEATURE_WPA3_SAE}.
     * @hide
     */
    public SoftApCapability(@HotspotFeatures long features) {
        mSupportedFeatures = features;
    }

    @Override
    /** Implement the Parcelable interface. */
    public int describeContents() {
        return 0;
    }

    @Override
    /** Implement the Parcelable interface */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mSupportedFeatures);
        dest.writeInt(mMaximumSupportedClientNumber);
    }

    @NonNull
    /** Implement the Parcelable interface */
    public static final Creator<SoftApCapability> CREATOR = new Creator<SoftApCapability>() {
        public SoftApCapability createFromParcel(Parcel in) {
            long supportedFeatures = in.readLong();
            SoftApCapability capability = new SoftApCapability(supportedFeatures);
            capability.mMaximumSupportedClientNumber = in.readInt();
            return capability;
        }

        public SoftApCapability[] newArray(int size) {
            return new SoftApCapability[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("SupportedFeatures=").append(mSupportedFeatures);
        sbuf.append("MaximumSupportedClientNumber=").append(mMaximumSupportedClientNumber);
        return sbuf.toString();
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (!(o instanceof SoftApCapability)) return false;
        SoftApCapability capability = (SoftApCapability) o;
        return mSupportedFeatures == capability.mSupportedFeatures
                && mMaximumSupportedClientNumber == capability.mMaximumSupportedClientNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSupportedFeatures, mMaximumSupportedClientNumber);
    }
}
