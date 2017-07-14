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
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.provider.Downloads;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/**
 * Performs a number of miscellaneous, non-system-critical actions
 * after the system has finished booting.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    // Maximum size of a logged event (files get truncated if they're longer).
    // Give userdebug builds a larger max to capture extra debug, esp. for last_kmsg.
    private static final int LOG_SIZE =
        SystemProperties.getInt("ro.debuggable", 0) == 1 ? 98304 : 65536;

    private static final File TOMBSTONE_DIR = new File("/data/tombstones");

    // The pre-froyo package and class of the system updater, which
    // ran in the system process.  We need to remove its packages here
    // in order to clean up after a pre-froyo-to-froyo update.
    private static final String OLD_UPDATER_PACKAGE =
        "com.google.android.systemupdater";
    private static final String OLD_UPDATER_CLASS =
        "com.google.android.systemupdater.SystemUpdateReceiver";

    // Keep a reference to the observer so the finalizer doesn't disable it.
    private static FileObserver sTombstoneObserver = null;

    private static final String LOG_FILES_FILE = "log-files.xml";
    private static final AtomicFile sFile = new AtomicFile(new File(
            Environment.getDataSystemDirectory(), LOG_FILES_FILE));
    private static final String LAST_HEADER_FILE = "last-header.txt";
    private static final File lastHeaderFile = new File(
            Environment.getDataSystemDirectory(), LAST_HEADER_FILE);

    @Override
    public void onReceive(final Context context, Intent intent) {
        // Log boot events in the background to avoid blocking the main thread with I/O
        new Thread() {
            @Override
            public void run() {
                try {
                    logBootEvents(context);
                } catch (Exception e) {
                    Slog.e(TAG, "Can't log boot events", e);
                }
                try {
                    boolean onlyCore = false;
                    try {
                        onlyCore = IPackageManager.Stub.asInterface(ServiceManager.getService(
                                "package")).isOnlyCoreApps();
                    } catch (RemoteException e) {
                    }
                    if (!onlyCore) {
                        removeOldUpdatePackages(context);
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Can't remove old update packages", e);
                }

            }
        }.start();
    }

    private void removeOldUpdatePackages(Context context) {
        Downloads.removeAllDownloadsByPackage(context, OLD_UPDATER_PACKAGE, OLD_UPDATER_CLASS);
    }

    private String getPreviousBootHeaders() {
        try {
            return FileUtils.readTextFile(lastHeaderFile, 0, null);
        } catch (IOException e) {
            Slog.e(TAG, "Error reading " + lastHeaderFile, e);
            return null;
        }
    }

    private String getCurrentBootHeaders() throws IOException {
        return new StringBuilder(512)
            .append("Build: ").append(Build.FINGERPRINT).append("\n")
            .append("Hardware: ").append(Build.BOARD).append("\n")
            .append("Revision: ")
            .append(SystemProperties.get("ro.revision", "")).append("\n")
            .append("Bootloader: ").append(Build.BOOTLOADER).append("\n")
            .append("Radio: ").append(Build.RADIO).append("\n")
            .append("Kernel: ")
            .append(FileUtils.readTextFile(new File("/proc/version"), 1024, "...\n"))
            .append("\n").toString();
    }


    private String getBootHeadersToLogAndUpdate() throws IOException {
        final String oldHeaders = getPreviousBootHeaders();
        final String newHeaders = getCurrentBootHeaders();

        try {
            FileUtils.stringToFile(lastHeaderFile, newHeaders);
        } catch (IOException e) {
            Slog.e(TAG, "Error writing " + lastHeaderFile, e);
        }

        if (oldHeaders == null) {
            // If we failed to read the old headers, use the current headers
            // but note this in the headers so we know
            return "isPrevious: false\n" + newHeaders;
        }

        return "isPrevious: true\n" + oldHeaders;
    }

    private void logBootEvents(Context ctx) throws IOException {
        final DropBoxManager db = (DropBoxManager) ctx.getSystemService(Context.DROPBOX_SERVICE);
        final String headers = getBootHeadersToLogAndUpdate();
        final String bootReason = SystemProperties.get("ro.boot.bootreason", null);

        String recovery = RecoverySystem.handleAftermath(ctx);
        if (recovery != null && db != null) {
            db.addText("SYSTEM_RECOVERY_LOG", headers + recovery);
        }

        String lastKmsgFooter = "";
        if (bootReason != null) {
            lastKmsgFooter = new StringBuilder(512)
                .append("\n")
                .append("Boot info:\n")
                .append("Last boot reason: ").append(bootReason).append("\n")
                .toString();
        }

        HashMap<String, Long> timestamps = readTimestamps();

        if (SystemProperties.getLong("ro.runtime.firstboot", 0) == 0) {
            if (StorageManager.inCryptKeeperBounce()) {
                // Encrypted, first boot to get PIN/pattern/password so data is tmpfs
                // Don't set ro.runtime.firstboot so that we will do this again
                // when data is properly mounted
            } else {
                String now = Long.toString(System.currentTimeMillis());
                SystemProperties.set("ro.runtime.firstboot", now);
            }
            if (db != null) db.addText("SYSTEM_BOOT", headers);

            // Negative sizes mean to take the *tail* of the file (see FileUtils.readTextFile())
            addFileWithFootersToDropBox(db, timestamps, headers, lastKmsgFooter,
                    "/proc/last_kmsg", -LOG_SIZE, "SYSTEM_LAST_KMSG");
            addFileWithFootersToDropBox(db, timestamps, headers, lastKmsgFooter,
                    "/sys/fs/pstore/console-ramoops", -LOG_SIZE, "SYSTEM_LAST_KMSG");
            addFileWithFootersToDropBox(db, timestamps, headers, lastKmsgFooter,
                    "/sys/fs/pstore/console-ramoops-0", -LOG_SIZE, "SYSTEM_LAST_KMSG");
            addFileToDropBox(db, timestamps, headers, "/cache/recovery/log", -LOG_SIZE,
                    "SYSTEM_RECOVERY_LOG");
            addFileToDropBox(db, timestamps, headers, "/cache/recovery/last_kmsg",
                    -LOG_SIZE, "SYSTEM_RECOVERY_KMSG");
            addAuditErrorsToDropBox(db, timestamps, headers, -LOG_SIZE, "SYSTEM_AUDIT");
            addFsckErrorsToDropBox(db, timestamps, headers, -LOG_SIZE, "SYSTEM_FSCK");
        } else {
            if (db != null) db.addText("SYSTEM_RESTART", headers);
        }

        // Scan existing tombstones (in case any new ones appeared)
        File[] tombstoneFiles = TOMBSTONE_DIR.listFiles();
        for (int i = 0; tombstoneFiles != null && i < tombstoneFiles.length; i++) {
            if (tombstoneFiles[i].isFile()) {
                addFileToDropBox(db, timestamps, headers, tombstoneFiles[i].getPath(),
                        LOG_SIZE, "SYSTEM_TOMBSTONE");
            }
        }

        writeTimestamps(timestamps);

        // Start watching for new tombstone files; will record them as they occur.
        // This gets registered with the singleton file observer thread.
        sTombstoneObserver = new FileObserver(TOMBSTONE_DIR.getPath(), FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                HashMap<String, Long> timestamps = readTimestamps();
                try {
                    File file = new File(TOMBSTONE_DIR, path);
                    if (file.isFile()) {
                        addFileToDropBox(db, timestamps, headers, file.getPath(), LOG_SIZE,
                                "SYSTEM_TOMBSTONE");
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Can't log tombstone", e);
                }
                writeTimestamps(timestamps);
            }
        };

        sTombstoneObserver.startWatching();
    }

    private static void addFileToDropBox(
            DropBoxManager db, HashMap<String, Long> timestamps,
            String headers, String filename, int maxSize, String tag) throws IOException {
        addFileWithFootersToDropBox(db, timestamps, headers, "", filename, maxSize, tag);
    }

    private static void addFileWithFootersToDropBox(
            DropBoxManager db, HashMap<String, Long> timestamps,
            String headers, String footers, String filename, int maxSize,
            String tag) throws IOException {
        if (db == null || !db.isTagEnabled(tag)) return;  // Logging disabled

        File file = new File(filename);
        long fileTime = file.lastModified();
        if (fileTime <= 0) return;  // File does not exist

        if (timestamps.containsKey(filename) && timestamps.get(filename) == fileTime) {
            return;  // Already logged this particular file
        }

        timestamps.put(filename, fileTime);

        Slog.i(TAG, "Copying " + filename + " to DropBox (" + tag + ")");
        db.addText(tag, headers + FileUtils.readTextFile(file, maxSize, "[[TRUNCATED]]\n") +
                footers);
    }

    private static void addAuditErrorsToDropBox(DropBoxManager db,
            HashMap<String, Long> timestamps, String headers, int maxSize, String tag)
            throws IOException {
        if (db == null || !db.isTagEnabled(tag)) return;  // Logging disabled
        Slog.i(TAG, "Copying audit failures to DropBox");

        File file = new File("/proc/last_kmsg");
        long fileTime = file.lastModified();
        if (fileTime <= 0) {
            file = new File("/sys/fs/pstore/console-ramoops");
            fileTime = file.lastModified();
            if (fileTime <= 0) {
                file = new File("/sys/fs/pstore/console-ramoops-0");
                fileTime = file.lastModified();
            }
        }

        if (fileTime <= 0) return;  // File does not exist

        if (timestamps.containsKey(tag) && timestamps.get(tag) == fileTime) {
            return;  // Already logged this particular file
        }

        timestamps.put(tag, fileTime);

        String log = FileUtils.readTextFile(file, maxSize, "[[TRUNCATED]]\n");
        StringBuilder sb = new StringBuilder();
        for (String line : log.split("\n")) {
            if (line.contains("audit")) {
                sb.append(line + "\n");
            }
        }
        Slog.i(TAG, "Copied " + sb.toString().length() + " worth of audits to DropBox");
        db.addText(tag, headers + sb.toString());
    }

    private static void addFsckErrorsToDropBox(DropBoxManager db,
            HashMap<String, Long> timestamps, String headers, int maxSize, String tag)
            throws IOException {
        boolean upload_needed = false;
        if (db == null || !db.isTagEnabled(tag)) return;  // Logging disabled
        Slog.i(TAG, "Checking for fsck errors");

        File file = new File("/dev/fscklogs/log");
        long fileTime = file.lastModified();
        if (fileTime <= 0) return;  // File does not exist

        String log = FileUtils.readTextFile(file, maxSize, "[[TRUNCATED]]\n");
        StringBuilder sb = new StringBuilder();
        for (String line : log.split("\n")) {
            if (line.contains("FILE SYSTEM WAS MODIFIED")) {
                upload_needed = true;
                break;
            }
        }

        if (upload_needed) {
            addFileToDropBox(db, timestamps, headers, "/dev/fscklogs/log", maxSize, tag);
        }

        // Remove the file so we don't re-upload if the runtime restarts.
        file.delete();
    }

    private static HashMap<String, Long> readTimestamps() {
        synchronized (sFile) {
            HashMap<String, Long> timestamps = new HashMap<String, Long>();
            boolean success = false;
            try (final FileInputStream stream = sFile.openRead()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());

                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    ;
                }

                if (type != XmlPullParser.START_TAG) {
                    throw new IllegalStateException("no start tag found");
                }

                int outerDepth = parser.getDepth();  // Skip the outer <log-files> tag.
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    String tagName = parser.getName();
                    if (tagName.equals("log")) {
                        final String filename = parser.getAttributeValue(null, "filename");
                        final long timestamp = Long.valueOf(parser.getAttributeValue(
                                    null, "timestamp"));
                        timestamps.put(filename, timestamp);
                    } else {
                        Slog.w(TAG, "Unknown tag: " + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }
                success = true;
            } catch (FileNotFoundException e) {
                Slog.i(TAG, "No existing last log timestamp file " + sFile.getBaseFile() +
                        "; starting empty");
            } catch (IOException e) {
                Slog.w(TAG, "Failed parsing " + e);
            } catch (IllegalStateException e) {
                Slog.w(TAG, "Failed parsing " + e);
            } catch (NullPointerException e) {
                Slog.w(TAG, "Failed parsing " + e);
            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Failed parsing " + e);
            } finally {
                if (!success) {
                    timestamps.clear();
                }
            }
            return timestamps;
        }
    }

    private void writeTimestamps(HashMap<String, Long> timestamps) {
        synchronized (sFile) {
            final FileOutputStream stream;
            try {
                stream = sFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write timestamp file: " + e);
                return;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.startTag(null, "log-files");

                Iterator<String> itor = timestamps.keySet().iterator();
                while (itor.hasNext()) {
                    String filename = itor.next();
                    out.startTag(null, "log");
                    out.attribute(null, "filename", filename);
                    out.attribute(null, "timestamp", timestamps.get(filename).toString());
                    out.endTag(null, "log");
                }

                out.endTag(null, "log-files");
                out.endDocument();

                sFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write timestamp file, using the backup: " + e);
                sFile.failWrite(stream);
            }
        }
    }
}
