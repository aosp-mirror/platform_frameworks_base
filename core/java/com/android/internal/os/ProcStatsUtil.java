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

import android.annotation.Nullable;
import android.os.StrictMode;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Utility functions for reading {@code proc} files
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ProcStatsUtil {

    private static final boolean DEBUG = false;

    private static final String TAG = "ProcStatsUtil";

    /**
     * How much to read into a buffer when reading a proc file
     */
    private static final int READ_SIZE = 1024;

    /**
     * Class only contains static utility functions, and should not be instantiated
     */
    private ProcStatsUtil() {
    }

    /**
     * Read a {@code proc} file where the contents are separated by null bytes. Replaces the null
     * bytes with spaces, and removes any trailing null bytes
     *
     * @param path path of the file to read
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    @Nullable
    public static String readNullSeparatedFile(String path) {
        String contents = readSingleLineProcFile(path);
        if (contents == null) {
            return null;
        }

        // Content is either double-null terminated, or terminates at end of line. Remove anything
        // after the double-null
        final int endIndex = contents.indexOf("\0\0");
        if (endIndex != -1) {
            contents = contents.substring(0, endIndex);
        }

        // Change the null-separated contents into space-seperated
        return contents.replace("\0", " ");
    }

    /**
     * Read a {@code proc} file that contains a single line (e.g. {@code /proc/$PID/cmdline}, {@code
     * /proc/$PID/comm})
     *
     * @param path path of the file to read
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    @Nullable
    public static String readSingleLineProcFile(String path) {
        return readTerminatedProcFile(path, (byte) '\n');
    }

    /**
     * Read a {@code proc} file that terminates with a specific byte
     *
     * @param path path of the file to read
     * @param terminator byte that terminates the file. We stop reading once this character is
     * seen, or at the end of the file
     */
    @Nullable
    public static String readTerminatedProcFile(String path, byte terminator) {
        // Permit disk reads here, as /proc isn't really "on disk" and should be fast.
        // TODO: make BlockGuard ignore /proc/ and /sys/ files perhaps?
        final int savedPolicy = StrictMode.allowThreadDiskReadsMask();
        try {
            return readTerminatedProcFileInternal(path, terminator);
        } finally {
            StrictMode.setThreadPolicyMask(savedPolicy);
        }
    }

    private static String readTerminatedProcFileInternal(String path, byte terminator) {
        try (FileInputStream is = new FileInputStream(path)) {
            ByteArrayOutputStream byteStream = null;
            final byte[] buffer = new byte[READ_SIZE];
            while (true) {
                // Read file into buffer
                final int len = is.read(buffer);
                if (len <= 0) {
                    // If we've read nothing, we're done
                    break;
                }

                // Find the terminating character
                int terminatingIndex = -1;
                for (int i = 0; i < len; i++) {
                    if (buffer[i] == terminator) {
                        terminatingIndex = i;
                        break;
                    }
                }
                final boolean foundTerminator = terminatingIndex != -1;

                // If we have found it and the byte stream isn't initialized, we don't need to
                // initialize it and can return the string here
                if (foundTerminator && byteStream == null) {
                    return new String(buffer, 0, terminatingIndex);
                }

                // Initialize the byte stream
                if (byteStream == null) {
                    byteStream = new ByteArrayOutputStream(READ_SIZE);
                }

                // Write the whole buffer if terminator not found, or up to the terminator if found
                byteStream.write(buffer, 0, foundTerminator ? terminatingIndex : len);

                // If we've found the terminator, we can finish
                if (foundTerminator) {
                    break;
                }
            }

            // If the byte stream is null at the end, this means that we have read an empty file
            if (byteStream == null) {
                return "";
            }
            return byteStream.toString();
        } catch (IOException e) {
            if (DEBUG) {
                Slog.d(TAG, "Failed to open proc file", e);
            }
            return null;
        }
    }
}
