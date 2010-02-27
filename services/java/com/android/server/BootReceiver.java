/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.RecoverySystem;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import java.io.File;
import java.io.IOException;

/**
 * Performs a number of miscellaneous, non-system-critical actions
 * after the system has finished booting.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    // Maximum size of a logged event (files get truncated if they're longer)
    private static final int LOG_SIZE = 65536;

    private static final File TOMBSTONE_DIR = new File("/data/tombstones");

    // Keep a reference to the observer so the finalizer doesn't disable it.
    private static FileObserver sTombstoneObserver = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            logBootEvents(context);
        } catch (Exception e) {
            Slog.e(TAG, "Can't log boot events", e);
        }

        try {
            RecoverySystem.handleAftermath();
        } catch (Exception e) {
            Slog.e(TAG, "Can't handle recovery aftermath", e);
        }

        try {
            // Start the load average overlay, if activated
            ContentResolver res = context.getContentResolver();
            if (Settings.System.getInt(res, Settings.System.SHOW_PROCESSES, 0) != 0) {
                Intent loadavg = new Intent(context, com.android.server.LoadAverageService.class);
                context.startService(loadavg);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Can't start load average service", e);
        }
    }

    private void logBootEvents(Context ctx) throws IOException {
        final DropBoxManager db = (DropBoxManager) ctx.getSystemService(Context.DROPBOX_SERVICE);
        final SharedPreferences prefs = ctx.getSharedPreferences("log_files", Context.MODE_PRIVATE);
        final String props = new StringBuilder()
            .append("Build: ").append(Build.FINGERPRINT).append("\n")
            .append("Hardware: ").append(Build.BOARD).append("\n")
            .append("Bootloader: ").append(Build.BOOTLOADER).append("\n")
            .append("Radio: ").append(Build.RADIO).append("\n")
            .append("Kernel: ")
            .append(FileUtils.readTextFile(new File("/proc/version"), 1024, "...\n"))
            .toString();

        if (db == null || prefs == null) return;

        if (SystemProperties.getLong("ro.runtime.firstboot", 0) == 0) {
            String now = Long.toString(System.currentTimeMillis());
            SystemProperties.set("ro.runtime.firstboot", now);
            db.addText("SYSTEM_BOOT", props);

            // Negative sizes mean to take the *tail* of the file (see FileUtils.readTextFile())
            addFileToDropBox(db, prefs, props, "/proc/last_kmsg",
                    -LOG_SIZE, "SYSTEM_LAST_KMSG");
            addFileToDropBox(db, prefs, props, "/cache/recovery/log",
                    -LOG_SIZE, "SYSTEM_RECOVERY_LOG");
            addFileToDropBox(db, prefs, props, "/data/dontpanic/apanic_console",
                    -LOG_SIZE, "APANIC_CONSOLE");
            addFileToDropBox(db, prefs, props, "/data/dontpanic/apanic_threads",
                    -LOG_SIZE, "APANIC_THREADS");
        } else {
            db.addText("SYSTEM_RESTART", props);
        }

        // Scan existing tombstones (in case any new ones appeared)
        File[] tombstoneFiles = TOMBSTONE_DIR.listFiles();
        for (int i = 0; tombstoneFiles != null && i < tombstoneFiles.length; i++) {
            addFileToDropBox(db, prefs, props, tombstoneFiles[i].getPath(),
                    LOG_SIZE, "SYSTEM_TOMBSTONE");
        }

        // Start watching for new tombstone files; will record them as they occur.
        // This gets registered with the singleton file observer thread.
        sTombstoneObserver = new FileObserver(TOMBSTONE_DIR.getPath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                try {
                    String filename = new File(TOMBSTONE_DIR, path).getPath();
                    addFileToDropBox(db, prefs, props, filename, LOG_SIZE, "SYSTEM_TOMBSTONE");
                } catch (IOException e) {
                    Slog.e(TAG, "Can't log tombstone", e);
                }
            }
        };

        sTombstoneObserver.startWatching();
    }

    private static void addFileToDropBox(
            DropBoxManager db, SharedPreferences prefs,
            String headers, String filename, int maxSize, String tag) throws IOException {
        if (!db.isTagEnabled(tag)) return;  // Slog.ing disabled

        File file = new File(filename);
        long fileTime = file.lastModified();
        if (fileTime <= 0) return;  // File does not exist

        long lastTime = prefs.getLong(filename, 0);
        if (lastTime == fileTime) return;  // Already logged this particular file
        prefs.edit().putLong(filename, fileTime).commit();

        StringBuilder report = new StringBuilder(headers).append("\n");
        report.append(FileUtils.readTextFile(file, maxSize, "[[TRUNCATED]]\n"));
        db.addText(tag, report.toString());
        Slog.i(TAG, "Slog.ing " + filename + " to DropBox (" + tag + ")");
    }
}
