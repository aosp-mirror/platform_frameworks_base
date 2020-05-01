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

import com.android.internal.os.StatsdConfigProto;
import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.EventMetric;
import com.android.internal.os.StatsdConfigProto.FieldFilter;
import com.android.internal.os.StatsdConfigProto.GaugeMetric;
import com.android.internal.os.StatsdConfigProto.PullAtomPackages;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.TimeUnit;
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.StatsLogReport;
import com.android.statsd.shelltools.Utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
            "AID_NETWORK_STACK",
            "com.google.android.providers.media.module",
    };
    private static final String[] DEFAULT_PULL_SOURCES = {
            "AID_SYSTEM",
            "AID_RADIO"
    };
    private static final Logger LOGGER = Logger.getLogger(TestDrive.class.getName());

    @VisibleForTesting
    String mDeviceSerial = null;
    @VisibleForTesting
    Dumper mDumper = new BasicDumper();

    public static void main(String[] args) {
        final Configuration configuration = new Configuration();
        final TestDrive testDrive = new TestDrive();
        Utils.setUpLogger(LOGGER, false);

        if (!testDrive.processArgs(configuration, args,
                Utils.getDeviceSerials(LOGGER), Utils.getDefaultDevice(LOGGER))) {
            return;
        }

        final ConfigMetricsReportList reports = testDrive.testDriveAndGetReports(
                configuration.createConfig(), configuration.hasPulledAtoms(),
                configuration.hasPushedAtoms());
        if (reports != null) {
            configuration.dumpMetrics(reports, testDrive.mDumper);
        }
    }

    boolean processArgs(Configuration configuration, String[] args, List<String> connectedDevices,
            String defaultDevice) {
        if (args.length < 1) {
            LOGGER.severe("Usage: ./test_drive [-one] "
                    + "[-p additional_allowed_package] "
                    + "[-s DEVICE_SERIAL_NUMBER] "
                    + "<atomId1> <atomId2> ... <atomIdN>");
            return false;
        }

        int first_arg = 0;
        // Consume all flags, which must precede all atoms
        for (; first_arg < args.length; ++first_arg) {
            String arg = args[first_arg];
            int remaining_args = args.length - first_arg;
            if (remaining_args >= 2 && arg.equals("-one")) {
                LOGGER.info("Creating one event metric to catch all pushed atoms.");
                configuration.mOnePushedAtomEvent = true;
            } else if (remaining_args >= 2 && arg.equals("-terse")) {
                LOGGER.info("Terse output format.");
                mDumper = new TerseDumper();
            } else if (remaining_args >= 3 && arg.equals("-p")) {
                configuration.mAdditionalAllowedPackage = args[++first_arg];
            } else if (remaining_args >= 3 && arg.equals("-s")) {
                mDeviceSerial = args[++first_arg];
            } else {
                break;  // Found the atom list
            }
        }

        mDeviceSerial = Utils.chooseDevice(mDeviceSerial, connectedDevices, defaultDevice, LOGGER);
        if (mDeviceSerial == null) {
            return false;
        }

        for ( ; first_arg < args.length; ++first_arg) {
            String atom = args[first_arg];
            try {
                configuration.addAtom(Integer.valueOf(atom));
            } catch (NumberFormatException e) {
                LOGGER.severe("Bad atom id provided: " + atom);
            }
        }

        return configuration.hasPulledAtoms() || configuration.hasPushedAtoms();
    }

    private ConfigMetricsReportList testDriveAndGetReports(StatsdConfig config,
            boolean hasPulledAtoms, boolean hasPushedAtoms) {
        if (config == null) {
            LOGGER.severe("Failed to create valid config.");
            return null;
        }

        String remoteConfigPath = null;
        try {
            remoteConfigPath = pushConfig(config, mDeviceSerial);
            LOGGER.info("Pushed the following config to statsd on device '" + mDeviceSerial
                    + "':");
            LOGGER.info(config.toString());
            if (hasPushedAtoms) {
                LOGGER.info("Now please play with the device to trigger the event.");
            }
            if (!hasPulledAtoms) {
                LOGGER.info(
                        "All events should be dumped after 1 min ...");
                Thread.sleep(60_000);
            } else {
                LOGGER.info("All events should be dumped after 1.5 minutes ...");
                Thread.sleep(15_000);
                Utils.logAppBreadcrumb(0, 0, LOGGER, mDeviceSerial);
                Thread.sleep(75_000);
            }
            return Utils.getReportList(CONFIG_ID, true, false, LOGGER,
                    mDeviceSerial);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to test drive: " + e.getMessage(), e);
        } finally {
            removeConfig(mDeviceSerial);
            if (remoteConfigPath != null) {
                try {
                    Utils.runCommand(null, LOGGER,
                            "adb", "-s", mDeviceSerial, "shell", "rm",
                            remoteConfigPath);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING,
                            "Unable to remove remote config file: " + remoteConfigPath, e);
                }
            }
        }
        return null;
    }

    static class Configuration {
        boolean mOnePushedAtomEvent = false;
        @VisibleForTesting
        Set<Integer> mPushedAtoms = new TreeSet<>();
        @VisibleForTesting
        Set<Integer> mPulledAtoms = new TreeSet<>();
        @VisibleForTesting
        String mAdditionalAllowedPackage = null;
        private final Set<Long> mTrackedMetrics = new HashSet<>();

        private void dumpMetrics(ConfigMetricsReportList reportList, Dumper dumper) {
            // We may get multiple reports. Take the last one.
            ConfigMetricsReport report = reportList.getReports(reportList.getReportsCount() - 1);
            for (StatsLogReport statsLog : report.getMetricsList()) {
                if (isTrackedMetric(statsLog.getMetricId())) {
                    LOGGER.info(statsLog.toString());
                    dumper.dump(statsLog);
                }
            }
        }

        boolean isTrackedMetric(long metricId) {
            return mTrackedMetrics.contains(metricId);
        }

        static boolean isPulledAtom(int atomId) {
            return atomId >= PULL_ATOM_START && atomId <= MAX_PLATFORM_ATOM_TAG
                    || atomId >= VENDOR_PULLED_ATOM_START_TAG;
        }

        void addAtom(Integer atom) {
            if (Atom.getDescriptor().findFieldByNumber(atom) == null) {
                LOGGER.severe("No such atom found: " + atom);
                return;
            }
            if (isPulledAtom(atom)) {
                mPulledAtoms.add(atom);
            } else {
                mPushedAtoms.add(atom);
            }
        }

        private boolean hasPulledAtoms() {
            return !mPulledAtoms.isEmpty();
        }

        private boolean hasPushedAtoms() {
            return !mPushedAtoms.isEmpty();
        }

        StatsdConfig createConfig() {
            long metricId = METRIC_ID_BASE;
            long atomMatcherId = ATOM_MATCHER_ID_BASE;

            StatsdConfig.Builder builder = baseBuilder();

            if (hasPulledAtoms()) {
                builder.addAtomMatcher(
                        createAtomMatcher(
                                Atom.APP_BREADCRUMB_REPORTED_FIELD_NUMBER,
                                APP_BREADCRUMB_MATCHER_ID));
            }

            for (int atomId : mPulledAtoms) {
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
                atomMatcherId++;
                mTrackedMetrics.add(metricId++);
            }

            // A simple atom matcher for each pushed atom.
            List<AtomMatcher> simpleAtomMatchers = new ArrayList<>();
            for (int atomId : mPushedAtoms) {
                final AtomMatcher atomMatcher = createAtomMatcher(atomId, atomMatcherId++);
                simpleAtomMatchers.add(atomMatcher);
                builder.addAtomMatcher(atomMatcher);
            }

            if (mOnePushedAtomEvent) {
                // Create a union event metric, using an matcher that matches all pulled atoms.
                AtomMatcher unionAtomMatcher = createUnionMatcher(simpleAtomMatchers,
                        atomMatcherId);
                builder.addAtomMatcher(unionAtomMatcher);
                EventMetric.Builder eventMetricBuilder = EventMetric.newBuilder();
                eventMetricBuilder.setId(metricId).setWhat(unionAtomMatcher.getId());
                builder.addEventMetric(eventMetricBuilder.build());
                mTrackedMetrics.add(metricId++);
            } else {
                // Create multiple event metrics, one per pulled atom.
                for (AtomMatcher atomMatcher : simpleAtomMatchers) {
                    EventMetric.Builder eventMetricBuilder = EventMetric.newBuilder();
                    eventMetricBuilder
                            .setId(metricId)
                            .setWhat(atomMatcher.getId());
                    builder.addEventMetric(eventMetricBuilder.build());
                    mTrackedMetrics.add(metricId++);
                }
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

        private AtomMatcher createUnionMatcher(List<AtomMatcher> simpleAtomMatchers,
                long atomMatcherId) {
            AtomMatcher.Combination.Builder combinationBuilder =
                    AtomMatcher.Combination.newBuilder();
            combinationBuilder.setOperation(StatsdConfigProto.LogicalOperation.OR);
            for (AtomMatcher matcher : simpleAtomMatchers) {
                combinationBuilder.addMatcher(matcher.getId());
            }
            AtomMatcher.Builder atomMatcherBuilder = AtomMatcher.newBuilder();
            atomMatcherBuilder.setId(atomMatcherId).setCombination(combinationBuilder.build());
            return atomMatcherBuilder.build();
        }

        private StatsdConfig.Builder baseBuilder() {
            ArrayList<String> allowedSources = new ArrayList<>();
            Collections.addAll(allowedSources, ALLOWED_LOG_SOURCES);
            if (mAdditionalAllowedPackage != null) {
                allowedSources.add(mAdditionalAllowedPackage);
            }
            return StatsdConfig.newBuilder()
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
        }
    }

    interface Dumper {
        void dump(StatsLogReport report);
    }

    static class BasicDumper implements Dumper {
        @Override
        public void dump(StatsLogReport report) {
            System.out.println(report.toString());
        }
    }

    static class TerseDumper extends BasicDumper {
        @Override
        public void dump(StatsLogReport report) {
            if (report.hasGaugeMetrics()) {
                dumpGaugeMetrics(report);
            }
            if (report.hasEventMetrics()) {
                dumpEventMetrics(report);
            }
        }
        void dumpEventMetrics(StatsLogReport report) {
            final List<StatsLog.EventMetricData> data = report.getEventMetrics().getDataList();
            if (data.isEmpty()) {
                return;
            }
            long firstTimestampNanos = data.get(0).getElapsedTimestampNanos();
            for (StatsLog.EventMetricData event : data) {
                final double deltaSec = (event.getElapsedTimestampNanos() - firstTimestampNanos)
                        / 1e9;
                System.out.println(
                        String.format("+%.3fs: %s", deltaSec, event.getAtom().toString()));
            }
        }
        void dumpGaugeMetrics(StatsLogReport report) {
            final List<StatsLog.GaugeMetricData> data = report.getGaugeMetrics().getDataList();
            if (data.isEmpty()) {
                return;
            }
            for (StatsLog.GaugeMetricData gauge : data) {
                System.out.println(gauge.toString());
            }
        }
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
            LOGGER.severe("Failed to remove config: " + e.getMessage());
        }
    }
}
