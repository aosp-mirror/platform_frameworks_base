/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tests.sysmem.host;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestMetrics;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * Runs a system memory test.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class MemoryTest implements IDeviceTest {

    @Rule public TestMetrics testMetrics = new TestMetrics();
    @Rule public TestLogData testLogs = new TestLogData();

    private ITestDevice mTestDevice;
    private int mIterations = 0;     // Number of times cujs have been run.
    private Metrics mMetrics;
    private Cujs mCujs;

    @Override
    public void setDevice(ITestDevice device) {
        mTestDevice = device;
        mMetrics = new Metrics(device, testMetrics, testLogs);
        mCujs = new Cujs(device);
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    // Invoke a single iteration of running the cujs.
    private void runCujs() throws DeviceNotAvailableException {
        mCujs.run();
        mIterations++;
    }

    // Sample desired memory.
    private void sample()
            throws DeviceNotAvailableException, IOException, Metrics.MetricsException {
        mMetrics.sample(String.format("%03d", mIterations));
    }

    /**
     * Runs the memory tests.
     */
    @Test
    public void run() throws Exception {
        sample();   // Sample before running cujs
        runCujs();
        sample();   // Sample after first iteration of cujs

        // Run cujs in a loop to highlight memory leaks.
        for (int i = 0; i < 5; ++i) {
            for (int j = 0; j < 5; j++) {
                runCujs();
            }
            sample();
        }
    }
}
