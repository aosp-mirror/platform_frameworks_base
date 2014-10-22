/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Information identifying a Wi-Fi network.
 * @see NetworkKey
 *
 * @hide
 */
@SystemApi
public class WifiKey implements Parcelable {

    // Patterns used for validation.
    private static final Pattern SSID_PATTERN = Pattern.compile("(\".*\")|(0x[\\p{XDigit}]+)");
    private static final Pattern BSSID_PATTERN =
            Pattern.compile("([\\p{XDigit}]{2}:){5}[\\p{XDigit}]{2}");

    /**
     * The service set identifier (SSID) of an 802.11 network. If the SSID can be decoded as
     * UTF-8, it will be surrounded by double quotation marks. Otherwise, it will be a string of
     * hex digits starting with 0x.
     */
    public final String ssid;

    /**
     * The basic service set identifier (BSSID) of an access point for this network. This will
     * be in the form of a six-byte MAC address: {@code XX:XX:XX:XX:XX:XX}, where each X is a
     * hexadecimal digit.
     */
    public final String bssid;

    /**
     * Construct a new {@link WifiKey} for the given Wi-Fi SSID/BSSID pair.
     *
     * @param ssid the service set identifier (SSID) of an 802.11 network. If the SSID can be
     *         decoded as UTF-8, it should be surrounded by double quotation marks. Otherwise,
     *         it should be a string of hex digits starting with 0x.
     * @param bssid the basic service set identifier (BSSID) of this network's access point.
     *         This should be in the form of a six-byte MAC address: {@code XX:XX:XX:XX:XX:XX},
     *         where each X is a hexadecimal digit.
     * @throws IllegalArgumentException if either the SSID or BSSID is invalid.
     */
    public WifiKey(String ssid, String bssid) {
        if (!SSID_PATTERN.matcher(ssid).matches()) {
            throw new IllegalArgumentException("Invalid ssid: " + ssid);
        }
        if (!BSSID_PATTERN.matcher(bssid).matches()) {
            throw new IllegalArgumentException("Invalid bssid: " + bssid);
        }
        this.ssid = ssid;
        this.bssid = bssid;
    }

    private WifiKey(Parcel in) {
        ssid = in.readString();
        bssid = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(ssid);
        out.writeString(bssid);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WifiKey wifiKey = (WifiKey) o;

        return Objects.equals(ssid, wifiKey.ssid) && Objects.equals(bssid, wifiKey.bssid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ssid, bssid);
    }

    @Override
    public String toString() {
        return "WifiKey[SSID=" + ssid + ",BSSID=" + bssid + "]";
    }

    public static final Creator<WifiKey> CREATOR =
            new Creator<WifiKey>() {
                @Override
                public WifiKey createFromParcel(Parcel in) {
                    return new WifiKey(in);
                }

                @Override
                public WifiKey[] newArray(int size) {
                    return new WifiKey[size];
                }
            };
}
