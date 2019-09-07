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
import java.io.InputStream;
import java.security.GeneralSecurityException;

/** Splits an input stream into chunks, which are to be encrypted separately. */
public interface Chunker {
    /**
     * Splits the input stream into chunks.
     *
     * @param inputStream The input stream.
     * @param chunkConsumer A function that processes each chunk as it is produced.
     * @throws IOException If there is a problem reading the input stream.
     * @throws GeneralSecurityException if the consumer function throws an error.
     */
    void chunkify(InputStream inputStream, ChunkConsumer chunkConsumer)
            throws IOException, GeneralSecurityException;

    /** Function that consumes chunks. */
    interface ChunkConsumer {
        /**
         * Invoked for each chunk.
         *
         * @param chunk Plaintext bytes of chunk.
         * @throws GeneralSecurityException if there is an issue encrypting the chunk.
         */
        void accept(byte[] chunk) throws GeneralSecurityException;
    }
}
