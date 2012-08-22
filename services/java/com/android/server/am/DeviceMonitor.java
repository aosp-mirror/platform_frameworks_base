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

package com.android.server.am;

import android.os.SELinux;
import android.util.Slog;

import java.io.*;
import java.util.Arrays;

/**
 * Monitors device resources periodically for some period of time. Useful for
 * tracking down performance problems.
 */
class DeviceMonitor {

    private static final String LOG_TAG = DeviceMonitor.class.getName();

    /** Number of samples to take. */
    private static final int SAMPLE_COUNT = 10;

    /** Time to wait in ms between samples. */
    private static final int INTERVAL = 1000;

    /** Time to wait in ms between samples. */
    private static final int MAX_FILES = 30;

    private final byte[] buffer = new byte[1024];

    /** Is the monitor currently running? */
    private boolean running = false;

    private DeviceMonitor() {
        new Thread() {
            public void run() {
                monitor();
            }
        }.start();
    }

    /**
     * Loops continuously. Pauses until someone tells us to start monitoring.
     */
    @SuppressWarnings("InfiniteLoopStatement")
    private void monitor() {
        while (true) {
            waitForStart();

            purge();

            for (int i = 0; i < SAMPLE_COUNT; i++) {
                try {
                    dump();
                } catch (IOException e) {
                    Slog.w(LOG_TAG, "Dump failed.", e);
                }
                pause();
            }

            stop();
        }
    }

    private static final File PROC = new File("/proc");
    private static final File BASE = new File("/data/anr/");
    static {
        if (!BASE.isDirectory() && !BASE.mkdirs()) {
            throw new AssertionError("Couldn't create " + BASE + ".");
        }
        if (!SELinux.restorecon(BASE)) {
            throw new AssertionError("Couldn't restorecon " + BASE + ".");
        }
    }

    private static final File[] PATHS = {
        new File(PROC, "zoneinfo"),
        new File(PROC, "interrupts"),
        new File(PROC, "meminfo"),
        new File(PROC, "slabinfo"),
    };


    /**
     * Deletes old files.
     */
    private void purge() {
        File[] files = BASE.listFiles();
        int count = files.length - MAX_FILES;
        if (count > 0) {
            Arrays.sort(files);
            for (int i = 0; i < count; i++) {
                if (!files[i].delete()) {
                    Slog.w(LOG_TAG, "Couldn't delete " + files[i] + ".");
                }
            }
        }
    }

    /**
     * Dumps the current device stats to a new file.
     */
    private void dump() throws IOException {
        OutputStream out = new FileOutputStream(
                new File(BASE, String.valueOf(System.currentTimeMillis())));
        try {
            // Copy /proc/*/stat
            for (File processDirectory : PROC.listFiles()) {
                if (isProcessDirectory(processDirectory)) {
                    dump(new File(processDirectory, "stat"), out);
                }
            }

            // Copy other files.
            for (File file : PATHS) {
                dump(file, out);
            }
        } finally {
            closeQuietly(out);
        }
    }

    /**
     * Returns true if the given file represents a process directory.
     */
    private static boolean isProcessDirectory(File file) {
        try {
            Integer.parseInt(file.getName());
            return file.isDirectory();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Copies from a file to an output stream.
     */
    private void dump(File from, OutputStream out) throws IOException {
        writeHeader(from, out);
        
        FileInputStream in = null;
        try {
            in = new FileInputStream(from);
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Writes a header for the given file.
     */
    private static void writeHeader(File file, OutputStream out)
            throws IOException {
        String header = "*** " + file.toString() + "\n";
        out.write(header.getBytes());
    }

    /**
     * Closes the given resource. Logs exceptions.
     * @param closeable
     */
    private static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException e) {
            Slog.w(LOG_TAG, e);
        }
    }

    /**
     * Pauses momentarily before we start the next dump.
     */
    private void pause() {
        try {
            Thread.sleep(INTERVAL);
        } catch (InterruptedException e) { /* ignore */ }
    }

    /**
     * Stops dumping.
     */
    private synchronized void stop() {
        running = false;        
    }

    /**
     * Waits until someone starts us.
     */
    private synchronized void waitForStart() {
        while (!running) {
            try {
                wait();
            } catch (InterruptedException e) { /* ignore */ }
        }
    }

    /**
     * Instructs the monitoring to start if it hasn't already.
     */
    private synchronized void startMonitoring() {
        if (!running) {
            running = true;
            notifyAll();
        }
    }

    private static DeviceMonitor instance = new DeviceMonitor();

    /**
     * Starts monitoring if it hasn't started already.
     */
    static void start() {
        instance.startMonitoring();
    }
}
