// Copyright 2011 Google Inc. All Rights Reserved.

package android.test;

import android.net.NetworkStats;
import android.net.NetworkStats.Entry;
import android.net.TrafficStats;
import android.os.Bundle;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestListener;

import java.lang.reflect.Method;

/**
 * A specialized {@link android.app.Instrumentation} that can collect Bandwidth statistics during
 * the execution of the test.
 * This is used in place of {@link InstrumentationTestRunner}.
 * i.e. adb shell am instrumentation -w -e class com.android.foo.FooTest \
 *          com.android.foo/android.test.BandwidthTestRunner
 *
 * @see NetworkStats and @see TrafficStats for details of the collected statistics
 * @hide
 */
public class BandwidthTestRunner extends InstrumentationTestRunner implements TestListener {
    private static final String REPORT_KEY_PACKETS_SENT = "txPackets";
    private static final String REPORT_KEY_PACKETS_RECEIVED = "rxPackets";
    private static final String REPORT_KEY_BYTES_SENT = "txBytes";
    private static final String REPORT_KEY_BYTES_RECEIVED = "rxBytes";
    private static final String REPORT_KEY_OPERATIONS = "operations";

    private boolean mHasClassAnnotation;
    private boolean mIsBandwidthTest;
    private Bundle mTestResult;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        addTestListener(this);
    }

    public void addError(Test test, Throwable t) {
    }

    public void addFailure(Test test, AssertionFailedError t) {
    }

    public void startTest(Test test) {
        String testClass = test.getClass().getName();
        String testName = ((TestCase)test).getName();
        Method testMethod = null;
        try {
            testMethod = test.getClass().getMethod(testName);
        } catch (NoSuchMethodException e) {
            // ignore- the test with given name does not exist. Will be handled during test
            // execution
        }
        try {
            // Look for BandwdthTest annotation on both test class and test method
            if (testMethod != null ) {
                if (testMethod.isAnnotationPresent(BandwidthTest.class) ){
                    mIsBandwidthTest = true;
                    TrafficStats.startDataProfiling(null);
                } else if (test.getClass().isAnnotationPresent(BandwidthTest.class)){
                    mIsBandwidthTest = true;
                    TrafficStats.startDataProfiling(null);
                }
            }
        } catch (SecurityException e) {
            // ignore - the test with given name cannot be accessed. Will be handled during
            // test execution
        }
    }

    public void endTest(Test test) {
        if (mIsBandwidthTest){
            mTestResult = new Bundle();
            mIsBandwidthTest=false;
            NetworkStats stats = TrafficStats.stopDataProfiling(null);
            Entry entry = stats.getTotal(null);
            mTestResult.putLong(REPORT_KEY_BYTES_RECEIVED, entry.rxBytes);
            mTestResult.putLong(REPORT_KEY_BYTES_SENT, entry.txBytes);
            mTestResult.putLong(REPORT_KEY_PACKETS_RECEIVED, entry.rxPackets);
            mTestResult.putLong(REPORT_KEY_PACKETS_SENT, entry.txPackets);
            mTestResult.putLong(REPORT_KEY_OPERATIONS, entry.operations);
            System.out.println(mTestResult.toString());
            sendStatus(0, mTestResult);
        }
    }
}
