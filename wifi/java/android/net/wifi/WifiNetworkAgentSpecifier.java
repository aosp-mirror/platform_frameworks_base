/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Network specifier object used by wifi's {@link android.net.NetworkAgent}.
 * @hide
 */
public final class WifiNetworkAgentSpecifier extends NetworkSpecifier implements Parcelable {
    /**
     * Security credentials for the currently connected network.
     */
    private final WifiConfiguration mWifiConfiguration;

    public WifiNetworkAgentSpecifier(@NonNull WifiConfiguration wifiConfiguration) {
        checkNotNull(wifiConfiguration);

        mWifiConfiguration = wifiConfiguration;
    }

    /**
     * @hide
     */
    public static final @android.annotation.NonNull Creator<WifiNetworkAgentSpecifier> CREATOR =
            new Creator<WifiNetworkAgentSpecifier>() {
                @Override
                public WifiNetworkAgentSpecifier createFromParcel(@NonNull Parcel in) {
                    WifiConfiguration wifiConfiguration = in.readParcelable(null);
                    return new WifiNetworkAgentSpecifier(wifiConfiguration);
                }

                @Override
                public WifiNetworkAgentSpecifier[] newArray(int size) {
                    return new WifiNetworkAgentSpecifier[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mWifiConfiguration, flags);
    }

    @Override
    public boolean canBeSatisfiedBy(@Nullable NetworkSpecifier other) {
        if (this == other) {
            return true;
        }
        // Any generic requests should be satisifed by a specific wifi network.
        if (other == null || other instanceof MatchAllNetworkSpecifier) {
            return true;
        }
        if (other instanceof WifiNetworkSpecifier) {
            return satisfiesNetworkSpecifier((WifiNetworkSpecifier) other);
        }
        return equals(other);
    }

    /**
     * Match {@link WifiNetworkSpecifier} in app's {@link NetworkRequest} with the
     * {@link WifiNetworkAgentSpecifier} in wifi platform's {@link android.net.NetworkAgent}.
     */
    public boolean satisfiesNetworkSpecifier(@NonNull WifiNetworkSpecifier ns) {
        // None of these should be null by construction.
        // {@link WifiNetworkSpecifier.Builder} enforces non-null in {@link WifiNetworkSpecifier}.
        // {@link WifiNetworkFactory} ensures non-null in {@link WifiNetworkAgentSpecifier}.
        checkNotNull(ns);
        checkNotNull(ns.ssidPatternMatcher);
        checkNotNull(ns.bssidPatternMatcher);
        checkNotNull(ns.wifiConfiguration.allowedKeyManagement);
        checkNotNull(this.mWifiConfiguration.SSID);
        checkNotNull(this.mWifiConfiguration.BSSID);
        checkNotNull(this.mWifiConfiguration.allowedKeyManagement);

        final String ssidWithQuotes = this.mWifiConfiguration.SSID;
        checkState(ssidWithQuotes.startsWith("\"") && ssidWithQuotes.endsWith("\""));
        final String ssidWithoutQuotes = ssidWithQuotes.substring(1, ssidWithQuotes.length() - 1);
        if (!ns.ssidPatternMatcher.match(ssidWithoutQuotes)) {
            return false;
        }
        final MacAddress bssid = MacAddress.fromString(this.mWifiConfiguration.BSSID);
        final MacAddress matchBaseAddress = ns.bssidPatternMatcher.first;
        final MacAddress matchMask = ns.bssidPatternMatcher.second;
        if (!bssid.matches(matchBaseAddress, matchMask))  {
            return false;
        }
        if (!ns.wifiConfiguration.allowedKeyManagement.equals(
                this.mWifiConfiguration.allowedKeyManagement)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mWifiConfiguration.SSID,
                mWifiConfiguration.BSSID,
                mWifiConfiguration.allowedKeyManagement);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WifiNetworkAgentSpecifier)) {
            return false;
        }
        WifiNetworkAgentSpecifier lhs = (WifiNetworkAgentSpecifier) obj;
        return Objects.equals(this.mWifiConfiguration.SSID, lhs.mWifiConfiguration.SSID)
                && Objects.equals(this.mWifiConfiguration.BSSID, lhs.mWifiConfiguration.BSSID)
                && Objects.equals(this.mWifiConfiguration.allowedKeyManagement,
                    lhs.mWifiConfiguration.allowedKeyManagement);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WifiNetworkAgentSpecifier [");
        sb.append("WifiConfiguration=")
                .append(", SSID=").append(mWifiConfiguration.SSID)
                .append(", BSSID=").append(mWifiConfiguration.BSSID)
                .append("]");
        return sb.toString();
    }

    @Override
    public NetworkSpecifier redact() {
        return null;
    }
}
