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
import android.util.Log;

/**
 * Instrumentation test runner for Bluetooth tests.
 * <p>
 * To run:
 * <pre>
 * {@code
 * adb shell am instrument \
 *     [-e enable_iterations <iterations>] \
 *     [-e discoverable_iterations <iterations>] \
 *     [-e scan_iterations <iterations>] \
 *     [-e enable_pan_iterations <iterations>] \
 *     [-e pair_iterations <iterations>] \
 *     [-e connect_a2dp_iterations <iterations>] \
 *     [-e connect_headset_iterations <iterations>] \
 *     [-e connect_input_iterations <iterations>] \
 *     [-e connect_pan_iterations <iterations>] \
 *     [-e start_stop_sco_iterations <iterations>] \
 *     [-e pair_address <address>] \
 *     [-e headset_address <address>] \
 *     [-e a2dp_address <address>] \
 *     [-e input_address <address>] \
 *     [-e pan_address <address>] \
 *     [-e pair_pin <pin>] \
 *     [-e pair_passkey <passkey>] \
 *     -w com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner
 * }
 * </pre>
 */
public class BluetoothTestRunner extends InstrumentationTestRunner {
    private static final String TAG = "BluetoothTestRunner";

    public static int sEnableIterations = 100;
    public static int sDiscoverableIterations = 1000;
    public static int sScanIterations = 1000;
    public static int sEnablePanIterations = 1000;
    public static int sPairIterations = 100;
    public static int sConnectHeadsetIterations = 100;
    public static int sConnectA2dpIterations = 100;
    public static int sConnectInputIterations = 100;
    public static int sConnectPanIterations = 100;
    public static int sStartStopScoIterations = 100;

    public static String sDeviceAddress = "";
    public static byte[] sDevicePairPin = {'1', '2', '3', '4'};
    public static int sDevicePairPasskey = 123456;

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

        val = arguments.getString("enable_pan_iterations");
        if (val != null) {
            try {
                sEnablePanIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("pair_iterations");
        if (val != null) {
            try {
                sPairIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("connect_a2dp_iterations");
        if (val != null) {
            try {
                sConnectA2dpIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("connect_headset_iterations");
        if (val != null) {
            try {
                sConnectHeadsetIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("connect_input_iterations");
        if (val != null) {
            try {
                sConnectInputIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("connect_pan_iterations");
        if (val != null) {
            try {
                sConnectPanIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("start_stop_sco_iterations");
        if (val != null) {
            try {
                sStartStopScoIterations = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        val = arguments.getString("device_address");
        if (val != null) {
            sDeviceAddress = val;
        }

        val = arguments.getString("device_pair_pin");
        if (val != null) {
            byte[] pin = BluetoothDevice.convertPinToBytes(val);
            if (pin != null) {
                sDevicePairPin = pin;
            }
        }

        val = arguments.getString("device_pair_passkey");
        if (val != null) {
            try {
                sDevicePairPasskey = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                // Invalid argument, fall back to default value
            }
        }

        Log.i(TAG, String.format("enable_iterations=%d", sEnableIterations));
        Log.i(TAG, String.format("discoverable_iterations=%d", sDiscoverableIterations));
        Log.i(TAG, String.format("scan_iterations=%d", sScanIterations));
        Log.i(TAG, String.format("pair_iterations=%d", sPairIterations));
        Log.i(TAG, String.format("connect_a2dp_iterations=%d", sConnectA2dpIterations));
        Log.i(TAG, String.format("connect_headset_iterations=%d", sConnectHeadsetIterations));
        Log.i(TAG, String.format("connect_input_iterations=%d", sConnectInputIterations));
        Log.i(TAG, String.format("connect_pan_iterations=%d", sConnectPanIterations));
        Log.i(TAG, String.format("start_stop_sco_iterations=%d", sStartStopScoIterations));
        Log.i(TAG, String.format("device_address=%s", sDeviceAddress));
        Log.i(TAG, String.format("device_pair_pin=%s", new String(sDevicePairPin)));
        Log.i(TAG, String.format("device_pair_passkey=%d", sDevicePairPasskey));

        // Call onCreate last since we want to set the static variables first.
        super.onCreate(arguments);
    }
}
