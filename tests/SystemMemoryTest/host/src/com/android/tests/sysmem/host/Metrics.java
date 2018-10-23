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

import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestMetrics;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.InputMismatchException;
import java.util.Scanner;

/**
 * Utilities for sampling and reporting memory metrics.
 */
class Metrics {

    private Device mDevice;
    private TestMetrics mMetrics;
    private TestLogData mLogs;

    /**
     * Constructs a metrics instance that will output high level metrics and
     * more detailed breakdowns using the given <code>metrics</code> and
     * <code>logs</code> objects.
     *
     * @param device the device to sample metrics from
     * @param metrics where to log the high level metrics when taking a sample
     * @param logs where to log detailed breakdowns when taking a sample
     */
    Metrics(Device device, TestMetrics metrics, TestLogData logs) {
        this.mDevice = device;
        this.mMetrics = metrics;
        this.mLogs = logs;
    }

    /**
     * Writes the given <code>text</code> to a log with the given label.
     */
    private void logText(String label, String text) throws TestException {
        try {
            File file = File.createTempFile(label, "txt");
            PrintStream ps = new PrintStream(file);
            ps.print(text);
            try (FileInputStreamSource dataStream = new FileInputStreamSource(file)) {
                mLogs.addTestLog(label, LogDataType.TEXT, dataStream);
            }
        } catch (IOException e) {
            throw new TestException(e);
        }
    }

    /**
     * Samples the current memory use on the system. Outputs high level test
     * metrics and detailed breakdowns to the TestMetrics and TestLogData
     * objects provided when constructing this Metrics instance. The metrics
     * and log names are prefixed with the given label.
     *
     * @param label prefix to use for metrics and logs output for this sample.
     */
    void sample(String label) throws TestException {
        // adb root access is required to get showmap
        mDevice.enableAdbRoot();

        int pid = mDevice.getProcessPid("system_server");

        // Read showmap for system server and add it as a test log
        String showmap = mDevice.executeShellCommand("showmap " + pid);
        logText(label + ".system_server.showmap", showmap);

        // Extract VSS, PSS and RSS from the showmap and output them as metrics.
        // The last lines of the showmap output looks something like:
        // CHECKSTYLE:OFF Generated code
        // virtual                     shared   shared  private  private
        //    size      RSS      PSS    clean    dirty    clean    dirty     swap  swapPSS   # object
        //-------- -------- -------- -------- -------- -------- -------- -------- -------- ---- ------------------------------
        //  928480   113016    24860    87348     7916     3632    14120     1968     1968 1900 TOTAL
        // CHECKSTYLE:ON Generated code
        try {
            int pos = showmap.lastIndexOf("----");
            Scanner sc = new Scanner(showmap.substring(pos));
            sc.next();
            long vss = sc.nextLong();
            long rss = sc.nextLong();
            long pss = sc.nextLong();

            mMetrics.addTestMetric(label + ".system_server.vss", Long.toString(vss));
            mMetrics.addTestMetric(label + ".system_server.rss", Long.toString(rss));
            mMetrics.addTestMetric(label + ".system_server.pss", Long.toString(pss));
        } catch (InputMismatchException e) {
            throw new TestException("unexpected showmap format", e);
        }

        // Run debuggerd -j to get GC stats for system server and add it as a
        // test log
        String debuggerd = mDevice.executeShellCommand("debuggerd -j " + pid);
        logText(label + ".system_server.debuggerd", debuggerd);

        // TODO: Experiment with other additional metrics.

        // TODO: Consider launching an instrumentation to collect metrics from
        // within the device itself.
    }
}
