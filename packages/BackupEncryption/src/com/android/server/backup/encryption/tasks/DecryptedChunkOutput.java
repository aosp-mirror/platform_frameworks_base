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

import java.io.Closeable;
import java.io.IOException;
import java.security.InvalidKeyException;

/**
 * Accepts the plaintext bytes of decrypted chunks and writes them to some output. Also keeps track
 * of the message digest of the chunks.
 */
public interface DecryptedChunkOutput extends Closeable {
    /**
     * Opens whatever output the implementation chooses, ready to process chunks.
     *
     * @return {@code this}, to allow use with try-with-resources
     */
    DecryptedChunkOutput open() throws IOException;

    /**
     * Writes the plaintext bytes of chunk to whatever output the implementation chooses. Also
     * updates the digest with the chunk.
     *
     * <p>You must call {@link #open()} before this method, and you may not call it after calling
     * {@link Closeable#close()}.
     *
     * @param plaintextBuffer An array containing the bytes of the plaintext of the chunk, starting
     *     at index 0.
     * @param length The length in bytes of the plaintext contained in {@code plaintextBuffer}.
     */
    void processChunk(byte[] plaintextBuffer, int length) throws IOException, InvalidKeyException;

    /**
     * Returns the message digest of all the chunks processed by {@link #processChunk}.
     *
     * <p>You must call {@link Closeable#close()} before calling this method.
     */
    byte[] getDigest();
}
