package com.android.browserpowertest;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import junit.framework.TestSuite;


/**
 * Instrumentation Test Runner for all browser power tests.
 *
 * Running power tests:
 *
 * adb shell am instrument \
 *   -w com.android.browserpowertest/.PowerTestRunner
 */

public class PowerTestRunner extends InstrumentationTestRunner {
    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(PowerMeasurement.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return PowerTestRunner.class.getClassLoader();
    }

}
