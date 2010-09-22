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

public class BluetoothStressTest extends InstrumentationTestCase {
    private static final String TAG = "BluetoothStressTest";
    private static final String OUTPUT_FILE = "BluetoothStressTestOutput.txt";

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

    public void testEnable() {
        int iterations = BluetoothTestRunner.sEnableIterations;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("enable iteration " + (i + 1) + " of " + iterations);
            mTestUtils.enable(adapter);
            mTestUtils.disable(adapter);
        }
    }

    public void testDiscoverable() {
        int iterations = BluetoothTestRunner.sDiscoverableIterations;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.enable(adapter);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("discoverable iteration " + (i + 1) + " of " + iterations);
            mTestUtils.discoverable(adapter);
            mTestUtils.undiscoverable(adapter);
        }

        mTestUtils.disable(adapter);
    }

    public void testScan() {
        int iterations = BluetoothTestRunner.sScanIterations;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.enable(adapter);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("scan iteration " + (i + 1) + " of " + iterations);
            mTestUtils.startScan(adapter);
            mTestUtils.stopScan(adapter);
        }

        mTestUtils.disable(adapter);
    }
}
