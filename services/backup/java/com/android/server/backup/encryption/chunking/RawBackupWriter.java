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
 * limitations under the License
 */

package com.android.server.backup.encryption.chunking;

import java.io.IOException;
import java.io.OutputStream;

/** Writes data straight to an output stream. */
public class RawBackupWriter implements BackupWriter {
    private final OutputStream outputStream;
    private long bytesWritten;

    /** Constructs a new writer which writes bytes to the given output stream. */
    public RawBackupWriter(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {
        outputStream.write(bytes);
        bytesWritten += bytes.length;
    }

    @Override
    public void writeChunk(long start, int length) throws IOException {
        throw new UnsupportedOperationException("RawBackupWriter cannot write existing chunks");
    }

    @Override
    public long getBytesWritten() {
        return bytesWritten;
    }

    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }
}
