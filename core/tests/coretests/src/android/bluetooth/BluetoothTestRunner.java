/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.bluetooth;

import junit.framework.TestSuite;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

public class BluetoothTestRunner extends InstrumentationTestRunner {
    public static int sEnableIterations = 100;
    public static int sDiscoverableIterations = 1000;
    public static int sScanIterations = 1000;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(BluetoothStressTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return BluetoothTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);

        String val = arguments.getString("enable_iterations");
        if (val != null) {
            try {
                sEnableIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("discoverable_iterations");
        if (val != null) {
            try {
                sDiscoverableIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("scan_iterations");
        if (val != null) {
            try {
                sScanIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }
    }
}
