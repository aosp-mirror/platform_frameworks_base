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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * WiFi configuration for a soft access point (a.k.a. Soft AP, SAP, Hotspot).
 *
 * This is input for the framework provided by a client app, i.e. it exposes knobs to instruct the
 * framework how it should open a hotspot.  It is not meant to describe the network as it will be
 * seen by clients; this role is currently served by {@link WifiConfiguration} (see
 * {@link WifiManager.LocalOnlyHotspotReservation#getWifiConfiguration()}).
 *
 * System apps can use this to configure a local-only hotspot using
 * {@link WifiManager#startLocalOnlyHotspot(SoftApConfiguration, Executor,
 * WifiManager.LocalOnlyHotspotCallback)}.
 *
 * Instances of this class are immutable; use {@link SoftApConfiguration.Builder} and its methods to
 * create a new instance.
 *
 * @hide
 */
@SystemApi
public final class SoftApConfiguration implements Parcelable {
    /**
     * SSID for the AP, or null for a framework-determined SSID.
     */
    private final @Nullable String mSsid;
    /**
     * BSSID for the AP, or null to use a framework-determined BSSID.
     */
    private final @Nullable MacAddress mBssid;
    /**
     * Pre-shared key for WPA2-PSK encryption (non-null enables WPA2-PSK).
     */
    private final @Nullable String mWpa2Passphrase;

    /** Private constructor for Builder and Parcelable implementation. */
    private SoftApConfiguration(
            @Nullable String ssid, @Nullable MacAddress bssid, String wpa2Passphrase) {
        mSsid = ssid;
        mBssid = bssid;
        mWpa2Passphrase = wpa2Passphrase;
    }

    @Override
    public boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        }
        if (!(otherObj instanceof SoftApConfiguration)) {
            return false;
        }
        SoftApConfiguration other = (SoftApConfiguration) otherObj;
        return Objects.equals(mSsid, other.mSsid)
                && Objects.equals(mBssid, other.mBssid)
                && Objects.equals(mWpa2Passphrase, other.mWpa2Passphrase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSsid, mBssid, mWpa2Passphrase);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mSsid);
        dest.writeParcelable(mBssid, flags);
        dest.writeString(mWpa2Passphrase);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<SoftApConfiguration> CREATOR = new Creator<SoftApConfiguration>() {
        @Override
        public SoftApConfiguration createFromParcel(Parcel in) {
            return new SoftApConfiguration(
                    in.readString(),
                    in.readParcelable(MacAddress.class.getClassLoader()),
                    in.readString());
        }

        @Override
        public SoftApConfiguration[] newArray(int size) {
            return new SoftApConfiguration[size];
        }
    };

    @Nullable
    public String getSsid() {
        return mSsid;
    }

    @Nullable
    public MacAddress getBssid() {
        return mBssid;
    }

    @Nullable
    public String getWpa2Passphrase() {
        return mWpa2Passphrase;
    }

    /**
     * Builds a {@link SoftApConfiguration}, which allows an app to configure various aspects of a
     * Soft AP.
     *
     * All fields are optional. By default, SSID and BSSID are automatically chosen by the
     * framework, and an open network is created.
     */
    public static final class Builder {
        private String mSsid;
        private MacAddress mBssid;
        private String mWpa2Passphrase;

        /**
         * Constructs a Builder with default values (see {@link Builder}).
         */
        public Builder() {
            mSsid = null;
            mBssid = null;
            mWpa2Passphrase = null;
        }

        /**
         * Constructs a Builder initialized from an existing {@link SoftApConfiguration} instance.
         */
        public Builder(@NonNull SoftApConfiguration other) {
            Objects.requireNonNull(other);

            mSsid = other.mSsid;
            mBssid = other.mBssid;
            mWpa2Passphrase = other.mWpa2Passphrase;
        }

        /**
         * Builds the {@link SoftApConfiguration}.
         *
         * @return A new {@link SoftApConfiguration}, as configured by previous method calls.
         */
        @NonNull
        public SoftApConfiguration build() {
            return new SoftApConfiguration(mSsid, mBssid, mWpa2Passphrase);
        }

        /**
         * Specifies an SSID for the AP.
         *
         * @param ssid SSID of valid Unicode characters, or null to have the SSID automatically
         *             chosen by the framework.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the SSID is empty or not valid Unicode.
         */
        @NonNull
        public Builder setSsid(@Nullable String ssid) {
            if (ssid != null) {
                Preconditions.checkStringNotEmpty(ssid);
                Preconditions.checkArgument(StandardCharsets.UTF_8.newEncoder().canEncode(ssid));
            }
            mSsid = ssid;
            return this;
        }

        /**
         * Specifies a BSSID for the AP.
         *
         * @param bssid BSSID, or null to have the BSSID chosen by the framework. The caller is
         *              responsible for avoiding collisions.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the given BSSID is the all-zero or broadcast MAC
         *                                  address.
         */
        @NonNull
        public Builder setBssid(@Nullable MacAddress bssid) {
            if (bssid != null) {
                Preconditions.checkArgument(!bssid.equals(MacAddress.ALL_ZEROS_ADDRESS));
                Preconditions.checkArgument(!bssid.equals(MacAddress.BROADCAST_ADDRESS));
            }
            mBssid = bssid;
            return this;
        }

        /**
         * Specifies that this AP should use WPA2-PSK with the given passphrase.  When set to null
         * and no other encryption method is configured, an open network is created.
         *
         * @param passphrase The passphrase to use, or null to unset a previously-set WPA2-PSK
         *                   configuration.
         * @return Builder for chaining.
         * @throws IllegalArgumentException when the passphrase is the empty string
         */
        @NonNull
        public Builder setWpa2Passphrase(@Nullable String passphrase) {
            if (passphrase != null) {
                Preconditions.checkStringNotEmpty(passphrase);
            }
            mWpa2Passphrase = passphrase;
            return this;
        }
    }
}
