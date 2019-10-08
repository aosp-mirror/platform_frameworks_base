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
package com.android.statsd.shelltools.localdrive;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.StatsLog.ConfigMetricsReport;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.statsd.shelltools.Utils;

import com.google.common.io.Files;
import com.google.protobuf.TextFormat;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Tool for using statsd locally. Can upload a config and get the data. Handles
 * both binary and human-readable protos.
 * To make: make statsd_localdrive
 * To run: statsd_localdrive     (i.e.  ./out/host/linux-x86/bin/statsd_localdrive)
 */
public class LocalDrive {
    private static final boolean DEBUG = false;

    public static final int MIN_SDK = 29;
    public static final String MIN_CODENAME = "Q";

    public static final long DEFAULT_CONFIG_ID = 56789;

    public static final String BINARY_FLAG = "--binary";
    public static final String CLEAR_DATA = "--clear";
    public static final String NO_UID_MAP_FLAG = "--no-uid-map";

    public static final String HELP_STRING =
        "Usage:\n\n" +

        "statsd_localdrive upload CONFIG_FILE [CONFIG_ID] [--binary]\n" +
        "  Uploads the given statsd config file (in binary or human-readable-text format).\n" +
        "  If a config with this id already exists, removes it first.\n" +
        "    CONFIG_FILE    Location of config file on host.\n" +
        "    CONFIG_ID      Long ID to associate with this config. If absent, uses "
                                                                + DEFAULT_CONFIG_ID + ".\n" +
        "    --binary       Config is in binary format; otherwise, assumed human-readable text.\n" +
        // Similar to: adb shell cmd stats config update SHELL_UID CONFIG_ID
        "\n" +

        "statsd_localdrive update CONFIG_FILE [CONFIG_ID] [--binary]\n" +
        "  Same as upload, but does not remove the old config first (if it already exists).\n" +
        // Similar to: adb shell cmd stats config update SHELL_UID CONFIG_ID
        "\n" +

        "statsd_localdrive get-data [CONFIG_ID] [--clear] [--binary] [--no-uid-map]\n" +
        "  Prints the output statslog data (in binary or human-readable-text format).\n" +
        "    CONFIG_ID      Long ID of the config. If absent, uses " + DEFAULT_CONFIG_ID + ".\n" +
        "    --binary       Output should be in binary, instead of default human-readable text.\n" +
        "                       Binary output can be redirected as usual (e.g. > FILENAME).\n" +
        "    --no-uid-map   Do not include the uid-map (the very lengthy uid<-->pkgName map).\n" +
        "    --clear        Erase the data from statsd afterwards. Does not remove the config.\n" +
        // Similar to: adb shell cmd stats dump-report SHELL_UID CONFIG_ID [--keep_data]
        //                                                      --include_current_bucket --proto
        "\n" +

        "statsd_localdrive remove [CONFIG_ID]\n" +
        "  Removes the config.\n" +
        "    CONFIG_ID      Long ID of the config. If absent, uses " + DEFAULT_CONFIG_ID + ".\n" +
        // Equivalent to: adb shell cmd stats config remove SHELL_UID CONFIG_ID
        "\n" +

        "statsd_localdrive clear [CONFIG_ID]\n" +
        "  Clears the data associated with the config.\n" +
        "    CONFIG_ID      Long ID of the config. If absent, uses " + DEFAULT_CONFIG_ID + ".\n" +
        // Similar to: adb shell cmd stats dump-report SHELL_UID CONFIG_ID
        //                                                      --include_current_bucket --proto
        "";


    private static final Logger sLogger = Logger.getLogger(LocalDrive.class.getName());

