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

    private ITestDevice device;
    private TestMetrics metrics;
    private TestLogData logs;

    /**
     * Exception thrown in case of error sampling metrics.
     */
    public static class MetricsException extends Exception {
        public MetricsException(String msg) {
            super(msg);
        }

        MetricsException(String msg, Exception cause) {
            super(msg, cause);
        }
    }

    /**
     * Constructs a metrics instance that will output high level metrics and
     * more detailed breakdowns using the given <code>metrics</code> and
     * <code>logs</code> objects.
     *
     * @param device the device to sample metrics from
     * @param metrics where to log the high level metrics when taking a sample
     * @param logs where to log detailed breakdowns when taking a sample
     */
    public Metrics(ITestDevice device, TestMetrics metrics, TestLogData logs) {
        this.device = device;
        this.metrics = metrics;
        this.logs = logs;
    }

    /**
     * Writes the given <code>text</code> to a log with the given label.
     */
    private void logText(String label, String text) throws IOException {
      File file = File.createTempFile(label, "txt");
      PrintStream ps = new PrintStream(file);
      ps.print(text);
      try (FileInputStreamSource dataStream = new FileInputStreamSource(file)) {
        logs.addTestLog(label, LogDataType.TEXT, dataStream);
      }
    }

    /**
     * Returns the pid for the process with the given name.
     */
    private int getPidForProcess(String name)
            throws DeviceNotAvailableException, IOException, MetricsException {
        String psout = device.executeShellCommand("ps -A -o PID,CMD");
        Scanner sc = new Scanner(psout);
        try {
            // ps output is of the form:
            //  PID CMD            
            //    1 init
            //    2 kthreadd
            //    ...
            // 9693 ps
            sc.nextLine();
            while (sc.hasNextLine()) {
                int pid = sc.nextInt();
                String cmd = sc.next();

                if (name.equals(cmd)) {
                    return pid;
                }
            }
        } catch (InputMismatchException e) {
            throw new MetricsException("unexpected ps output format: " + psout, e);
        }

        throw new MetricsException("failed to get pid for process " + name);
    }

    /**
     * Samples the current memory use on the system. Outputs high level test
     * metrics and detailed breakdowns to the TestMetrics and TestLogData
     * objects provided when constructing this Metrics instance. The metrics
     * and log names are prefixed with the given label.
     *
     * @param label prefix to use for metrics and logs output for this sample.
     */
    void sample(String label) throws DeviceNotAvailableException, IOException, MetricsException {
        // adb root access is required to get showmap
        device.enableAdbRoot();

        int pid = getPidForProcess("system_server");

        // Read showmap for system server and add it as a test log
        String showmap = device.executeShellCommand("showmap " + pid);
        logText(label + ".system_server.showmap", showmap);

        // Extract VSS, PSS and RSS from the showmap and output them as metrics.
        // The last lines of the showmap output looks something like:
        // virtual                     shared   shared  private  private
        //    size      RSS      PSS    clean    dirty    clean    dirty     swap  swapPSS   # object
        //-------- -------- -------- -------- -------- -------- -------- -------- -------- ---- ------------------------------
        //  928480   113016    24860    87348     7916     3632    14120     1968     1968 1900 TOTAL
        try {
            int pos = showmap.lastIndexOf("----");
            Scanner sc = new Scanner(showmap.substring(pos));
            sc.next();
            long vss = sc.nextLong();
            long rss = sc.nextLong();
            long pss = sc.nextLong();

            metrics.addTestMetric(String.format("%s.system_server.vss", label), Long.toString(vss));
            metrics.addTestMetric(String.format("%s.system_server.rss", label), Long.toString(rss));
            metrics.addTestMetric(String.format("%s.system_server.pss", label), Long.toString(pss));
        } catch (InputMismatchException e) {
            throw new MetricsException("unexpected showmap format", e);
        }

        // Run debuggerd -j to get GC stats for system server and add it as a
        // test log
        String debuggerd = device.executeShellCommand("debuggerd -j " + pid);
        logText(label + ".system_server.debuggerd", debuggerd);

        // TODO: Experiment with other additional metrics.

        // TODO: Consider launching an instrumentation to collect metrics from
        // within the device itself.
    }
}
