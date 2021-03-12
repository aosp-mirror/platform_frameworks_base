/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.power;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings.Global;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides utils to dump/wipe pre-reboot information.
 */
final class PreRebootLogger {
    private static final String TAG = "PreRebootLogger";
    private static final String PREREBOOT_DIR = "prereboot";

    private static final String[] BUFFERS_TO_DUMP = {"system"};
    private static final String[] SERVICES_TO_DUMP = {Context.ROLLBACK_SERVICE, "package"};

    private static final Object sLock = new Object();
    private static final long MAX_DUMP_TIME = TimeUnit.SECONDS.toMillis(20);

    /**
     * Process pre-reboot information. Dump pre-reboot information to {@link #PREREBOOT_DIR} if
     * enabled {@link Settings.Global#ADB_ENABLED} and having active staged session; wipe dumped
     * information otherwise.
     */
    static void log(Context context) {
        log(context, getDumpDir());
    }

    @VisibleForTesting
    static void log(Context context, @NonNull File dumpDir) {
        if (needDump(context)) {
            dump(dumpDir, MAX_DUMP_TIME);
        } else {
            wipe(dumpDir);
        }
    }

    private static boolean needDump(Context context) {
        return Global.getInt(context.getContentResolver(), Global.ADB_ENABLED, 0) == 1
                && !context.getPackageManager().getPackageInstaller()
                        .getActiveStagedSessions().isEmpty();
    }

    @VisibleForTesting
    static void dump(@NonNull File dumpDir, @DurationMillisLong long maxWaitTime) {
        Slog.d(TAG, "Dumping pre-reboot information...");
        final AtomicBoolean done = new AtomicBoolean(false);
        final Thread t = new Thread(() -> {
            synchronized (sLock) {
                for (String buffer : BUFFERS_TO_DUMP) {
                    dumpLogsLocked(dumpDir, buffer);
                }
                for (String service : SERVICES_TO_DUMP) {
                    dumpServiceLocked(dumpDir, service);
                }
            }
            done.set(true);
        });
        t.start();

        try {
            t.join(maxWaitTime);
        } catch (InterruptedException e) {
            Slog.e(TAG, "Failed to dump pre-reboot information due to interrupted", e);
        }

        if (!done.get()) {
            Slog.w(TAG, "Failed to dump pre-reboot information due to timeout");
        }
    }

    private static void wipe(@NonNull File dumpDir) {
        Slog.d(TAG, "Wiping pre-reboot information...");
        synchronized (sLock) {
            for (File file : dumpDir.listFiles()) {
                file.delete();
            }
        }
    }

    private static File getDumpDir() {
        final File dumpDir = new File(Environment.getDataMiscDirectory(), PREREBOOT_DIR);
        if (!dumpDir.exists() || !dumpDir.isDirectory()) {
            throw new UnsupportedOperationException("Pre-reboot dump directory not found");
        }
        return dumpDir;
    }

    @GuardedBy("sLock")
    private static void dumpLogsLocked(@NonNull File dumpDir, @NonNull String buffer) {
        try {
            final File dumpFile = new File(dumpDir, buffer);
            if (dumpFile.createNewFile()) {
                dumpFile.setWritable(true /* writable */, true /* ownerOnly */);
            } else {
                // Wipes dumped information in existing file before recording new information.
                new FileWriter(dumpFile, false).flush();
            }

            final String[] cmdline =
                    {"logcat", "-d", "-b", buffer, "-f", dumpFile.getAbsolutePath()};
            Runtime.getRuntime().exec(cmdline).waitFor();
        } catch (IOException | InterruptedException e) {
            Slog.e(TAG, "Failed to dump system log buffer before reboot", e);
        }
    }

    @GuardedBy("sLock")
    private static void dumpServiceLocked(@NonNull File dumpDir, @NonNull String serviceName) {
        final IBinder binder = ServiceManager.checkService(serviceName);
        if (binder == null) {
            return;
        }

        try {
            final File dumpFile = new File(dumpDir, serviceName);
            final ParcelFileDescriptor fd = ParcelFileDescriptor.open(dumpFile,
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_WRITE_ONLY);
            binder.dump(fd.getFileDescriptor(), ArrayUtils.emptyArray(String.class));
        } catch (FileNotFoundException | RemoteException e) {
            Slog.e(TAG, String.format("Failed to dump %s service before reboot", serviceName), e);
        }
    }
}
