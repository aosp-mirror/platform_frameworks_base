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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;
import java.util.Objects;

/**
 * The Network Suggestion object is used to provide a Wi-Fi network for consideration when
 * auto-connecting to networks. Apps cannot directly create this object, they must use
 * {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} to obtain an instance
 * of this object.
 *<p>
 * Apps can provide a list of such networks to the platform using
 * {@link WifiManager#addNetworkSuggestions(List)}.
 */
public final class WifiNetworkSuggestion implements Parcelable {
    /**
     * Network configuration for the provided network.
     * @hide
     */
    public final WifiConfiguration wifiConfiguration;

    /**
     * Whether app needs to log in to captive portal to obtain Internet access.
     * @hide
     */
    public final boolean isAppInteractionRequired;

    /**
     * Whether user needs to log in to captive portal to obtain Internet access.
     * @hide
     */
    public final boolean isUserInteractionRequired;

    /**
     * The UID of the process initializing this network suggestion.
     * @hide
     */
    public final int suggestorUid;

    /** @hide */
    public WifiNetworkSuggestion(WifiConfiguration wifiConfiguration,
                                 boolean isAppInteractionRequired,
                                 boolean isUserInteractionRequired,
                                 int suggestorUid) {
        checkNotNull(wifiConfiguration);

        this.wifiConfiguration = wifiConfiguration;
        this.isAppInteractionRequired = isAppInteractionRequired;
        this.isUserInteractionRequired = isUserInteractionRequired;
        this.suggestorUid = suggestorUid;
    }

    public static final Creator<WifiNetworkSuggestion> CREATOR =
            new Creator<WifiNetworkSuggestion>() {
                @Override
                public WifiNetworkSuggestion createFromParcel(Parcel in) {
                    return new WifiNetworkSuggestion(
                            in.readParcelable(null), // wifiConfiguration
                            in.readBoolean(), // isAppInteractionRequired
                            in.readBoolean(), // isUserInteractionRequired
                            in.readInt() // suggestorUid
                    );
                }

                @Override
                public WifiNetworkSuggestion[] newArray(int size) {
                    return new WifiNetworkSuggestion[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(wifiConfiguration, flags);
        dest.writeBoolean(isAppInteractionRequired);
        dest.writeBoolean(isUserInteractionRequired);
        dest.writeInt(suggestorUid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wifiConfiguration.SSID, wifiConfiguration.BSSID,
                wifiConfiguration.allowedKeyManagement, suggestorUid);
    }

    /**
     * Equals for network suggestions.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WifiNetworkSuggestion)) {
            return false;
        }
        WifiNetworkSuggestion lhs = (WifiNetworkSuggestion) obj;
        return Objects.equals(this.wifiConfiguration.SSID, lhs.wifiConfiguration.SSID)
                && Objects.equals(this.wifiConfiguration.BSSID, lhs.wifiConfiguration.BSSID)
                && Objects.equals(this.wifiConfiguration.allowedKeyManagement,
                                  lhs.wifiConfiguration.allowedKeyManagement)
                && suggestorUid == lhs.suggestorUid;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WifiNetworkSuggestion [")
                .append(", SSID=").append(wifiConfiguration.SSID)
                .append(", BSSID=").append(wifiConfiguration.BSSID)
                .append(", isAppInteractionRequired=").append(isAppInteractionRequired)
                .append(", isUserInteractionRequired=").append(isUserInteractionRequired)
                .append(", suggestorUid=").append(suggestorUid)
                .append("]");
        return sb.toString();
    }
}
