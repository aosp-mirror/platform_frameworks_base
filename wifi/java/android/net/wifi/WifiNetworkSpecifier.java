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

import android.annotation.NonNull;
import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkSpecifier;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.util.Pair;

import java.util.Objects;

/**
 * Network specifier object used to request a Wi-Fi network. Apps should use the
 * {@link WifiNetworkConfigBuilder} class to create an instance.
 * @hide
 */
public final class WifiNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    /**
     * SSID pattern match specified by the app.
     */
    public final PatternMatcher ssidPatternMatcher;

    /**
     * BSSID pattern match specified by the app.
     * Pair of <BaseAddress, Mask>.
     */
    public final Pair<MacAddress, MacAddress> bssidPatternMatcher;

    /**
     * Security credentials for the network.
     * <p>
     * Note: {@link WifiConfiguration#SSID} & {@link WifiConfiguration#BSSID} fields from
     * WifiConfiguration are not used. Instead we use the {@link #ssidPatternMatcher} &
     * {@link #bssidPatternMatcher} fields embedded directly
     * within {@link WifiNetworkSpecifier}.
     */
    public final WifiConfiguration wifiConfiguration;

    /**
     * The UID of the process initializing this network specifier. Validated by receiver using
     * checkUidIfNecessary() and is used by satisfiedBy() to determine whether the specifier
     * matches the offered network.
     */
    public final int requestorUid;

    public WifiNetworkSpecifier(@NonNull PatternMatcher ssidPatternMatcher,
                 @NonNull Pair<MacAddress, MacAddress> bssidPatternMatcher,
                 @NonNull WifiConfiguration wifiConfiguration,
                 int requestorUid) {
        checkNotNull(ssidPatternMatcher);
        checkNotNull(bssidPatternMatcher);
        checkNotNull(wifiConfiguration);

        this.ssidPatternMatcher = ssidPatternMatcher;
        this.bssidPatternMatcher = bssidPatternMatcher;
        this.wifiConfiguration = wifiConfiguration;
        this.requestorUid = requestorUid;
    }

    public static final Creator<WifiNetworkSpecifier> CREATOR =
            new Creator<WifiNetworkSpecifier>() {
                @Override
                public WifiNetworkSpecifier createFromParcel(Parcel in) {
                    PatternMatcher ssidPatternMatcher = in.readParcelable(/* classLoader */null);
                    MacAddress baseAddress = in.readParcelable(null);
                    MacAddress mask = in.readParcelable(null);
                    Pair<MacAddress, MacAddress> bssidPatternMatcher =
                            Pair.create(baseAddress, mask);
                    WifiConfiguration wifiConfiguration = in.readParcelable(null);
                    int requestorUid = in.readInt();
                    return new WifiNetworkSpecifier(ssidPatternMatcher, bssidPatternMatcher,
                            wifiConfiguration, requestorUid);
                }

                @Override
                public WifiNetworkSpecifier[] newArray(int size) {
                    return new WifiNetworkSpecifier[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(ssidPatternMatcher, flags);
        dest.writeParcelable(bssidPatternMatcher.first, flags);
        dest.writeParcelable(bssidPatternMatcher.second, flags);
        dest.writeParcelable(wifiConfiguration, flags);
        dest.writeInt(requestorUid);
    }

    @Override
    public boolean satisfiedBy(NetworkSpecifier other) {
        if (this == other) {
            return true;
        }
        // Any generic requests should be satisifed by a specific wifi network.
        if (other == null || other instanceof MatchAllNetworkSpecifier) {
            return true;
        }
        if (other instanceof WifiNetworkAgentSpecifier) {
            return ((WifiNetworkAgentSpecifier) other).satisfiesNetworkSpecifier(this);
        }
        // Specific requests are checked for equality although testing for equality of 2 patterns do
        // not make much sense!
        return equals(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                ssidPatternMatcher.getPath(),
                ssidPatternMatcher.getType(),
                bssidPatternMatcher,
                wifiConfiguration.allowedKeyManagement,
                requestorUid);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WifiNetworkSpecifier)) {
            return false;
        }
        WifiNetworkSpecifier lhs = (WifiNetworkSpecifier) obj;
        return Objects.equals(this.ssidPatternMatcher.getPath(),
                    lhs.ssidPatternMatcher.getPath())
                && Objects.equals(this.ssidPatternMatcher.getType(),
                    lhs.ssidPatternMatcher.getType())
                && Objects.equals(this.bssidPatternMatcher,
                    lhs.bssidPatternMatcher)
                && Objects.equals(this.wifiConfiguration.allowedKeyManagement,
                    lhs.wifiConfiguration.allowedKeyManagement)
                && requestorUid == lhs.requestorUid;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("WifiNetworkSpecifier [")
                .append(", SSID Match pattern=").append(ssidPatternMatcher)
                .append(", BSSID Match pattern=").append(bssidPatternMatcher)
                .append(", SSID=").append(wifiConfiguration.SSID)
                .append(", BSSID=").append(wifiConfiguration.BSSID)
                .append(", requestorUid=").append(requestorUid)
                .append("]")
                .toString();
    }

    @Override
    public void assertValidFromUid(int requestorUid) {
        if (this.requestorUid != requestorUid) {
            throw new SecurityException("mismatched UIDs");
        }
    }
}
