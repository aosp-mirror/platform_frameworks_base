/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.android.server.backup.BackupManagerService.TAG;

import android.os.ParcelFileDescriptor;
import android.util.Slog;

import com.android.server.backup.Flags;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Low-level utility methods for full backup.
 */
public class FullBackupUtils {

    /**
     * Reads data from pipe and writes it to the stream.
     *
     * @param inPipe - pipe to read the data from.
     * @param out - stream to write the data to.
     * @throws IOException - in case of an error.
     */
    public static void routeSocketDataToOutput(ParcelFileDescriptor inPipe, OutputStream out)
            throws IOException {
        // We do not take close() responsibility for the pipe FD
        FileInputStream raw = new FileInputStream(inPipe.getFileDescriptor());
        DataInputStream in = new DataInputStream(raw);
        int chunkSizeInBytes = 32 * 1024; // 32KB
        if (Flags.enableMaxSizeWritesToPipes()) {
            // Linux pipe capacity (buffer size) is 16 pages where each page is 4KB
            chunkSizeInBytes = 64 * 1024; // 64KB
        }
        byte[] buffer = new byte[chunkSizeInBytes];
        int chunkTotal;
        while ((chunkTotal = in.readInt()) > 0) {
            while (chunkTotal > 0) {
                int toRead = (chunkTotal > buffer.length) ? buffer.length : chunkTotal;
                int nRead = in.read(buffer, 0, toRead);
                if (nRead < 0) {
                    Slog.e(TAG, "Unexpectedly reached end of file while reading data");
                    throw new EOFException();
                }
                out.write(buffer, 0, nRead);
                chunkTotal -= nRead;
            }
        }
    }
}
