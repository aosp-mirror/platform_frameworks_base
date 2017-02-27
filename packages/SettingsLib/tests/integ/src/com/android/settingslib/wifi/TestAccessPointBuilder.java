/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.wifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.os.Bundle;

/**
* Build and return a valid AccessPoint.
*
* Only intended for testing the AccessPoint class;
* AccessPoints were designed to only be populated
* by the mechanisms of scan results and wifi configurations.
*/
public class TestAccessPointBuilder {
    // match the private values in WifiManager
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -55;

    // set some sensible defaults
    private int mRssi = AccessPoint.UNREACHABLE_RSSI;
    private int networkId = WifiConfiguration.INVALID_NETWORK_ID;
    private String ssid = "TestSsid";
    private NetworkInfo mNetworkInfo = null;

    Context mContext;

    public TestAccessPointBuilder(Context context) {
        mContext = context;
    }

    public AccessPoint build() {
        Bundle bundle = new Bundle();

        WifiConfiguration wifiConig = new WifiConfiguration();
        wifiConig.networkId = networkId;

        bundle.putString(AccessPoint.KEY_SSID, ssid);
        bundle.putParcelable(AccessPoint.KEY_CONFIG, wifiConig);
        bundle.putParcelable(AccessPoint.KEY_NETWORKINFO, mNetworkInfo);
        AccessPoint ap = new AccessPoint(mContext, bundle);
        ap.setRssi(mRssi);
        return ap;
    }

    public TestAccessPointBuilder setActive(boolean active) {
        if (active) {
            mNetworkInfo = new NetworkInfo(
                ConnectivityManager.TYPE_DUMMY,
                ConnectivityManager.TYPE_DUMMY,
                "TestNetwork",
                "TestNetwork");
        } else {
            mNetworkInfo = null;
        }
        return this;
    }

    /**
    * Set the signal level.
    * Side effect: if this AccessPoint was previously unreachable,
    * setting the level will also make it reachable.
    */
    public TestAccessPointBuilder setLevel(int level) {
        int outputRange = AccessPoint.SIGNAL_LEVELS - 1;

        if (level > outputRange) {
            level = outputRange;
        } else if (level < 0) {
            level = 0;
        }

        int inputRange = MAX_RSSI - MIN_RSSI;

        // calculate the rssi required to get the level we want.
        // this is a rearrangement of the formula from WifiManager.calculateSignalLevel()
        mRssi = (int)((float)(level * inputRange) / (float)outputRange) + MIN_RSSI;
        return this;
    }

    /**
    * Set whether the AccessPoint is reachable.
    * Side effect: if the signal level was not previously set,
    * making an AccessPoint reachable will set the signal to the minimum level.
    */
    public TestAccessPointBuilder setReachable(boolean reachable) {
        if (reachable) {
            // only override the mRssi if it hasn't been set yet
            if (mRssi == AccessPoint.UNREACHABLE_RSSI) {
                mRssi = MIN_RSSI;
            }
        } else {
            mRssi = AccessPoint.UNREACHABLE_RSSI;
        }
        return this;
    }

    public TestAccessPointBuilder setSaved(boolean saved){
        if (saved) {
             networkId = 1;
        } else {
             networkId = WifiConfiguration.INVALID_NETWORK_ID;
        }
        return this;
    }

    public TestAccessPointBuilder setSsid(String newSsid) {
        ssid = newSsid;
        return this;
    }
}
