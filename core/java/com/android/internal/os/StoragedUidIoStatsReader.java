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

package com.android.internal.os;

import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;


/**
 * Reads /proc/uid_io/stats which has the line format:
 *
 * uid: foreground_read_chars foreground_write_chars foreground_read_bytes foreground_write_bytes
 * background_read_chars background_write_chars background_read_bytes background_write_bytes
 * foreground_fsync background_fsync
 *
 * This provides the number of bytes/chars read/written in foreground/background for each uid.
 * The file contains a monotonically increasing count of bytes/chars for a single boot.
 */
public class StoragedUidIoStatsReader {

    private static final String TAG = StoragedUidIoStatsReader.class.getSimpleName();
    private static String sUidIoFile = "/proc/uid_io/stats";

    public StoragedUidIoStatsReader() {
    }

    @VisibleForTesting
    public StoragedUidIoStatsReader(String file) {
        sUidIoFile = file;
    }

    /**
     * Notifies when new data is available.
     */
    public interface Callback {

        /**
         * Provides data to the client.
         *
         * Note: Bytes are I/O events from a storage device. Chars are data requested by syscalls,
         *   and can be satisfied by caching.
         */
        void onUidStorageStats(int uid, long fgCharsRead, long fgCharsWrite, long fgBytesRead,
                long fgBytesWrite, long bgCharsRead, long bgCharsWrite, long bgBytesRead,
                long bgBytesWrite, long fgFsync, long bgFsync);
    }

    /**
     * Reads the proc file, calling into the callback with raw absolute value of I/O stats
     * for each UID.
     *
     * @param callback The callback to invoke for each line of the proc file.
     */
    public void readAbsolute(Callback callback) {
        final int oldMask = StrictMode.allowThreadDiskReadsMask();
        File file = new File(sUidIoFile);
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = TextUtils.split(line, " ");
                if (fields.length != 11) {
                    Slog.e(TAG, "Malformed entry in " + sUidIoFile + ": " + line);
                    continue;
                }
                try {
                    final String uidStr = fields[0];
                    final int uid = Integer.parseInt(fields[0], 10);
                    final long fgCharsRead = Long.parseLong(fields[1], 10);
                    final long fgCharsWrite = Long.parseLong(fields[2], 10);
                    final long fgBytesRead = Long.parseLong(fields[3], 10);
                    final long fgBytesWrite = Long.parseLong(fields[4], 10);
                    final long bgCharsRead = Long.parseLong(fields[5], 10);
                    final long bgCharsWrite = Long.parseLong(fields[6], 10);
                    final long bgBytesRead = Long.parseLong(fields[7], 10);
                    final long bgBytesWrite = Long.parseLong(fields[8], 10);
                    final long fgFsync = Long.parseLong(fields[9], 10);
                    final long bgFsync = Long.parseLong(fields[10], 10);
                    callback.onUidStorageStats(uid, fgCharsRead, fgCharsWrite, fgBytesRead,
                            fgBytesWrite, bgCharsRead, bgCharsWrite, bgBytesRead, bgBytesWrite,
                            fgFsync, bgFsync);
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Could not parse entry in " + sUidIoFile + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read " + sUidIoFile + ": " + e.getMessage());
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
        }
    }
}
