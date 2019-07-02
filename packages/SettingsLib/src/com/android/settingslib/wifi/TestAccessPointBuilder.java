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
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.annotation.Keep;

import com.android.settingslib.wifi.AccessPoint.Speed;

import java.util.ArrayList;

/**
* Build and return a valid AccessPoint.
*
* Only intended for testing the AccessPoint class or creating Access points to be used in testing
* applications. AccessPoints were designed to only be populated by the mechanisms of scan results
* and wifi configurations.
*/
@Keep
public class TestAccessPointBuilder {
    // match the private values in WifiManager
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -55;

    // set some sensible defaults
    private String mBssid = null;
    private int mSpeed = Speed.NONE;
    private int mRssi = AccessPoint.UNREACHABLE_RSSI;
    private int mNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    private String ssid = "TestSsid";
    private NetworkInfo mNetworkInfo = null;
    private String mFqdn = null;
    private String mProviderFriendlyName = null;
    private int mSecurity = AccessPoint.SECURITY_NONE;
    private WifiConfiguration mWifiConfig;
    private WifiInfo mWifiInfo;
    private boolean mIsCarrierAp = false;
    private String mCarrierName = null;

    Context mContext;
    private ArrayList<ScanResult> mScanResults;
    private ArrayList<TimestampedScoredNetwork> mScoredNetworkCache;

    @Keep
    public TestAccessPointBuilder(Context context) {
        mContext = context;
    }

    @Keep
    public AccessPoint build() {
        Bundle bundle = new Bundle();

        WifiConfiguration wifiConfig = null;
        // ephemeral networks don't have a WifiConfiguration object in AccessPoint representation.
        if (mNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            wifiConfig = new WifiConfiguration();
            wifiConfig.networkId = mNetworkId;
            wifiConfig.BSSID = mBssid;
        }

        bundle.putString(AccessPoint.KEY_SSID, ssid);
        bundle.putParcelable(AccessPoint.KEY_CONFIG, wifiConfig);
        bundle.putParcelable(AccessPoint.KEY_NETWORKINFO, mNetworkInfo);
        bundle.putParcelable(AccessPoint.KEY_WIFIINFO, mWifiInfo);
        if (mFqdn != null) {
            bundle.putString(AccessPoint.KEY_FQDN, mFqdn);
        }
        if (mProviderFriendlyName != null) {
            bundle.putString(AccessPoint.KEY_PROVIDER_FRIENDLY_NAME, mProviderFriendlyName);
        }
        if (mScanResults != null) {
            bundle.putParcelableArray(AccessPoint.KEY_SCANRESULTS,
                    mScanResults.toArray(new Parcelable[mScanResults.size()]));
        }
        if (mScoredNetworkCache != null) {
            bundle.putParcelableArrayList(AccessPoint.KEY_SCOREDNETWORKCACHE, mScoredNetworkCache);
        }
        bundle.putInt(AccessPoint.KEY_SECURITY, mSecurity);
        bundle.putInt(AccessPoint.KEY_SPEED, mSpeed);
        bundle.putBoolean(AccessPoint.KEY_IS_CARRIER_AP, mIsCarrierAp);
        if (mCarrierName != null) {
            bundle.putString(AccessPoint.KEY_CARRIER_NAME, mCarrierName);
        }

        AccessPoint ap = new AccessPoint(mContext, bundle);
        ap.setRssi(mRssi);
        return ap;
    }

    @Keep
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
     * Set the rssi based upon the desired signal level.
     *
     * <p>Side effect: if this AccessPoint was previously unreachable,
     * setting the level will also make it reachable.
     */
    @Keep
    public TestAccessPointBuilder setLevel(int level) {
        // Reversal of WifiManager.calculateSignalLevels
        if (level == 0) {
            mRssi = MIN_RSSI;
        } else if (level >= AccessPoint.SIGNAL_LEVELS) {
            mRssi = MAX_RSSI;
        } else {
            float inputRange = MAX_RSSI - MIN_RSSI;
            float outputRange = AccessPoint.SIGNAL_LEVELS - 1;
            mRssi = (int) (level * inputRange / outputRange + MIN_RSSI);
        }
        return this;
    }

    @Keep
    public TestAccessPointBuilder setNetworkInfo(NetworkInfo info) {
        mNetworkInfo = info;
        return this;
    }

    @Keep
    public TestAccessPointBuilder setRssi(int rssi) {
        mRssi = rssi;
        return this;
    }

    public TestAccessPointBuilder setSpeed(int speed) {
        mSpeed = speed;
        return this;
    }

    /**
    * Set whether the AccessPoint is reachable.
    * Side effect: if the signal level was not previously set,
    * making an AccessPoint reachable will set the signal to the minimum level.
    */
    @Keep
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

    @Keep
    public TestAccessPointBuilder setSaved(boolean saved){
        if (saved) {
             mNetworkId = 1;
        } else {
             mNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        }
        return this;
    }

    @Keep
    public TestAccessPointBuilder setSecurity(int security) {
        mSecurity = security;
        return this;
    }

    @Keep
    public TestAccessPointBuilder setSsid(String newSsid) {
        ssid = newSsid;
        return this;
    }

    @Keep
    public TestAccessPointBuilder setFqdn(String fqdn) {
        mFqdn = fqdn;
        return this;
    }

    @Keep
    public TestAccessPointBuilder setProviderFriendlyName(String friendlyName) {
        mProviderFriendlyName = friendlyName;
        return this;
    }

    @Keep
    public TestAccessPointBuilder setWifiInfo(WifiInfo info) {
        mWifiInfo = info;
        return this;
    }

    /**
     * Set the networkId in the WifiConfig.
     *
     * <p>Setting this to a value other than {@link WifiConfiguration#INVALID_NETWORK_ID} makes this
     * AccessPoint a saved network.
     */
    @Keep
    public TestAccessPointBuilder setNetworkId(int networkId) {
        mNetworkId = networkId;
        return this;
    }

    public TestAccessPointBuilder setBssid(String bssid) {
        mBssid = bssid;
        return this;
    }

    public TestAccessPointBuilder setScanResults(ArrayList<ScanResult> scanResults) {
        mScanResults = scanResults;
        return this;
    }

    public TestAccessPointBuilder setIsCarrierAp(boolean isCarrierAp) {
        mIsCarrierAp = isCarrierAp;
        return this;
    }

    public TestAccessPointBuilder setCarrierName(String carrierName) {
        mCarrierName = carrierName;
        return this;
    }

    public TestAccessPointBuilder setScoredNetworkCache(
            ArrayList<TimestampedScoredNetwork> scoredNetworkCache) {
        mScoredNetworkCache = scoredNetworkCache;
        return this;
    }
}
