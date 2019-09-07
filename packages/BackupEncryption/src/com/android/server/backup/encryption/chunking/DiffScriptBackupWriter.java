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

package com.android.server.backup.encryption.chunking;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.OutputStream;

/** Writes backup data to a diff script, using a {@link SingleStreamDiffScriptWriter}. */
public class DiffScriptBackupWriter implements BackupWriter {
    /**
     * The maximum size of a chunk in the diff script. The diff script writer {@code mWriter} will
     * buffer this many bytes in memory.
     */
    private static final int ENCRYPTION_DIFF_SCRIPT_MAX_CHUNK_SIZE_BYTES = 1024 * 1024;

    private final SingleStreamDiffScriptWriter mWriter;
    private long mBytesWritten;

    /**
     * Constructs a new writer which writes the diff script to the given output stream, using the
     * maximum new chunk size {@code ENCRYPTION_DIFF_SCRIPT_MAX_CHUNK_SIZE_BYTES}.
     */
    public static DiffScriptBackupWriter newInstance(OutputStream outputStream) {
        SingleStreamDiffScriptWriter writer =
                new SingleStreamDiffScriptWriter(
                        outputStream, ENCRYPTION_DIFF_SCRIPT_MAX_CHUNK_SIZE_BYTES);
        return new DiffScriptBackupWriter(writer);
    }

    @VisibleForTesting
    DiffScriptBackupWriter(SingleStreamDiffScriptWriter writer) {
        mWriter = writer;
    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {
        for (byte b : bytes) {
            mWriter.writeByte(b);
        }

        mBytesWritten += bytes.length;
    }

    @Override
    public void writeChunk(long start, int length) throws IOException {
        mWriter.writeChunk(start, length);
        mBytesWritten += length;
    }

    @Override
    public long getBytesWritten() {
        return mBytesWritten;
    }

    @Override
    public void flush() throws IOException {
        mWriter.flush();
    }
}
