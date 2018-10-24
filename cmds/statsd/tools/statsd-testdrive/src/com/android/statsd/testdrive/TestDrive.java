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
package com.android.statsd.testdrive;

import com.android.internal.os.StatsdConfigProto.AtomMatcher;
import com.android.internal.os.StatsdConfigProto.SimpleAtomMatcher;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.AtomsProto.Atom;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;

import com.google.common.io.Files;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class TestDrive {

    public static final int PULL_ATOM_START = 10000;
    public static final long ATOM_MATCHER_ID = 1234567;

    public static final String UPDATE_CONFIG_CMD = "cmd stats config update";
    public static final String DUMP_REPORT_CMD = "cmd stats dump-report";
    public static final String REMOVE_CONFIG_CMD = "cmd stats config remove";
    public static final String CONFIG_UID = "2000"; // shell uid
    public static final long CONFIG_ID = 54321;

    private static boolean mIsPushedAtom = false;

    private static final Logger logger = Logger.getLogger(TestDrive.class.getName());

    public static void main(String[] args) {
        if (args.length != 1) {
            logger.log(Level.SEVERE, "Usage: ./test_drive <atomId>");
            return;
        }
        int atomId;
        try {
            atomId = Integer.valueOf(args[0]);
        } catch (NumberFormatException e) {
            logger.log(Level.SEVERE, "Bad atom id provided: " + args[0]);
            return;
        }
        if (Atom.getDescriptor().findFieldByNumber(atomId) == null) {
            logger.log(Level.SEVERE, "No such atom found: " + args[0]);
            return;
        }
        mIsPushedAtom = atomId < PULL_ATOM_START;

        TestDrive testDrive = new TestDrive();
        TestDriveFormatter formatter = new TestDriveFormatter();
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(formatter);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);

        try {
            StatsdConfig config = testDrive.createConfig(atomId);
            if (config == null) {
                logger.log(Level.SEVERE, "Failed to create valid config.");
                return;
            }
            testDrive.pushConfig(config);
            logger.info("Pushed the following config to statsd:");
            logger.info(config.toString());
            if (mIsPushedAtom) {
                logger.info(
                        "Now please play with the device to trigger the event. All events should "
                                + "be dumped after 1 min ...");
                Thread.sleep(60_000);
            } else {
                // wait for 2 min
                logger.info("Now wait for 2 minutes ...");
                Thread.sleep(120_000);
            }
            testDrive.dumpMetrics();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to test drive: " + e.getMessage());
        } finally {
            testDrive.removeConfig();
        }
    }

    private void pushConfig(StatsdConfig config) throws IOException, InterruptedException {
        File configFile = File.createTempFile("statsdconfig", ".config");
        configFile.deleteOnExit();
        Files.write(config.toByteArray(), configFile);
        String remotePath = "/data/local/tmp/" + configFile.getName();
        runCommand(null, "adb", "push", configFile.getAbsolutePath(), remotePath);
        runCommand(
                null, "adb", "shell", "cat", remotePath, "|", UPDATE_CONFIG_CMD,
                String.valueOf(CONFIG_ID));
    }

    private void removeConfig() {
        try {
            runCommand(null, "adb", "shell", REMOVE_CONFIG_CMD, String.valueOf(CONFIG_ID));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to remove config: " + e.getMessage());
        }
    }

    // Runs a shell command. Output should go to outputFile. Returns error string.
    private String runCommand(File outputFile, String... commands)
            throws IOException, InterruptedException {
        // Run macro on target
        ProcessBuilder pb = new ProcessBuilder(commands);
        // pb.redirectErrorStream(true);

        if (outputFile != null && outputFile.exists() && outputFile.canWrite()) {
            pb.redirectOutput(outputFile);
        }
        Process process = pb.start();

        // capture any errors
        StringBuilder out = new StringBuilder();
        // Read output
        BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String line = null, previous = null;
        while ((line = br.readLine()) != null) {
            if (!line.equals(previous)) {
                previous = line;
                out.append(line).append('\n');
                logger.fine(line);
            }
        }

        // Check result
        if (process.waitFor() == 0) {
            logger.fine("Success!");
        } else {
            // Abnormal termination: Log command parameters and output and throw ExecutionException
            logger.log(Level.SEVERE, out.toString());
        }
        return out.toString();
    }

    private StatsdConfig createConfig(int atomId) {
        try {
            if (mIsPushedAtom) {
                return createSimpleEventMetricConfig(atomId);
            } else {
                return createSimpleGaugeMetricConfig(atomId);
            }
        } catch (ParseException e) {
            logger.log(
                    Level.SEVERE,
                    "Failed to parse the config! line: "
                            + e.getLine()
                            + " col: "
                            + e.getColumn()
                            + " "
                            + e.getMessage());
        }
        return null;
    }

    private StatsdConfig createSimpleEventMetricConfig(int atomId) throws ParseException {
        StatsdConfig.Builder baseBuilder = getSimpleEventMetricBaseConfig();
        baseBuilder.addAtomMatcher(createAtomMatcher(atomId));
        return baseBuilder.build();
    }

    private StatsdConfig createSimpleGaugeMetricConfig(int atomId) throws ParseException {
        StatsdConfig.Builder baseBuilder = getSimpleGaugeMetricBaseConfig();
        baseBuilder.addAtomMatcher(createAtomMatcher(atomId));
        return baseBuilder.build();
    }

    private AtomMatcher createAtomMatcher(int atomId) {
        AtomMatcher.Builder atomMatcherBuilder = AtomMatcher.newBuilder();
        atomMatcherBuilder
                .setId(ATOM_MATCHER_ID)
                .setSimpleAtomMatcher(SimpleAtomMatcher.newBuilder().setAtomId(atomId));
        return atomMatcherBuilder.build();
    }

    private StatsdConfig.Builder getSimpleEventMetricBaseConfig() throws ParseException {
        StatsdConfig.Builder builder = StatsdConfig.newBuilder();
        TextFormat.merge(EVENT_BASE_CONFIG_SRTR, builder);
        return builder;
    }

    private StatsdConfig.Builder getSimpleGaugeMetricBaseConfig() throws ParseException {
        StatsdConfig.Builder builder = StatsdConfig.newBuilder();
        TextFormat.merge(GAUGE_BASE_CONFIG_STR, builder);
        return builder;
    }

    private ConfigMetricsReportList getReportList() throws Exception {
        try {
            File outputFile = File.createTempFile("statsdret", ".bin");
            outputFile.deleteOnExit();
            runCommand(
                    outputFile,
                    "adb",
                    "shell",
                    DUMP_REPORT_CMD,
                    String.valueOf(CONFIG_ID),
                    "--include_current_bucket",
                    "--proto");
            ConfigMetricsReportList reportList =
                    ConfigMetricsReportList.parseFrom(new FileInputStream(outputFile));
            return reportList;
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            logger.log(
                    Level.SEVERE,
                    "Failed to fetch and parse the statsd output report. "
                            + "Perhaps there is not a valid statsd config for the requested "
                            + "uid="
                            + CONFIG_UID
                            + ", id="
                            + CONFIG_ID
                            + ".");
            throw (e);
        }
    }

    private void dumpMetrics() throws Exception {
        ConfigMetricsReportList reportList = getReportList();
        // We may get multiple reports. Take the last one.
        ConfigMetricsReport report = reportList.getReports(reportList.getReportsCount() - 1);
        // Really should be only one metric.
        if (report.getMetricsCount() != 1) {
            logger.log(Level.SEVERE,
                    "Only one report metric expected, got " + report.getMetricsCount());
            return;
        }

        logger.info("Got following metric data dump:");
        logger.info(report.getMetrics(0).toString());
    }

    private static final String EVENT_BASE_CONFIG_SRTR =
            "id: 12345\n"
                    + "event_metric {\n"
                    + "  id: 1111\n"
                    + "  what: 1234567\n"
                    + "}\n"
                    + "allowed_log_source: \"AID_GRAPHICS\"\n"
                    + "allowed_log_source: \"AID_INCIDENTD\"\n"
                    + "allowed_log_source: \"AID_STATSD\"\n"
                    + "allowed_log_source: \"AID_RADIO\"\n"
                    + "allowed_log_source: \"com.android.systemui\"\n"
                    + "allowed_log_source: \"com.android.vending\"\n"
                    + "allowed_log_source: \"AID_SYSTEM\"\n"
                    + "allowed_log_source: \"AID_ROOT\"\n"
                    + "allowed_log_source: \"AID_BLUETOOTH\"\n"
                    + "\n"
                    + "hash_strings_in_metric_report: false";

    private static final String GAUGE_BASE_CONFIG_STR =
            "id: 56789\n"
                    + "gauge_metric {\n"
                    + "  id: 2222\n"
                    + "  what: 1234567\n"
                    + "  gauge_fields_filter {\n"
                    + "    include_all: true\n"
                    + "  }\n"
                    + "  bucket: ONE_MINUTE\n"
                    + "}\n"
                    + "allowed_log_source: \"AID_GRAPHICS\"\n"
                    + "allowed_log_source: \"AID_INCIDENTD\"\n"
                    + "allowed_log_source: \"AID_STATSD\"\n"
                    + "allowed_log_source: \"AID_RADIO\"\n"
                    + "allowed_log_source: \"com.android.systemui\"\n"
                    + "allowed_log_source: \"com.android.vending\"\n"
                    + "allowed_log_source: \"AID_SYSTEM\"\n"
                    + "allowed_log_source: \"AID_ROOT\"\n"
                    + "allowed_log_source: \"AID_BLUETOOTH\"\n"
                    + "\n"
                    + "hash_strings_in_metric_report: false";

    public static class TestDriveFormatter extends Formatter {
        public String format(LogRecord record) {
            return record.getMessage() + "\n";
        }
    }
}