    /** Usage: make statsd_localdrive && statsd_localdrive */
    public static void main(String[] args) {
        Utils.setUpLogger(sLogger, DEBUG);

        if (!Utils.isAcceptableStatsd(sLogger, MIN_SDK, MIN_CODENAME)) {
            sLogger.severe("LocalDrive only works with statsd versions for Android "
                    + MIN_CODENAME + " or higher.");
            return;
        }

        if (args.length > 0) {
            switch (args[0]) {
                case "clear":
                    cmdClear(args);
                    return;
                case "get-data":
                    cmdGetData(args);
                    return;
                case "remove":
                    cmdRemove(args);
                    return;
                case "update":
                    cmdUpdate(args);
                    return;
                case "upload":
                    cmdUpload(args);
                    return;
            }
        }
        printHelp();
    }

    private static void printHelp() {
        sLogger.info(HELP_STRING);
    }

    // upload CONFIG_FILE [CONFIG_ID] [--binary]
    private static boolean cmdUpload(String[] args) {
        return updateConfig(args, true);
    }

    // update CONFIG_FILE [CONFIG_ID] [--binary]
    private static boolean cmdUpdate(String[] args) {
        return updateConfig(args, false);
    }

    private static boolean updateConfig(String[] args, boolean removeOldConfig) {
        int argCount = args.length - 1; // Used up one for upload/update.

        // Get CONFIG_FILE
        if (argCount < 1) {
            sLogger.severe("No config file provided.");
            printHelp();
            return false;
        }
        final String origConfigLocation = args[1];
        if (!new File(origConfigLocation).exists()) {
            sLogger.severe("Error - Cannot find the provided config file: " + origConfigLocation);
            return false;
        }
        argCount--;

        // Get --binary
        boolean binary = contains(args, 2, BINARY_FLAG);
        if (binary) argCount --;

        // Get CONFIG_ID
        long configId;
        try {
            configId = getConfigId(argCount < 1, args, 2);
        } catch (NumberFormatException e) {
            sLogger.severe("Invalid config id provided.");
            printHelp();
            return false;
        }
        sLogger.fine(String.format("updateConfig with %s %d %b %b",
                origConfigLocation, configId, binary, removeOldConfig));

        // Remove the old config.
        if (removeOldConfig) {
            try {
                Utils.runCommand(null, sLogger, "adb", "shell", Utils.CMD_REMOVE_CONFIG,
                        Utils.SHELL_UID, String.valueOf(configId));
                Utils.getReportList(configId, true /* clearData */, true /* SHELL_UID */, sLogger);
            } catch (InterruptedException | IOException e) {
                sLogger.severe("Failed to remove config: " + e.getMessage());
                return false;
            }
        }

        // Upload the config.
        String configLocation;
        if (binary) {
            configLocation = origConfigLocation;
        } else {
            StatsdConfig.Builder builder = StatsdConfig.newBuilder();
            try {
                TextFormat.merge(new FileReader(origConfigLocation), builder);
            } catch (IOException e) {
                sLogger.severe("Failed to read config file " + origConfigLocation + ": "
                        + e.getMessage());
                return false;
            }

            try {
                File tempConfigFile = File.createTempFile("statsdconfig", ".config");
                tempConfigFile.deleteOnExit();
                Files.write(builder.build().toByteArray(), tempConfigFile);
                configLocation = tempConfigFile.getAbsolutePath();
            } catch (IOException e) {
                sLogger.severe("Failed to write temp config file: " + e.getMessage());
                return false;
            }
        }
        String remotePath = "/data/local/tmp/statsdconfig.config";
        try {
            Utils.runCommand(null, sLogger, "adb", "push", configLocation, remotePath);
            Utils.runCommand(null, sLogger, "adb", "shell", "cat", remotePath, "|",
                    Utils.CMD_UPDATE_CONFIG, Utils.SHELL_UID, String.valueOf(configId));
        } catch (InterruptedException | IOException e) {
            sLogger.severe("Failed to update config: " + e.getMessage());
            return false;
        }
        return true;
    }

