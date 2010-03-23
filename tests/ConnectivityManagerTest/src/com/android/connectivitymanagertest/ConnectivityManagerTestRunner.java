package com.android.connectivitymanagertest;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import android.util.Log;
import com.android.connectivitymanagertest.functional.ConnectivityManagerMobileTest;

import junit.framework.TestSuite;

/**
 * Instrumentation Test Runner for all connectivity manager tests.
 *
 * To run the connectivity manager tests:
 *
 * adb shell am instrument \
 *     -w com.android.connectivitymanagertest/.ConnectivityManagerTestRunner
 */

public class ConnectivityManagerTestRunner extends InstrumentationTestRunner {
    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(ConnectivityManagerMobileTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return ConnectivityManagerTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        String testSSID = (String) icicle.get("ssid");
        if (testSSID != null) {
            TEST_SSID = testSSID;
        }
    }

    public String TEST_SSID = "GoogleGuest";
}
