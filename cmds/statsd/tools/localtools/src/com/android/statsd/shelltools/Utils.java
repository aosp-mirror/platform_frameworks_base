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
package com.android.statsd.shelltools;

import com.android.os.StatsLog.ConfigMetricsReportList;

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

/**
 * Utilities for local use of statsd.
 */
public class Utils {

    public static final String CMD_UPDATE_CONFIG = "cmd stats config update";
    public static final String CMD_DUMP_REPORT = "cmd stats dump-report";
    public static final String CMD_REMOVE_CONFIG = "cmd stats config remove";

    public static final String SHELL_UID = "2000"; // Use shell, even if rooted.

    /**
     * Runs adb shell command with output directed to outputFile if non-null.
     */
    public static void runCommand(File outputFile, Logger logger, String... commands)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(commands);
        if (outputFile != null && outputFile.exists() && outputFile.canWrite()) {
            pb.redirectOutput(outputFile);
        }
        Process process = pb.start();

        // Capture any errors
        StringBuilder err = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        for (String line = br.readLine(); line != null; line = br.readLine()) {
            err.append(line).append('\n');
        }
        logger.severe(err.toString());

        // Check result
        if (process.waitFor() == 0) {
            logger.fine("Adb command successful.");
        } else {
            logger.severe("Abnormal adb shell cmd termination for: " + String.join(",", commands));
            throw new RuntimeException("Error running adb command: " + err.toString());
        }
    }

    /**
     * Dumps the report from the device and converts it to a ConfigMetricsReportList.
     * Erases the data if clearData is true.
     * @param configId id of the config
     * @param clearData whether to erase the report data from statsd after getting the report.
     * @param useShellUid Pulls data for the {@link SHELL_UID} instead of the caller's uid.
     * @param logger Logger to log error messages
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static ConfigMetricsReportList getReportList(long configId, boolean clearData,
            boolean useShellUid, Logger logger) throws IOException, InterruptedException {
        try {
            File outputFile = File.createTempFile("statsdret", ".bin");
            outputFile.deleteOnExit();
            runCommand(
                    outputFile,
                    logger,
                    "adb",
                    "shell",
                    CMD_DUMP_REPORT,
                    useShellUid ? SHELL_UID : "",
                    String.valueOf(configId),
                    clearData ? "" : "--keep_data",
                    "--include_current_bucket",
                    "--proto");
            ConfigMetricsReportList reportList =
                    ConfigMetricsReportList.parseFrom(new FileInputStream(outputFile));
            return reportList;
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            logger.severe("Failed to fetch and parse the statsd output report. "
                            + "Perhaps there is not a valid statsd config for the requested "
                            + (useShellUid ? ("uid=" + SHELL_UID + ", ") : "")
                            + "configId=" + configId
                            + ".");
            throw (e);
        }
    }

    public static void setUpLogger(Logger logger, boolean debug) {
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new LocalToolsFormatter());
        logger.setUseParentHandlers(false);
        if (debug) {
            handler.setLevel(Level.ALL);
            logger.setLevel(Level.ALL);
        }
        logger.addHandler(handler);
    }

    public static class LocalToolsFormatter extends Formatter {
        public String format(LogRecord record) {
            return record.getMessage() + "\n";
        }
    }
}
