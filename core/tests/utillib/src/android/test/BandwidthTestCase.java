/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.test;

import android.net.NetworkStats;
import android.net.TrafficStats;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * A bandwidth test case that collects bandwidth statistics for tests that are
 * annotated with {@link BandwidthTest} otherwise the test is executed
 * as an {@link InstrumentationTestCase}
 */
public class BandwidthTestCase extends InstrumentationTestCase {
    private static final String TAG = "BandwidthTestCase";
    private static final String REPORT_KEY_PACKETS_SENT = "txPackets";
    private static final String REPORT_KEY_PACKETS_RECEIVED = "rxPackets";
    private static final String REPORT_KEY_BYTES_SENT = "txBytes";
    private static final String REPORT_KEY_BYTES_RECEIVED = "rxBytes";
    private static final String REPORT_KEY_OPERATIONS = "operations";

    @Override
    protected void runTest() throws Throwable {
        //This is a copy of {@link InstrumentationTestCase#runTest} with
        //added logic to handle bandwidth measurements
        String fName = getName();
        assertNotNull(fName);
        Method method = null;
        Class testClass = null;
        try {
            // use getMethod to get all public inherited
            // methods. getDeclaredMethods returns all
            // methods of this class but excludes the
            // inherited ones.
            testClass = getClass();
            method = testClass.getMethod(fName, (Class[]) null);
        } catch (NoSuchMethodException e) {
            fail("Method \""+fName+"\" not found");
        }

        if (!Modifier.isPublic(method.getModifiers())) {
            fail("Method \""+fName+"\" should be public");
        }

        int runCount = 1;
        boolean isRepetitive = false;
        if (method.isAnnotationPresent(FlakyTest.class)) {
            runCount = method.getAnnotation(FlakyTest.class).tolerance();
        } else if (method.isAnnotationPresent(RepetitiveTest.class)) {
            runCount = method.getAnnotation(RepetitiveTest.class).numIterations();
            isRepetitive = true;
        }

        if (method.isAnnotationPresent(UiThreadTest.class)) {
            final int tolerance = runCount;
            final boolean repetitive = isRepetitive;
            final Method testMethod = method;
            final Throwable[] exceptions = new Throwable[1];
            getInstrumentation().runOnMainSync(new Runnable() {
                public void run() {
                    try {
                        runMethod(testMethod, tolerance, repetitive);
                    } catch (Throwable throwable) {
                        exceptions[0] = throwable;
                    }
                }
            });
            if (exceptions[0] != null) {
                throw exceptions[0];
            }
        } else if (method.isAnnotationPresent(BandwidthTest.class) ||
                testClass.isAnnotationPresent(BandwidthTest.class)) {
            /**
             * If bandwidth profiling fails for whatever reason the test
             * should be allow to execute to its completion.
             * Typically bandwidth profiling would fail when a lower level
             * component is missing, such as the kernel module, for a newly
             * introduced hardware.
             */
            try{
                TrafficStats.startDataProfiling(null);
            } catch(IllegalStateException isx){
                Log.w(TAG, "Failed to start bandwidth profiling");
            }
            runMethod(method, 1, false);
            try{
                NetworkStats stats = TrafficStats.stopDataProfiling(null);
                NetworkStats.Entry entry = stats.getTotal(null);
                getInstrumentation().sendStatus(2, getBandwidthStats(entry));
            } catch (IllegalStateException isx){
                Log.w(TAG, "Failed to collect bandwidth stats");
            }
        } else {
            runMethod(method, runCount, isRepetitive);
        }
    }

    private void runMethod(Method runMethod, int tolerance, boolean isRepetitive) throws Throwable {
        //This is a copy of {@link InstrumentationTestCase#runMethod}
        Throwable exception = null;

        int runCount = 0;
        do {
            try {
                runMethod.invoke(this, (Object[]) null);
                exception = null;
            } catch (InvocationTargetException e) {
                e.fillInStackTrace();
                exception = e.getTargetException();
            } catch (IllegalAccessException e) {
                e.fillInStackTrace();
                exception = e;
            } finally {
                runCount++;
                // Report current iteration number, if test is repetitive
                if (isRepetitive) {
                    Bundle iterations = new Bundle();
                    iterations.putInt("currentiterations", runCount);
                    getInstrumentation().sendStatus(2, iterations);
                }
            }
        } while ((runCount < tolerance) && (isRepetitive || exception != null));

        if (exception != null) {
            throw exception;
        }
    }

    private Bundle getBandwidthStats(NetworkStats.Entry entry){
        Bundle bundle = new Bundle();
        bundle.putLong(REPORT_KEY_BYTES_RECEIVED, entry.rxBytes);
        bundle.putLong(REPORT_KEY_BYTES_SENT, entry.txBytes);
        bundle.putLong(REPORT_KEY_PACKETS_RECEIVED, entry.rxPackets);
        bundle.putLong(REPORT_KEY_PACKETS_SENT, entry.txPackets);
        bundle.putLong(REPORT_KEY_OPERATIONS, entry.operations);
        return bundle;
    }
}

