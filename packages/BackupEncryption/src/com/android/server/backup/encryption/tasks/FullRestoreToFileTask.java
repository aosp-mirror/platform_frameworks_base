/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.tasks;

import static com.android.internal.util.Preconditions.checkArgument;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.FullRestoreDownloader;
import com.android.server.backup.encryption.FullRestoreDownloader.FinishType;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Reads a stream from a {@link FullRestoreDownloader} and writes it to a file for consumption by
 * {@link BackupFileDecryptorTask}.
 */
public class FullRestoreToFileTask {
    /**
     * Maximum number of bytes which the framework can request from the full restore data stream in
     * one call to {@link BackupTransport#getNextFullRestoreDataChunk}.
     */
    public static final int MAX_BYTES_FULL_RESTORE_CHUNK = 1024 * 32;

    /** Returned when the end of a backup stream has been reached. */
    private static final int END_OF_STREAM = -1;

    private final FullRestoreDownloader mFullRestoreDownloader;
    private final int mBufferSize;

    /**
     * Constructs a new instance which reads from the given package wrapper, using a buffer of size
     * {@link #MAX_BYTES_FULL_RESTORE_CHUNK}.
     */
    public FullRestoreToFileTask(FullRestoreDownloader fullRestoreDownloader) {
        this(fullRestoreDownloader, MAX_BYTES_FULL_RESTORE_CHUNK);
    }

    @VisibleForTesting
    FullRestoreToFileTask(FullRestoreDownloader fullRestoreDownloader, int bufferSize) {
        checkArgument(bufferSize > 0, "Buffer must have positive size");

        this.mFullRestoreDownloader = fullRestoreDownloader;
        this.mBufferSize = bufferSize;
    }

    /**
     * Downloads the backup file from the server and writes it to the given file.
     *
     * <p>At the end of the download (success or failure), closes the connection and sends a
     * Clearcut log.
     */
    public void restoreToFile(File targetFile) throws IOException {
        try (BufferedOutputStream outputStream =
                new BufferedOutputStream(new FileOutputStream(targetFile))) {
            byte[] buffer = new byte[mBufferSize];
            int bytesRead = mFullRestoreDownloader.readNextChunk(buffer);
            while (bytesRead != END_OF_STREAM) {
                outputStream.write(buffer, /* off=*/ 0, bytesRead);
                bytesRead = mFullRestoreDownloader.readNextChunk(buffer);
            }

            outputStream.flush();

            mFullRestoreDownloader.finish(FinishType.FINISHED);
        } catch (IOException e) {
            mFullRestoreDownloader.finish(FinishType.TRANSFER_FAILURE);
            throw e;
        }
    }
}
