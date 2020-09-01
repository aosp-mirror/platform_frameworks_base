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
import java.io.OutputStream;

/** Writer that formats a Diff Script and writes it to an output source. */
interface DiffScriptWriter {
    /** Adds a new byte to the diff script. */
    void writeByte(byte b) throws IOException;

    /** Adds a known chunk to the diff script. */
    void writeChunk(long chunkStart, int chunkLength) throws IOException;

    /** Indicates that no more bytes or chunks will be added to the diff script. */
    void flush() throws IOException;

    interface Factory {
        DiffScriptWriter create(OutputStream outputStream);
    }
}
