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

import java.util.HashMap;

/**
 * Connection Statistics For a WiFi Network.
 * @hide
 */
@SystemApi
public class WifiNetworkConnectionStatistics implements Parcelable {
    private static final String TAG = "WifiNetworkConnnectionStatistics";

    public int numConnection;
    public int numUsage;

    public WifiNetworkConnectionStatistics(int connection, int usage) {
        numConnection = connection;
        numUsage = usage;
    }

    public WifiNetworkConnectionStatistics() { }


    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("c=").append(numConnection);
        sbuf.append(" u=").append(numUsage);
        return sbuf.toString();
    }


    /** copy constructor*/
    public WifiNetworkConnectionStatistics(WifiNetworkConnectionStatistics source) {
        numConnection = source.numConnection;
        numUsage = source.numUsage;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(numConnection);
        dest.writeInt(numUsage);
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiNetworkConnectionStatistics> CREATOR =
        new Creator<WifiNetworkConnectionStatistics>() {
            public WifiNetworkConnectionStatistics createFromParcel(Parcel in) {
                int numConnection = in.readInt();
                int numUsage = in.readInt();
                WifiNetworkConnectionStatistics stats =
                        new WifiNetworkConnectionStatistics(numConnection, numUsage);
                return stats;
            }

            public WifiNetworkConnectionStatistics[] newArray(int size) {
                return new WifiNetworkConnectionStatistics[size];
            }
        };
}
