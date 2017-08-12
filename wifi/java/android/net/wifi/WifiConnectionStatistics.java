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

import android.annotation.SystemApi;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.HashMap;

/**
 * Wifi Connection Statistics: gather various stats regarding WiFi connections,
 * connection requests, auto-join
 * and WiFi usage.
 * @hide
 * @removed
 */
@SystemApi
public class WifiConnectionStatistics implements Parcelable {
    private static final String TAG = "WifiConnnectionStatistics";

    /**
     *  history of past connection to untrusted SSID
     *  Key = SSID
     *  Value = num connection
     */
    public HashMap<String, WifiNetworkConnectionStatistics> untrustedNetworkHistory;

    // Number of time we polled the chip and were on 5GHz
    public int num5GhzConnected;

    // Number of time we polled the chip and were on 2.4GHz
    public int num24GhzConnected;

    // Number autojoin attempts
    public int numAutoJoinAttempt;

    // Number auto-roam attempts
    public int numAutoRoamAttempt;

    // Number wifimanager join attempts
    public int numWifiManagerJoinAttempt;

    public WifiConnectionStatistics() {
        untrustedNetworkHistory = new HashMap<String, WifiNetworkConnectionStatistics>();
    }

    public void incrementOrAddUntrusted(String SSID, int connection, int usage) {
        WifiNetworkConnectionStatistics stats;
        if (TextUtils.isEmpty(SSID))
            return;
        if (untrustedNetworkHistory.containsKey(SSID)) {
            stats = untrustedNetworkHistory.get(SSID);
            if (stats != null){
                stats.numConnection = connection + stats.numConnection;
                stats.numUsage = usage + stats.numUsage;
            }
        } else {
            stats = new WifiNetworkConnectionStatistics(connection, usage);
        }
        if (stats != null) {
            untrustedNetworkHistory.put(SSID, stats);
        }
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("Connected on: 2.4Ghz=").append(num24GhzConnected);
        sbuf.append(" 5Ghz=").append(num5GhzConnected).append("\n");
        sbuf.append(" join=").append(numWifiManagerJoinAttempt);
        sbuf.append("\\").append(numAutoJoinAttempt).append("\n");
        sbuf.append(" roam=").append(numAutoRoamAttempt).append("\n");

        for (String Key : untrustedNetworkHistory.keySet()) {
            WifiNetworkConnectionStatistics stats = untrustedNetworkHistory.get(Key);
            if (stats != null) {
                sbuf.append(Key).append(" ").append(stats.toString()).append("\n");
            }
        }
        return sbuf.toString();
    }

    /** copy constructor*/
    public WifiConnectionStatistics(WifiConnectionStatistics source) {
        untrustedNetworkHistory = new HashMap<String, WifiNetworkConnectionStatistics>();
        if (source != null) {
            untrustedNetworkHistory.putAll(source.untrustedNetworkHistory);
        }
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(num24GhzConnected);
        dest.writeInt(num5GhzConnected);
        dest.writeInt(numAutoJoinAttempt);
        dest.writeInt(numAutoRoamAttempt);
        dest.writeInt(numWifiManagerJoinAttempt);

        dest.writeInt(untrustedNetworkHistory.size());
        for (String Key : untrustedNetworkHistory.keySet()) {
            WifiNetworkConnectionStatistics num = untrustedNetworkHistory.get(Key);
            dest.writeString(Key);
            dest.writeInt(num.numConnection);
            dest.writeInt(num.numUsage);

        }
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiConnectionStatistics> CREATOR =
        new Creator<WifiConnectionStatistics>() {
            public WifiConnectionStatistics createFromParcel(Parcel in) {
                WifiConnectionStatistics stats = new WifiConnectionStatistics();
                stats.num24GhzConnected = in.readInt();
                stats.num5GhzConnected = in.readInt();
                stats.numAutoJoinAttempt = in.readInt();
                stats.numAutoRoamAttempt = in.readInt();
                stats.numWifiManagerJoinAttempt = in.readInt();
                int n = in.readInt();
                while (n-- > 0) {
                    String Key = in.readString();
                    int numConnection = in.readInt();
                    int numUsage = in.readInt();
                    WifiNetworkConnectionStatistics st =
                            new WifiNetworkConnectionStatistics(numConnection, numUsage);
                    stats.untrustedNetworkHistory.put(Key, st);
                }
                return stats;
            }

            public WifiConnectionStatistics[] newArray(int size) {
                return new WifiConnectionStatistics[size];
            }
        };
}
