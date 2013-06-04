/*
 * Copyright (C) 2013, The Android Open Source Project
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

package com.android.connectivitymanagertest;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import android.util.Log;

import com.android.connectivitymanagertest.functional.WifiAssociationTest;

import junit.framework.TestSuite;
import junit.framework.Assert;

/**
 * Instrumentation Test Runner for wifi association test.
 * The instrument will set frequency band if it is necessary
 *
 * To run the association tests:
 *
 * adb shell am instrument -e ssid <ssid> -e password <password> \
 * -e security-type [OPEN|WEP64|WEP128|WPA_TKIP|WPA2_AES] -e frequency-band [2.4|5.0|auto]
 * -w com.android.connectivitymanagertest/.WifiAssociationTestRunner"
 */
public class WifiAssociationTestRunner extends InstrumentationTestRunner {
    private static final String TAG = "WifiAssociationTestRunner";
    public int mBand;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(WifiAssociationTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return WifiAssociationTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Bundle arguments = icicle;
        String mFrequencyBand = arguments.getString("frequency-band");
        if (mFrequencyBand != null) {
            setFrequencyBand(mFrequencyBand);
        }
    }

    private void setFrequencyBand(String band) {
        WifiManager mWifiManager = (WifiManager)getContext().getSystemService(Context.WIFI_SERVICE);
        if (band.equals("2.4")) {
            Log.v(TAG, "set frequency band to 2.4");
            mBand = WifiManager.WIFI_FREQUENCY_BAND_2GHZ;
        } else if (band.equals("5.0")) {
            Log.v(TAG, "set frequency band to 5.0");
            mBand = WifiManager.WIFI_FREQUENCY_BAND_5GHZ;
        } else if (band.equals("auto")) {
            Log.v(TAG, "set frequency band to auto");
            mBand = WifiManager.WIFI_FREQUENCY_BAND_AUTO;
        } else {
            Assert.fail("invalid frequency band");
        }
        int currentFreq = mWifiManager.getFrequencyBand();
        if (mBand == currentFreq) {
            Log.v(TAG, "frequency band has been set");
            return;
        }
        mWifiManager.setFrequencyBand(mBand, true);
    }
}
