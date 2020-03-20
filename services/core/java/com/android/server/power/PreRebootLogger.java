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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Provides utils to dump/wipe pre-reboot information.
 */
final class PreRebootLogger {
    private static final String TAG = "PreRebootLogger";
    private static final String PREREBOOT_DIR = "prereboot";

    private static final String[] BUFFERS_TO_DUMP = {"system"};
    private static final String[] SERVICES_TO_DUMP = {Context.ROLLBACK_SERVICE, "package"};

    private static final Object sLock = new Object();

    /**
     * Process pre-reboot information. Dump pre-reboot information to {@link #PREREBOOT_DIR} if
     * enabled {@link Settings.Global#ADB_ENABLED}; wipe dumped information otherwise.
     */
    static void log(Context context) {
        log(context, getDumpDir());
    }

    @VisibleForTesting
    static void log(Context context, @NonNull File dumpDir) {
        if (Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1) {
            Slog.d(TAG, "Dumping pre-reboot information...");
            dump(dumpDir);
        } else {
            Slog.d(TAG, "Wiping pre-reboot information...");
            wipe(dumpDir);
        }
    }

    private static void dump(@NonNull File dumpDir) {
        synchronized (sLock) {
            for (String buffer : BUFFERS_TO_DUMP) {
                dumpLogsLocked(dumpDir, buffer);
            }
            for (String service : SERVICES_TO_DUMP) {
                dumpServiceLocked(dumpDir, service);
            }
        }
    }

    private static void wipe(@NonNull File dumpDir) {
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
            Slog.d(TAG, "Dump system log buffer before reboot fail", e);
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
            Slog.d(TAG, String.format("Dump %s service before reboot fail", serviceName), e);
        }
    }
}
