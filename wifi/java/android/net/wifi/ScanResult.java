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

import android.net.wifi.passpoint.WifiPasspointInfo;
import android.net.wifi.passpoint.WifiPasspointManager;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes information about a detected access point. In addition
 * to the attributes described here, the supplicant keeps track of
 * {@code quality}, {@code noise}, and {@code maxbitrate} attributes,
 * but does not currently report them to external clients.
 */
public class ScanResult implements Parcelable {
    /**
     * The network name.
     */
    public String SSID;

    /**
     * Ascii encoded SSID. This will replace SSID when we deprecate it. @hide
     */
    public WifiSsid wifiSsid;

    /**
     * The address of the access point.
     */
    public String BSSID;
    /**
     * Describes the authentication, key management, and encryption schemes
     * supported by the access point.
     */
    public String capabilities;
    /**
     * The detected signal level in dBm, also known as the RSSI.
     *
     * <p>Use {@link android.net.wifi.WifiManager#calculateSignalLevel} to convert this number into
     * an absolute signal level which can be displayed to a user.
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

    /**
     * Timestamp representing date when this result was last seen, in milliseconds from 1970
     * {@hide}
     */
    public long seen;

    /**
     * @hide
     * Update RSSI of the scan result
     * @param previousRSSI
     * @param previousSeen
     * @param maxAge
     */
    public void averageRssi(int previousRssi, long previousSeen, int maxAge) {

        if (seen == 0) {
            seen = System.currentTimeMillis();
        }
        long age = seen - previousSeen;

        if (previousSeen > 0 && age > 0 && age < maxAge/2) {
            // Average the RSSI with previously seen instances of this scan result
            double alpha = 0.5 - (double) age / (double) maxAge;
            level = (int) ((double) level * (1 - alpha) + (double) previousRssi * alpha);
        }
    }

    /** @hide */
    public static final int ENABLED                                          = 0;
    /** @hide */
    public static final int AUTO_ROAM_DISABLED                               = 16;
    /** @hide */
    public static final int AUTO_JOIN_DISABLED                               = 32;
    /** @hide */
    public static final int AUTHENTICATION_ERROR                              = 128;

    /**
     * Status: indicating join status
     * @hide
     */
    public int status;

    /**
     * Status: indicating the scan result is not a result
     * that is part of user's saved configurations
     * @hide
     */
    public boolean untrusted;

    /**
     * Number of time we connected to it
     * @hide
     */
    public int numConnection;

    /**
     * Number of time autojoin used it
     * @hide
     */
    public int numUsage;

    /**
     * The approximate distance to the AP in centimeter, if available.  Else
     * {@link UNSPECIFIED}.
     * {@hide}
     */
    public int distanceCm;

    /**
     * The standard deviation of the distance to the AP, if available.
     * Else {@link UNSPECIFIED}.
     * {@hide}
     */
    public int distanceSdCm;

    /**
     * Passpoint ANQP information. This is not fetched automatically.
     * Use {@link WifiPasspointManager#requestAnqpInfo} to request ANQP info.
     */
    public WifiPasspointInfo passpoint;

    /**
     * {@hide}
     */
    public final static int UNSPECIFIED = -1;
    /**
     * @hide
     */
    public boolean is24GHz() {
        return ScanResult.is24GHz(frequency);
    }

    /**
     * @hide
     * TODO: makes real freq boundaries
     */
    public static boolean is24GHz(int freq) {
        return freq > 2400 && freq < 2500;
    }

    /**
     * @hide
     */
    public boolean is5GHz() {
        return ScanResult.is5GHz(frequency);
    }

    /**
     * @hide
     * TODO: makes real freq boundaries
     */
    public static boolean is5GHz(int freq) {
        return freq > 4900 && freq < 5900;
    }

    /** information element from beacon
     * @hide
     */
    public static class InformationElement {
        public int id;
        public byte[] bytes;

        public InformationElement() {
        }

        public InformationElement(InformationElement rhs) {
            this.id = rhs.id;
            this.bytes = rhs.bytes.clone();
        }
    }

    /** information elements found in the beacon
     * @hide
     */
    public InformationElement informationElements[];

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
        this.distanceCm = UNSPECIFIED;
        this.distanceSdCm = UNSPECIFIED;
    }

    /** {@hide} */
    public ScanResult(WifiSsid wifiSsid, String BSSID, String caps, int level, int frequency,
            long tsf, int distCm, int distSdCm) {
        this.wifiSsid = wifiSsid;
        this.SSID = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
        this.BSSID = BSSID;
        this.capabilities = caps;
        this.level = level;
        this.frequency = frequency;
        this.timestamp = tsf;
        this.distanceCm = distCm;
        this.distanceSdCm = distSdCm;
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
            distanceCm = source.distanceCm;
            distanceSdCm = source.distanceSdCm;
            seen = source.seen;
            passpoint = source.passpoint;
            status = source.status;
            untrusted = source.untrusted;
            numConnection = source.numConnection;
            numUsage = source.numUsage;
        }
    }

    /** empty scan result
     *
     * {@hide}
     * */
    public ScanResult() {
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

        sb.append(", distance: ").append((distanceCm != UNSPECIFIED ? distanceCm : "?")).
                append("(cm)");
        sb.append(", distanceSd: ").append((distanceSdCm != UNSPECIFIED ? distanceSdCm : "?")).
                append("(cm)");

        sb.append(", passpoint: ").append(passpoint != null ? "yes" : "no");
        if (status != 0) {
            sb.append(", status: ").append(status);
        }
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
        dest.writeInt(distanceCm);
        dest.writeInt(distanceSdCm);
        dest.writeLong(seen);
        dest.writeInt(status);
        dest.writeInt(untrusted ? 1 : 0);
        dest.writeInt(numConnection);
        dest.writeInt(numUsage);
        if (passpoint != null) {
            dest.writeInt(1);
            passpoint.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (informationElements != null) {
            dest.writeInt(informationElements.length);
            for (int i = 0; i < informationElements.length; i++) {
                dest.writeInt(informationElements[i].id);
                dest.writeInt(informationElements[i].bytes.length);
                dest.writeByteArray(informationElements[i].bytes);
            }
        } else {
            dest.writeInt(0);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<ScanResult> CREATOR =
        new Creator<ScanResult>() {
            public ScanResult createFromParcel(Parcel in) {
                WifiSsid wifiSsid = null;
                if (in.readInt() == 1) {
                    wifiSsid = WifiSsid.CREATOR.createFromParcel(in);
                }
                ScanResult sr = new ScanResult(
                    wifiSsid,
                    in.readString(),
                    in.readString(),
                    in.readInt(),
                    in.readInt(),
                    in.readLong(),
                    in.readInt(),
                    in.readInt()
                );
                sr.seen = in.readLong();
                sr.status = in.readInt();
                sr.untrusted = in.readInt() != 0;
                sr.numConnection = in.readInt();
                sr.numUsage = in.readInt();
                if (in.readInt() == 1) {
                    sr.passpoint = WifiPasspointInfo.CREATOR.createFromParcel(in);
                }
                int n = in.readInt();
                if (n != 0) {
                    sr.informationElements = new InformationElement[n];
                    for (int i = 0; i < n; i++) {
                        sr.informationElements[i] = new InformationElement();
                        sr.informationElements[i].id = in.readInt();
                        int len = in.readInt();
                        sr.informationElements[i].bytes = new byte[len];
                        in.readByteArray(sr.informationElements[i].bytes);
                    }
                }
                return sr;
            }

            public ScanResult[] newArray(int size) {
                return new ScanResult[size];
            }
        };
}
