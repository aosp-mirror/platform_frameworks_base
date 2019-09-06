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

import com.android.server.backup.encryption.chunk.ChunkOrderingType;

import java.io.IOException;

/** Encodes an {@link EncryptedChunk} as bytes to write to the encrypted backup file. */
public interface EncryptedChunkEncoder {
    /**
     * Encodes the given chunk and asks the writer to write it.
     *
     * <p>The chunk will be encoded in the format [nonce]+[encrypted data].
     *
     * <p>TODO(b/116575321): Choose a more descriptive method name after the code move is done.
     */
    void writeChunkToWriter(BackupWriter writer, EncryptedChunk chunk) throws IOException;

    /**
     * Returns the length in bytes that this chunk would be if encoded with {@link
     * #writeChunkToWriter}.
     */
    int getEncodedLengthOfChunk(EncryptedChunk chunk);

    /**
     * Returns the {@link ChunkOrderingType} that must be included in the backup file, when using
     * this decoder, so that the file may be correctly decoded.
     */
    @ChunkOrderingType
    int getChunkOrderingType();
}
