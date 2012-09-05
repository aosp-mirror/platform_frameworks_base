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

import android.os.Parcelable;
import android.os.Parcel;

/**
 * Describes information about a detected access point. In addition
 * to the attributes described here, the supplicant keeps track of
 * {@code quality}, {@code noise}, and {@code maxbitrate} attributes,
 * but does not currently report them to external clients.
 */
public class ScanResult implements Parcelable {
    /** The network name. */
    public String SSID;

    /** Ascii encoded SSID. This will replace SSID when we deprecate it. @hide */
    public WifiSsid wifiSsid;

    /** The address of the access point. */
    public String BSSID;
    /**
     * Describes the authentication, key management, and encryption schemes
     * supported by the access point.
     */
    public String capabilities;
    /**
     * The detected signal level in dBm. At least those are the units used by
     * the TI driver.
     */
    public int level;
    /**
     * The frequency in MHz of the channel over which the client is communicating
     * with the access point.
     */
    public int frequency;

    /**
     * Time Synchronization Function (tsf) timestamp in microseconds when
     * this result was last seen.
     */
     public long timestamp;

    /** {@hide} */
    public ScanResult(WifiSsid wifiSsid, String BSSID, String caps, int level, int frequency,
            long tsf) {
        this.wifiSsid = wifiSsid;
        this.SSID = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
        this.BSSID = BSSID;
        this.capabilities = caps;
        this.level = level;
        this.frequency = frequency;
        this.timestamp = tsf;
    }


    /** copy constructor {@hide} */
    public ScanResult(ScanResult source) {
        if (source != null) {
            wifiSsid = source.wifiSsid;
            SSID = source.SSID;
            BSSID = source.BSSID;
            capabilities = source.capabilities;
            level = source.level;
            frequency = source.frequency;
            timestamp = source.timestamp;
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String none = "<none>";

        sb.append("SSID: ").
            append(wifiSsid == null ? WifiSsid.NONE : wifiSsid).
            append(", BSSID: ").
            append(BSSID == null ? none : BSSID).
            append(", capabilities: ").
            append(capabilities == null ? none : capabilities).
            append(", level: ").
            append(level).
            append(", frequency: ").
            append(frequency).
            append(", timestamp: ").
            append(timestamp);

        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        if (wifiSsid != null) {
            dest.writeInt(1);
            wifiSsid.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(BSSID);
        dest.writeString(capabilities);
        dest.writeInt(level);
        dest.writeInt(frequency);
        dest.writeLong(timestamp);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<ScanResult> CREATOR =
        new Creator<ScanResult>() {
            public ScanResult createFromParcel(Parcel in) {
                WifiSsid wifiSsid = null;
                if (in.readInt() == 1) {
                    wifiSsid = WifiSsid.CREATOR.createFromParcel(in);
                }
                return new ScanResult(
                    wifiSsid,
                    in.readString(),
                    in.readString(),
                    in.readInt(),
                    in.readInt(),
                    in.readLong()
                );
            }

            public ScanResult[] newArray(int size) {
                return new ScanResult[size];
            }
        };

}
