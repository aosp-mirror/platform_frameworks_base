/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.crashrecovery;

import android.os.Environment;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Class containing helper methods for the CrashRecoveryModule.
 *
 * @hide
 */
public class CrashRecoveryUtils {
    private static final String TAG = "CrashRecoveryUtils";
    private static final long MAX_CRITICAL_INFO_DUMP_SIZE = 1000 * 1000; // ~1MB
    private static final Object sFileLock = new Object();

    /** Persist recovery related events in crashrecovery events file.**/
    public static void logCrashRecoveryEvent(int priority, String msg) {
        Slog.println(priority, TAG, msg);
        try {
            File fname = getCrashRecoveryEventsFile();
            synchronized (sFileLock) {
                FileOutputStream out = new FileOutputStream(fname, true);
                PrintWriter pw = new PrintWriter(out);
                String dateString = LocalDateTime.now(ZoneId.systemDefault()).toString();
                pw.println(dateString + ": " + msg);
                pw.close();
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to log CrashRecoveryEvents " + e.getMessage());
        }
    }

    /** Dump recovery related events from crashrecovery events file.**/
    public static void dumpCrashRecoveryEvents(IndentingPrintWriter pw) {
        pw.println("CrashRecovery Events: ");
        pw.increaseIndent();
        final File file = getCrashRecoveryEventsFile();
        final long skipSize = file.length() - MAX_CRITICAL_INFO_DUMP_SIZE;
        synchronized (sFileLock) {
            try (BufferedReader in = new BufferedReader(new FileReader(file))) {
                if (skipSize > 0) {
                    in.skip(skipSize);
                }
                String line;
                while ((line = in.readLine()) != null) {
                    pw.println(line);
                }
            } catch (IOException e) {
                Slog.e(TAG, "Unable to dump CrashRecoveryEvents " + e.getMessage());
            }
        }
        pw.decreaseIndent();
    }

    private static File getCrashRecoveryEventsFile() {
        File systemDir = new File(Environment.getDataDirectory(), "system");
        return new File(systemDir, "crashrecovery-events.txt");
    }
}
