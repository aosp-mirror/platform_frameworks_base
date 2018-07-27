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
 * limitations under the License
 */

package com.android.tests.sysmem.host;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestMetrics;
import com.android.tradefed.testtype.IDeviceTest;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class MemoryTest implements IDeviceTest {

    @Rule public TestMetrics testMetrics = new TestMetrics();
    @Rule public TestLogData testLogs = new TestLogData();

    private ITestDevice testDevice;
    private int iterations = 0;     // Number of times cujs have been run.
    private Metrics metrics;
    private Cujs cujs;

    @Override
    public void setDevice(ITestDevice device) {
        testDevice = device;
        metrics = new Metrics(device, testMetrics, testLogs);
        cujs = new Cujs(device);
    }

    @Override
    public ITestDevice getDevice() {
        return testDevice;
    }

    // Invoke a single iteration of running the cujs.
    private void runCujs() throws DeviceNotAvailableException {
        cujs.run();
        iterations++;
    }

    // Sample desired memory.
    private void sample()
            throws DeviceNotAvailableException, IOException, Metrics.MetricsException {
        metrics.sample(String.format("%03d", iterations));
    }

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
