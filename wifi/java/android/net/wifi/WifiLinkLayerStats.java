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
 * limitations under the License.
 */

package android.net.wifi;

import android.os.Parcelable;
import android.os.Parcel;

/**
 * A class representing link layer statistics collected over a Wifi Interface.
 */
/** {@hide} */
public class WifiLinkLayerStats implements Parcelable {
    private static final String TAG = "WifiLinkLayerStats";

    /**
     * The current status of this network configuration entry.
     * @see Status
     */
    /** {@hide} */
    public int status;

    /**
     * The network's SSID. Can either be an ASCII string,
     * which must be enclosed in double quotation marks
     * (e.g., {@code "MyNetwork"}, or a string of
     * hex digits,which are not enclosed in quotes
     * (e.g., {@code 01a243f405}).
     */
    /** {@hide} */
    public String SSID;
    /**
     * When set. this is the BSSID the radio is currently associated with.
     * The value is a string in the format of an Ethernet MAC address, e.g.,
     * <code>XX:XX:XX:XX:XX:XX</code> where each <code>X</code> is a hex digit.
     */
    /** {@hide} */
    public String BSSID;

    /* number beacons received from our own AP */
    /** {@hide} */
    public int beacon_rx;

    /* RSSI taken on management frames */
    /** {@hide} */
    public int rssi_mgmt;

    /* packets counters */
    /** {@hide} */
    /* WME Best Effort Access Category (receive mpdu, transmit mpdu, lost mpdu, number of retries)*/
    public long rxmpdu_be;
    /** {@hide} */
    public long txmpdu_be;
    /** {@hide} */
    public long lostmpdu_be;
    /** {@hide} */
    public long retries_be;
    /** {@hide} */
    /* WME Background Access Category (receive mpdu, transmit mpdu, lost mpdu, number of retries) */
    public long rxmpdu_bk;
    /** {@hide} */
    public long txmpdu_bk;
    /** {@hide} */
    public long lostmpdu_bk;
    /** {@hide} */
    public long retries_bk;
    /** {@hide} */
    /* WME Video Access Category (receive mpdu, transmit mpdu, lost mpdu, number of retries) */
    public long rxmpdu_vi;
    /** {@hide} */
    public long txmpdu_vi;
    /** {@hide} */
    public long lostmpdu_vi;
    /** {@hide} */
    public long retries_vi;
    /** {@hide} */
    /* WME Voice Access Category (receive mpdu, transmit mpdu, lost mpdu, number of retries) */
    public long rxmpdu_vo;
    /** {@hide} */
    public long txmpdu_vo;
    /** {@hide} */
    public long lostmpdu_vo;
    /** {@hide} */
    public long retries_vo;

    /** {@hide} */
    public int on_time;
    /** {@hide} */
    public int tx_time;
    /** {@hide} */
    public int rx_time;
    /** {@hide} */
    public int on_time_scan;

    /** {@hide} */
    public WifiLinkLayerStats() {
    }

    @Override
    /** {@hide} */
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append(" WifiLinkLayerStats: ").append('\n');

        if (this.SSID != null) {
            sbuf.append(" SSID: ").append(this.SSID).append('\n');
        }
        if (this.BSSID != null) {
            sbuf.append(" BSSID: ").append(this.BSSID).append('\n');
        }

        sbuf.append(" my bss beacon rx: ").append(Integer.toString(this.beacon_rx)).append('\n');
        sbuf.append(" RSSI mgmt: ").append(Integer.toString(this.rssi_mgmt)).append('\n');
        sbuf.append(" BE : ").append(" rx=").append(Long.toString(this.rxmpdu_be))
                .append(" tx=").append(Long.toString(this.txmpdu_be))
                .append(" lost=").append(Long.toString(this.lostmpdu_be))
                .append(" retries=").append(Long.toString(this.retries_be)).append('\n');
        sbuf.append(" BK : ").append(" rx=").append(Long.toString(this.rxmpdu_bk))
                .append(" tx=").append(Long.toString(this.txmpdu_bk))
                .append(" lost=").append(Long.toString(this.lostmpdu_bk))
                .append(" retries=").append(Long.toString(this.retries_bk)).append('\n');
        sbuf.append(" VI : ").append(" rx=").append(Long.toString(this.rxmpdu_vi))
                .append(" tx=").append(Long.toString(this.txmpdu_vi))
                .append(" lost=").append(Long.toString(this.lostmpdu_vi))
                .append(" retries=").append(Long.toString(this.retries_vi)).append('\n');
        sbuf.append(" VO : ").append(" rx=").append(Long.toString(this.rxmpdu_vo))
                .append(" tx=").append(Long.toString(this.txmpdu_vo))
                .append(" lost=").append(Long.toString(this.lostmpdu_vo))
                .append(" retries=").append(Long.toString(this.retries_vo)).append('\n');
        sbuf.append(" on_time : ").append(Integer.toString(this.on_time))
                .append(" tx_time=").append(Integer.toString(this.tx_time))
                .append(" rx_time=").append(Integer.toString(this.rx_time))
                .append(" scan_time=").append(Integer.toString(this.on_time_scan)).append('\n');
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** {@hide} */
    public String getPrintableSsid() {
        if (SSID == null) return "";
        final int length = SSID.length();
        if (length > 2 && (SSID.charAt(0) == '"') && SSID.charAt(length - 1) == '"') {
            return SSID.substring(1, length - 1);
        }

        /** The ascii-encoded string format is P"<ascii-encoded-string>"
         * The decoding is implemented in the supplicant for a newly configured
         * network.
         */
        if (length > 3 && (SSID.charAt(0) == 'P') && (SSID.charAt(1) == '"') &&
                (SSID.charAt(length-1) == '"')) {
            WifiSsid wifiSsid = WifiSsid.createFromAsciiEncoded(
                    SSID.substring(2, length - 1));
            return wifiSsid.toString();
        }
        return SSID;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(SSID);
        dest.writeString(BSSID);
        dest.writeInt(on_time);
        dest.writeInt(tx_time);
        dest.writeInt(rx_time);
        dest.writeInt(on_time_scan);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiLinkLayerStats> CREATOR =
        new Creator<WifiLinkLayerStats>() {
            public WifiLinkLayerStats createFromParcel(Parcel in) {
                WifiLinkLayerStats stats = new WifiLinkLayerStats();
                stats.SSID = in.readString();
                stats.BSSID = in.readString();
                stats.on_time = in.readInt();
                stats.tx_time = in.readInt();
                stats.rx_time = in.readInt();
                stats.on_time_scan = in.readInt();
                return stats;
            };
            public WifiLinkLayerStats[] newArray(int size) {
                return new WifiLinkLayerStats[size];
            }

        };
}
