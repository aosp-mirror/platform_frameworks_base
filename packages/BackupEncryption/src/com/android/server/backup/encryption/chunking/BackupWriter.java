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

import java.io.IOException;

/** Writes backup data either as a diff script or as raw data, determined by the implementation. */
public interface BackupWriter {
    /** Writes the given bytes to the output. */
    void writeBytes(byte[] bytes) throws IOException;

    /**
     * Writes an existing chunk from the previous backup to the output.
     *
     * <p>Note: not all implementations support this method.
     */
    void writeChunk(long start, int length) throws IOException;

    /** Returns the number of bytes written, included bytes copied from the old file. */
    long getBytesWritten();

    /**
     * Indicates that no more bytes or chunks will be written.
     *
     * <p>After calling this, you may not call {@link #writeBytes(byte[])} or {@link
     * #writeChunk(long, int)}
     */
    void flush() throws IOException;
}
