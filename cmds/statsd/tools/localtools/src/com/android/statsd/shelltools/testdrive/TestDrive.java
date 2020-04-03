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
package com.android.statsd.shelltools.testdrive;

import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.EventMetric;
import com.android.internal.os.StatsdConfigProto.FieldFilter;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.PullAtomPackages;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.StatsLogReport;
import com.android.statsd.shelltools.Utils;

import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestDrive {

    private static final int METRIC_ID_BASE = 1111;
    private static final long ATOM_MATCHER_ID_BASE = 1234567;
    private static final long APP_BREADCRUMB_MATCHER_ID = 1111111;
    private static final int PULL_ATOM_START = 10000;
    private static final int MAX_PLATFORM_ATOM_TAG = 100000;
    private static final int VENDOR_PULLED_ATOM_START_TAG = 150000;
    private static final long CONFIG_ID = 54321;
    private static final String[] ALLOWED_LOG_SOURCES = {
            "AID_GRAPHICS",
            "AID_INCIDENTD",
            "AID_STATSD",
            "AID_RADIO",
            "com.android.systemui",
            "com.android.vending",
            "AID_SYSTEM",
            "AID_ROOT",
            "AID_BLUETOOTH",
            "AID_LMKD",
            "com.android.managedprovisioning",
            "AID_MEDIA",
            "AID_NETWORK_STACK"
    };
    private static final String[] DEFAULT_PULL_SOURCES = {
            "AID_SYSTEM",
    };
    private static final Logger LOGGER = Logger.getLogger(TestDrive.class.getName());

    private String mAdditionalAllowedPackage;
    private String mDeviceSerial;
    private final Set<Long> mTrackedMetrics = new HashSet<>();

    public static void main(String[] args) {
        TestDrive testDrive = new TestDrive();
        Set<Integer> trackedAtoms = new HashSet<>();
        Utils.setUpLogger(LOGGER, false);
        String remoteConfigPath = null;

        if (args.length < 1) {
            LOGGER.log(Level.SEVERE, "Usage: ./test_drive [-p additional_allowed_package] "
                    + "[-s DEVICE_SERIAL_NUMBER]"
                    + "<atomId1> <atomId2> ... <atomIdN>");
            return;
        }

        List<String> connectedDevices = Utils.getDeviceSerials(LOGGER);
        if (connectedDevices == null || connectedDevices.size() == 0) {
            LOGGER.log(Level.SEVERE, "No device connected.");
            return;
        }

        int arg_index = 0;
        while (arg_index < args.length) {
            String arg = args[arg_index];
            if (arg.equals("-p")) {
                testDrive.mAdditionalAllowedPackage = args[++arg_index];
            } else if (arg.equals("-s")) {
                testDrive.mDeviceSerial = args[++arg_index];
            } else {
                break;
            }
            arg_index++;
        }

        if (connectedDevices.size() == 1 && testDrive.mDeviceSerial == null) {
            testDrive.mDeviceSerial = connectedDevices.get(0);
        }

        if (testDrive.mDeviceSerial == null) {
            LOGGER.log(Level.SEVERE, "More than one devices connected. Please specify"
                    + " with -s DEVICE_SERIAL");
            return;
        }

        for (int i = arg_index; i < args.length; i++) {
            try {
                int atomId = Integer.valueOf(args[i]);
                if (Atom.getDescriptor().findFieldByNumber(atomId) == null) {
                    LOGGER.log(Level.SEVERE, "No such atom found: " + args[i]);
                    continue;
                }
                trackedAtoms.add(atomId);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.SEVERE, "Bad atom id provided: " + args[i]);
                continue;
            }
        }

        try {
            StatsdConfig config = testDrive.createConfig(trackedAtoms);
            if (config == null) {
                LOGGER.log(Level.SEVERE, "Failed to create valid config.");
                return;
            }
            remoteConfigPath = testDrive.pushConfig(config, testDrive.mDeviceSerial);
            LOGGER.info("Pushed the following config to statsd:");
            LOGGER.info(config.toString());
            if (!hasPulledAtom(trackedAtoms)) {
                LOGGER.info(
                        "Now please play with the device to trigger the event. All events should "
                                + "be dumped after 1 min ...");
                Thread.sleep(60_000);
            } else {
                LOGGER.info("Now wait for 1.5 minutes ...");
                Thread.sleep(15_000);
                Utils.logAppBreadcrumb(0, 0, LOGGER, testDrive.mDeviceSerial);
                Thread.sleep(75_000);
            }
            testDrive.dumpMetrics();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to test drive: " + e.getMessage(), e);
        } finally {
            testDrive.removeConfig(testDrive.mDeviceSerial);
            if (remoteConfigPath != null) {
                try {
                    Utils.runCommand(null, LOGGER,
                            "adb", "-s", testDrive.mDeviceSerial, "shell", "rm", remoteConfigPath);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING,
                            "Unable to remove remote config file: " + remoteConfigPath, e);
                }
            }
        }
    }

    private void dumpMetrics() throws Exception {
        ConfigMetricsReportList reportList = Utils.getReportList(CONFIG_ID, true, false, LOGGER,
                mDeviceSerial);
        // We may get multiple reports. Take the last one.
        ConfigMetricsReport report = reportList.getReports(reportList.getReportsCount() - 1);
        for (StatsLogReport statsLog : report.getMetricsList()) {
            if (mTrackedMetrics.contains(statsLog.getMetricId())) {
                LOGGER.info(statsLog.toString());
            }
        }
    }

    private StatsdConfig createConfig(Set<Integer> atomIds) {
        long metricId = METRIC_ID_BASE;
        long atomMatcherId = ATOM_MATCHER_ID_BASE;

        ArrayList<String> allowedSources = new ArrayList<>();
        Collections.addAll(allowedSources, ALLOWED_LOG_SOURCES);
        if (mAdditionalAllowedPackage != null) {
            allowedSources.add(mAdditionalAllowedPackage);
        }

        StatsdConfig.Builder builder = StatsdConfig.newBuilder();
        builder
            .addAllAllowedLogSource(allowedSources)
            .addAllDefaultPullPackages(Arrays.asList(DEFAULT_PULL_SOURCES))
            .addPullAtomPackages(PullAtomPackages.newBuilder()
                    .setAtomId(Atom.GPU_STATS_GLOBAL_INFO_FIELD_NUMBER)
                    .addPackages("AID_GPU_SERVICE"))
            .addPullAtomPackages(PullAtomPackages.newBuilder()
                    .setAtomId(Atom.GPU_STATS_APP_INFO_FIELD_NUMBER)
                    .addPackages("AID_GPU_SERVICE"))
            .addPullAtomPackages(PullAtomPackages.newBuilder()
                    .setAtomId(Atom.TRAIN_INFO_FIELD_NUMBER)
                    .addPackages("AID_STATSD"))
            .setHashStringsInMetricReport(false);

        if (hasPulledAtom(atomIds)) {
            builder.addAtomMatcher(
                    createAtomMatcher(
                            Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER, APP_BREADCRUMB_MATCHER_ID));
        }

        for (int atomId : atomIds) {
            if (isPulledAtom(atomId)) {
                builder.addAtomMatcher(createAtomMatcher(atomId, atomMatcherId));
                GaugeMetric.Builder gaugeMetricBuilder = GaugeMetric.newBuilder();
                gaugeMetricBuilder
                    .setId(metricId)
                    .setWhat(atomMatcherId)
                    .setTriggerEvent(APP_BREADCRUMB_MATCHER_ID)
                    .setGaugeFieldsFilter(FieldFilter.newBuilder().setIncludeAll(true).build())
                    .setBucket(TimeUnit.ONE_MINUTE)
                    .setSamplingType(GaugeMetric.SamplingType.FIRST_N_SAMPLES)
                    .setMaxNumGaugeAtomsPerBucket(100);
                builder.addGaugeMetric(gaugeMetricBuilder.build());
            } else {
                EventMetric.Builder eventMetricBuilder = EventMetric.newBuilder();
                eventMetricBuilder
                    .setId(metricId)
                    .setWhat(atomMatcherId);
                builder.addEventMetric(eventMetricBuilder.build());
                builder.addAtomMatcher(createAtomMatcher(atomId, atomMatcherId));
            }
            atomMatcherId++;
            mTrackedMetrics.add(metricId++);
        }
        return builder.build();
    }

    private static AtomMatcher createAtomMatcher(int atomId, long matcherId) {
        AtomMatcher.Builder atomMatcherBuilder = AtomMatcher.newBuilder();
        atomMatcherBuilder
                .setId(matcherId)
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder().setAtomId(atomId));
        return atomMatcherBuilder.build();
    }

    private static String pushConfig(StatsdConfig config, String deviceSerial)
            throws IOException, InterruptedException {
        File configFile = File.createTempFile("statsdconfig", ".config");
        configFile.deleteOnExit();
        Files.write(config.toByteArray(), configFile);
        String remotePath = "/data/local/tmp/" + configFile.getName();
        Utils.runCommand(null, LOGGER, "adb", "-s", deviceSerial,
                "push", configFile.getAbsolutePath(), remotePath);
        Utils.runCommand(null, LOGGER, "adb", "-s", deviceSerial,
                "shell", "cat", remotePath, "|", Utils.CMD_UPDATE_CONFIG,
                String.valueOf(CONFIG_ID));
        return remotePath;
    }

    private static void removeConfig(String deviceSerial) {
        try {
            Utils.runCommand(null, LOGGER, "adb", "-s", deviceSerial,
                    "shell", Utils.CMD_REMOVE_CONFIG, String.valueOf(CONFIG_ID));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to remove config: " + e.getMessage());
        }
    }

    private static boolean isPulledAtom(int atomId) {
        return atomId >= PULL_ATOM_START && atomId <= MAX_PLATFORM_ATOM_TAG
                || atomId >= VENDOR_PULLED_ATOM_START_TAG;
    }

    private static boolean hasPulledAtom(Set<Integer> atoms) {
        for (Integer i : atoms) {
            if (isPulledAtom(i)) {
                return true;
            }
        }
        return false;
    }
}