    // get-data [CONFIG_ID] [--clear] [--binary] [--no-uid-map]
    private static boolean cmdGetData(String[] args) {
        boolean binary = contains(args, 1, BINARY_FLAG);
        boolean noUidMap = contains(args, 1, NO_UID_MAP_FLAG);
        boolean clearData = contains(args, 1, CLEAR_DATA);

        // Get CONFIG_ID
        int argCount = args.length - 1; // Used up one for get-data.
        if (binary) argCount--;
        if (noUidMap) argCount--;
        if (clearData) argCount--;
        long configId;
        try {
            configId = getConfigId(argCount < 1, args, 1);
        } catch (NumberFormatException e) {
            sLogger.severe("Invalid config id provided.");
            printHelp();
            return false;
        }
        sLogger.fine(String.format("cmdGetData with %d %b %b %b",
                configId, clearData, binary, noUidMap));

        // Get the StatsLog
        // Even if the args request no modifications, we still parse it to make sure it's valid.
        ConfigMetricsReportList reportList;
        try {
            reportList = Utils.getReportList(configId, clearData, true /* SHELL_UID */, sLogger);
        } catch (IOException | InterruptedException e) {
            sLogger.severe("Failed to get report list: " + e.getMessage());
            return false;
        }
        if (noUidMap) {
            ConfigMetricsReportList.Builder builder
                    = ConfigMetricsReportList.newBuilder(reportList);
            // Clear the reports, then add them back without their UidMap.
            builder.clearReports();
            for (ConfigMetricsReport report : reportList.getReportsList()) {
                builder.addReports(ConfigMetricsReport.newBuilder(report).clearUidMap());
            }
            reportList = builder.build();
        }

        if (!binary) {
            sLogger.info(reportList.toString());
        } else {
            try {
                System.out.write(reportList.toByteArray());
            } catch (IOException e) {
                sLogger.severe("Failed to output binary statslog proto: "
                        + e.getMessage());
                return false;
            }
        }
        return true;
    }

    // clear [CONFIG_ID]
    private static boolean cmdClear(String[] args) {
        // Get CONFIG_ID
        long configId;
        try {
            configId = getConfigId(false, args, 1);
        } catch (NumberFormatException e) {
            sLogger.severe("Invalid config id provided.");
            printHelp();
            return false;
        }
        sLogger.fine(String.format("cmdClear with %d", configId));

        try {
            Utils.getReportList(configId, true /* clearData */, true /* SHELL_UID */, sLogger);
        } catch (IOException | InterruptedException e) {
            sLogger.severe("Failed to get report list: " + e.getMessage());
            return false;
        }
        return true;
    }

    // remove [CONFIG_ID]
    private static boolean cmdRemove(String[] args) {
        // Get CONFIG_ID
        long configId;
        try {
            configId = getConfigId(false, args, 1);
        } catch (NumberFormatException e) {
            sLogger.severe("Invalid config id provided.");
            printHelp();
            return false;
        }
        sLogger.fine(String.format("cmdRemove with %d", configId));

        try {
            Utils.runCommand(null, sLogger, "adb", "shell", Utils.CMD_REMOVE_CONFIG,
                    Utils.SHELL_UID, String.valueOf(configId));
        } catch (InterruptedException | IOException e) {
            sLogger.severe("Failed to remove config: " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Searches through the array to see if it contains (precisely) the given value, starting
     * at the given firstIdx.
     */
    private static boolean contains(String[] array, int firstIdx, String value) {
        if (value == null) return false;
        if (firstIdx < 0) return false;
        for (int i = firstIdx; i < array.length; i++) {
            if (value.equals(array[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the config id from args[idx], or returns DEFAULT_CONFIG_ID if args[idx] does not exist.
     * If justUseDefault, overrides and just uses DEFAULT_CONFIG_ID instead.
     */
    private static long getConfigId(boolean justUseDefault, String[] args, int idx)
            throws NumberFormatException {
        if (justUseDefault || args.length <= idx || idx < 0) {
            return DEFAULT_CONFIG_ID;
        }
        try {
            return Long.valueOf(args[idx]);
        } catch (NumberFormatException e) {
            sLogger.severe("Bad config id provided: " + args[idx]);
            throw e;
        }
    }
}
