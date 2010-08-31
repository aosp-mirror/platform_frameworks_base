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

import android.content.Context;
import android.test.InstrumentationTestCase;

/**
 * Instrumentation test case for stress test involving rebooting the device.
 * <p>
 * This test case tests that bluetooth is enabled after a device reboot. Because
 * the device will reboot, the instrumentation must be driven by a script on the
 * host side.
 */
public class BluetoothRebootStressTest extends InstrumentationTestCase {
    private static final String TAG = "BluetoothRebootStressTest";
    private static final String OUTPUT_FILE = "BluetoothRebootStressTestOutput.txt";

    private BluetoothTestUtils mTestUtils;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        Context context = getInstrumentation().getTargetContext();
        mTestUtils = new BluetoothTestUtils(context, TAG, OUTPUT_FILE);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mTestUtils.close();
    }

    /**
     * Test method used to start the test by turning bluetooth on.
     */
    public void testStart() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.enable(adapter);
    }

    /**
     * Test method used in the middle iterations of the test to check if
     * bluetooth is on. Does not toggle bluetooth after the check. Assumes that
     * bluetooth has been turned on by {@code #testStart()}
     */
    public void testMiddleNoToggle() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        assertTrue(adapter.isEnabled());
    }

    /**
     * Test method used in the middle iterations of the test to check if
     * bluetooth is on. Toggles bluetooth after the check. Assumes that
     * bluetooth has been turned on by {@code #testStart()}
     */
    public void testMiddleToggle() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        assertTrue(adapter.isEnabled());

        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
    }

    /**
     * Test method used in the stop the test by turning bluetooth off. Assumes
     * that bluetooth has been turned on by {@code #testStart()}
     */
    public void testStop() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        assertTrue(adapter.isEnabled());

        mTestUtils.disable(adapter);
    }
}
