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
package com.android.tests.nativeprocesses;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

/**
 * Test to gather native processes count and memory usage.
 *
 * Native processes are parsed from dumpsys meminfo --oom -c
 *
 * <pre>
 * time,35857009,35857009
 * oom,native,331721,N/A
 * proc,native,init,1,2715,N/A,e
 * proc,native,init,445,1492,N/A,e
 * ...
 * </pre>
 *
 * For each native process we also look at its showmap output, and gather the PSS, VSS, and RSS.
 * The showmap output is also saved to test logs.
 *
 * The metrics reported are:
 *
 * <pre>
 *   - number of native processes
 *   - total memory use of native processes
 *   - memory usage of each native process (one measurement for each process)
 * </pre>
 */
public class NativeProcessesMemoryTest implements IDeviceTest, IRemoteTest {
    // the dumpsys process comes and go as we run this test, changing pids, so ignore it
    private static final List<String> PROCESSES_TO_IGNORE = Arrays.asList("dumpsys");

    // -c gives us a compact output which is comma separated
    private static final String DUMPSYS_MEMINFO_OOM_CMD = "dumpsys meminfo --oom -c";

    private static final String SEPARATOR = ",";
    private static final String LINE_SEPARATOR = "\\n";

    // name of this test run, used for reporting
    private static final String RUN_NAME = "NativeProcessesTest";
    // key used to report the number of native processes
    private static final String NUM_NATIVE_PROCESSES_KEY = "Num_native_processes";

    private final Map<String, String> mNativeProcessToMemory = new HashMap<String, String>();
    // identity for summing over MemoryMetric
    private final MemoryMetric mZero = new MemoryMetric(0, 0, 0);

    private ITestDevice mTestDevice;
    private ITestInvocationListener mListener;

    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        mListener = listener;
        // showmap requires root, we enable it here for the rest of the test
        mTestDevice.enableAdbRoot();

        listener.testRunStarted(RUN_NAME, 1 /* testCount */);

        TestDescription testDescription = new TestDescription(getClass().getName(), "run");
        listener.testStarted(testDescription);

        // process name -> list of pids with that name
        Map<String, List<String>> nativeProcesses = collectNativeProcesses();
        sampleAndLogAllProcesses(nativeProcesses);

        // want to also record the number of native processes
        mNativeProcessToMemory.put(
                NUM_NATIVE_PROCESSES_KEY, Integer.toString(nativeProcesses.size()));

        listener.testEnded(testDescription, mNativeProcessToMemory);
        listener.testRunEnded(0, new HashMap<String, String>());
    }

    /** Samples memory of all processes and logs the memory use. */
    private void sampleAndLogAllProcesses(Map<String, List<String>> nativeProcesses)
            throws DeviceNotAvailableException {
        for (Map.Entry<String, List<String>> entry : nativeProcesses.entrySet()) {
            String processName = entry.getKey();
            if (PROCESSES_TO_IGNORE.contains(processName)) {
                continue;
            }

            // for all pids associated with this process name, record their memory usage
            List<MemoryMetric> allMemsForProcess = new ArrayList<>();
            for (String pid : entry.getValue()) {
                Optional<MemoryMetric> mem = snapMemoryUsage(processName, pid);
                if (mem.isPresent()) {
                    allMemsForProcess.add(mem.get());
                }
            }

            // if for some reason a process does not have any MemoryMetric, don't log it
            if (allMemsForProcess.isEmpty()) {
                continue;
            }

            // combine all the memory metrics of process with the same name
            MemoryMetric combined = allMemsForProcess.stream().reduce(mZero, MemoryMetric::sum);
            logMemoryMetric(processName, combined);
        }
    }

    @Override
    public void setDevice(ITestDevice device) {
        mTestDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    /**
     * Query system for a list of native process.
     *
     * @return a map from process name to a list of pids that share the same name
     */
    private Map<String, List<String>> collectNativeProcesses() throws DeviceNotAvailableException {
        HashMap<String, List<String>> nativeProcesses = new HashMap<>();
        String memInfo = mTestDevice.executeShellCommand(DUMPSYS_MEMINFO_OOM_CMD);

        for (String line : memInfo.split(LINE_SEPARATOR)) {
            String[] splits = line.split(SEPARATOR);
            // ignore lines that don't list a native process
            if (splits.length < 4 || !splits[0].equals("proc") || !splits[1].equals("native")) {
                continue;
            }

            String processName = splits[2];
            String pid = splits[3];
            if (nativeProcesses.containsKey(processName)) {
                nativeProcesses.get(processName).add(pid);
            } else {
                nativeProcesses.put(processName, new ArrayList<>(Arrays.asList(pid)));
            }
        }
        return nativeProcesses;
    }

    /** Logs an entire showmap output to the test logs. */
    private void logShowmap(String label, String showmap) {
        try (ByteArrayInputStreamSource source =
                new ByteArrayInputStreamSource(showmap.getBytes())) {
            mListener.testLog(label + "_showmap", LogDataType.TEXT, source);
        }
    }

    /**
     * Extract VSS, PSS, and RSS from showmap of a process.
     * The showmap output is also added to test logs.
     */
    private Optional<MemoryMetric> snapMemoryUsage(String processName, String pid)
            throws DeviceNotAvailableException {
        // TODO(zhin): copied from com.android.tests.sysmem.host.Metrics#sample(), extract?
        String showmap = mTestDevice.executeShellCommand("showmap " + pid);
        logShowmap(processName + "_" + pid, showmap);

        // CHECKSTYLE:OFF Generated code
        // The last lines of the showmap output looks like:
        // ------- -------- -------- -------- -------- -------- -------- -------- -------- ---- ------------------------------
        // virtual                     shared   shared  private  private
        //    size      RSS      PSS    clean    dirty    clean    dirty     swap  swapPSS   # object
        // -------- -------- -------- -------- -------- -------- -------- -------- -------- ---- ------------------------------
        //   12848     4240     1543     2852       64       36     1288        0        0  171 TOTAL
        // CHECKSTYLE:ON Generated code
        try {
            int pos = showmap.lastIndexOf("----");
            Scanner sc = new Scanner(showmap.substring(pos));
            sc.next();
            long vss = sc.nextLong();
            long rss = sc.nextLong();
            long pss = sc.nextLong();
            return Optional.of(new MemoryMetric(pss, rss, vss));
        } catch (InputMismatchException e) {
            // this might occur if we have transient processes, it was collected earlier,
            // but by the time we look at showmap the process is gone
            CLog.e("Unable to parse MemoryMetric from showmap of pid: " + pid + " processName: "
                    + processName);
            CLog.e(showmap);
            return Optional.empty();
        }
    }

    /** Logs a MemoryMetric of a process. */
    private void logMemoryMetric(String processName, MemoryMetric memoryMetric) {
        mNativeProcessToMemory.put(processName + "_pss", Long.toString(memoryMetric.pss));
        mNativeProcessToMemory.put(processName + "_rss", Long.toString(memoryMetric.rss));
        mNativeProcessToMemory.put(processName + "_vss", Long.toString(memoryMetric.vss));
    }

    /** Container of memory numbers we want to log. */
    private final class MemoryMetric {
        final long pss;
        final long rss;
        final long vss;

        MemoryMetric(long pss, long rss, long vss) {
            this.pss = pss;
            this.rss = rss;
            this.vss = vss;
        }

        MemoryMetric sum(MemoryMetric other) {
            return new MemoryMetric(
                    pss + other.pss, rss + other.rss, vss + other.vss);
        }
    }
}
