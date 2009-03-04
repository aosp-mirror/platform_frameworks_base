/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.os;

import android.os.FileUtils;
import android.os.Power;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Utility class for interacting with the Android recovery partition.
 * The recovery partition is a small standalone system which can perform
 * operations that are difficult while the main system is running, like
 * upgrading system software or reformatting the data partition.
 * Note that most of these operations must be run as root.
 *
 * @hide
 */
public class RecoverySystem {
    private static final String TAG = "RecoverySystem";  // for logging

    // Used to communicate with recovery.  See commands/recovery/recovery.c.
    private static File RECOVERY_DIR = new File("/cache/recovery");
    private static File COMMAND_FILE = new File(RECOVERY_DIR, "command");
    private static File LOG_FILE = new File(RECOVERY_DIR, "log");

    // Length limits for reading files.
    private static int LOG_FILE_MAX_LENGTH = 8 * 1024;

    /**
     * Reboot into the recovery system to install a system update.
     * @param update package to install (must be in /cache or /data).
     * @throws IOException if something goes wrong.
     */
    public static void rebootAndUpdate(File update) throws IOException {
        String path = update.getCanonicalPath();
        if (path.startsWith("/cache/")) {
            path = "CACHE:" + path.substring(7);
        } else if (path.startsWith("/data/")) {
            path = "DATA:" + path.substring(6);
        } else {
            throw new IllegalArgumentException(
                    "Must start with /cache or /data: " + path);
        }
        bootCommand("--update_package=" + path);
    }

    /**
     * Reboot into the recovery system to wipe the /data partition.
     * @param extras to add to the RECOVERY_COMPLETED intent after rebooting.
     * @throws IOException if something goes wrong.
     */
    public static void rebootAndWipe() throws IOException {
        bootCommand("--wipe_data");
    }

    /**
     * Reboot into the recovery system with the supplied argument.
     * @param arg to pass to the recovery utility.
     * @throws IOException if something goes wrong.
     */
    private static void bootCommand(String arg) throws IOException {
        RECOVERY_DIR.mkdirs();  // In case we need it
        COMMAND_FILE.delete();  // In case it's not writable
        LOG_FILE.delete();

        FileWriter command = new FileWriter(COMMAND_FILE);
        try {
            command.write(arg);
            command.write("\n");
        } finally {
            command.close();
        }

        // Having written the command file, go ahead and reboot
        Power.reboot("recovery");
        throw new IOException("Reboot failed (no permissions?)");
    }

    /**
     * Called after booting to process and remove recovery-related files.
     * @return the log file from recovery, or null if none was found.
     */
    public static String handleAftermath() {
        // Record the tail of the LOG_FILE
        String log = null;
        try {
            log = FileUtils.readTextFile(LOG_FILE, -LOG_FILE_MAX_LENGTH, "...\n");
        } catch (FileNotFoundException e) {
            Log.i(TAG, "No recovery log file");
        } catch (IOException e) {
            Log.e(TAG, "Error reading recovery log", e);
        }

        // Delete everything in RECOVERY_DIR
        String[] names = RECOVERY_DIR.list();
        for (int i = 0; names != null && i < names.length; i++) {
            File f = new File(RECOVERY_DIR, names[i]);
            if (!f.delete()) {
                Log.e(TAG, "Can't delete: " + f);
            } else {
                Log.i(TAG, "Deleted: " + f);
            }
        }

        return log;
    }
}
