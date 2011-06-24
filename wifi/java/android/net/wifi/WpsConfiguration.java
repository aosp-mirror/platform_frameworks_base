/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.net.LinkProperties;
import android.net.wifi.WifiConfiguration.IpAssignment;
import android.net.wifi.WifiConfiguration.ProxySettings;
import android.os.Parcelable;
import android.os.Parcel;

import java.util.BitSet;

/**
 * A class representing a WPS network configuration
 * @hide
 */
public class WpsConfiguration implements Parcelable {

    /* Wi-Fi Protected Setup. www.wi-fi.org/wifi-protected-setup has details */
    public enum Setup {
        /* Push button configuration */
        PBC,
        /* Display pin method configuration - pin is generated and displayed on device */
        DISPLAY,
        /* Keypad pin method configuration - pin is entered on device */
        KEYPAD,
        /* Label pin method configuration - pin is obtained from a printed label */
        LABEL,
        /* Invalid config */
        INVALID
    }

    public Setup setup;

    public String BSSID;

    public String pin;

    public IpAssignment ipAssignment;

    public ProxySettings proxySettings;

    public LinkProperties linkProperties;

    public WpsConfiguration() {
        setup = Setup.INVALID;
        BSSID = null;
        pin = null;
        ipAssignment = IpAssignment.UNASSIGNED;
        proxySettings = ProxySettings.UNASSIGNED;
        linkProperties = new LinkProperties();
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append(" setup: ").append(setup.toString());
        sbuf.append('\n');
        sbuf.append(" BSSID: ").append(BSSID);
        sbuf.append('\n');
        sbuf.append(" pin: ").append(pin);
        sbuf.append('\n');
        sbuf.append("IP assignment: " + ipAssignment.toString());
        sbuf.append("\n");
        sbuf.append("Proxy settings: " + proxySettings.toString());
        sbuf.append("\n");
        sbuf.append(linkProperties.toString());
        sbuf.append("\n");
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    public WpsConfiguration(WpsConfiguration source) {
        if (source != null) {
            setup = source.setup;
            BSSID = source.BSSID;
            pin = source.pin;
            ipAssignment = source.ipAssignment;
            proxySettings = source.proxySettings;
            linkProperties = new LinkProperties(source.linkProperties);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(setup.name());
        dest.writeString(BSSID);
        dest.writeString(pin);
        dest.writeString(ipAssignment.name());
        dest.writeString(proxySettings.name());
        dest.writeParcelable(linkProperties, flags);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WpsConfiguration> CREATOR =
        new Creator<WpsConfiguration>() {
            public WpsConfiguration createFromParcel(Parcel in) {
                WpsConfiguration config = new WpsConfiguration();
                config.setup = Setup.valueOf(in.readString());
                config.BSSID = in.readString();
                config.pin = in.readString();
                config.ipAssignment = IpAssignment.valueOf(in.readString());
                config.proxySettings = ProxySettings.valueOf(in.readString());
                config.linkProperties = in.readParcelable(null);
                return config;
            }

            public WpsConfiguration[] newArray(int size) {
                return new WpsConfiguration[size];
            }
        };
}
