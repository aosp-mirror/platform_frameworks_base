/*
 * Copyright (C) 2010, The Android Open Source Project
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

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import com.android.connectivitymanagertest.stress.WifiStressTest;

import junit.framework.TestSuite;

/**
 * Instrumentation Test Runner for all stress tests
 *
 * To run the stress tests:
 *
 * adb shell am instrument -e stressnum <stress times> \
 *     -w com.android.connectivitymanagertest/.ConnectivityManagerStressTestRunner
 */

public class ConnectivityManagerStressTestRunner extends InstrumentationTestRunner {
    private int mSoftApIterations = 0;
    private int mScanIterations = 100;
    private int mReconnectIterations = 100;
    // sleep time before restart wifi, default is set to 2 minutes
    private long mSleepTime = 2 * 60 * 1000;
    private String mReconnectSsid = null;
    private String mReconnectPassword = null;
    private boolean mWifiOnlyFlag = false;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(WifiStressTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return ConnectivityManagerTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        String scanIterationStr = icicle.getString("scan_iterations");
        if (scanIterationStr != null) {
            int scanIteration = Integer.parseInt(scanIterationStr);
            if (scanIteration > 0) {
                mScanIterations = scanIteration;
            }
        }

        String ssidStr= icicle.getString("reconnect_ssid");
        if (ssidStr != null) {
            mReconnectSsid = ssidStr;
        }

        String passwordStr = icicle.getString("reconnect_password");
        if (passwordStr != null) {
            mReconnectPassword = passwordStr;
        }

        String reconnectStr = icicle.getString("reconnect_iterations");
        if (reconnectStr != null) {
            int iteration = Integer.parseInt(reconnectStr);
            if (iteration > 0) {
                mReconnectIterations = iteration;
            }
        }

        String sleepTimeStr = icicle.getString("sleep_time");
        if (sleepTimeStr != null) {
            int sleepTime = Integer.parseInt(sleepTimeStr);
            if (sleepTime > 0) {
                mSleepTime = 1000 * sleepTime;
            }
        }

        String wifiOnlyFlag = icicle.getString("wifi-only");
        if (wifiOnlyFlag != null) {
            mWifiOnlyFlag = true;
        }
    }

    public int getSoftApIterations() {
        return mSoftApIterations;
    }

    public int getScanIterations() {
        return mScanIterations;
    }

    public int getReconnectIterations() {
        return mReconnectIterations;
    }

    public boolean isWifiOnly() {
        return mWifiOnlyFlag;
    }

    public long getSleepTime() {
        return mSleepTime;
    }

    public String getReconnectSsid() {
        return mReconnectSsid;
    }

    public String getReconnectPassword() {
        return mReconnectPassword;
    }
}
