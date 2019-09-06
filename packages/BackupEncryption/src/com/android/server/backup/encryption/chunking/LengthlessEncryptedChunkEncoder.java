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
import com.android.server.backup.encryption.chunk.ChunksMetadataProto;

import java.io.IOException;

/**
 * Encodes an {@link EncryptedChunk} as bytes without including any information about the length of
 * the chunk.
 *
 * <p>In order for us to decode the backup file during restore it must include a chunk ordering in
 * mode {@link ChunksMetadataProto#EXPLICIT_STARTS}, which contains the boundaries of the chunks in
 * the encrypted file. This information allows us to decode the backup file and divide it into
 * chunks without including the length of each chunk inline.
 *
 * <p>We use this implementation during full backup.
 */
public class LengthlessEncryptedChunkEncoder implements EncryptedChunkEncoder {
    @Override
    public void writeChunkToWriter(BackupWriter writer, EncryptedChunk chunk) throws IOException {
        writer.writeBytes(chunk.nonce());
        writer.writeBytes(chunk.encryptedBytes());
    }

    @Override
    public int getEncodedLengthOfChunk(EncryptedChunk chunk) {
        return chunk.nonce().length + chunk.encryptedBytes().length;
    }

    @Override
    @ChunkOrderingType
    public int getChunkOrderingType() {
        return ChunksMetadataProto.EXPLICIT_STARTS;
    }
}
