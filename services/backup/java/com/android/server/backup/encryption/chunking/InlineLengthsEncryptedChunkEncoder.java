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

import com.android.server.backup.encryption.chunk.ChunkOrderingType;
import com.android.server.backup.encryption.chunk.ChunksMetadataProto;
import java.io.IOException;

/**
 * Encodes an {@link EncryptedChunk} as bytes, prepending the length of the chunk.
 *
 * <p>This allows us to decode the backup file during restore without any extra information about
 * the boundaries of the chunks. The backup file should contain a chunk ordering in mode {@link
 * ChunksMetadataProto#INLINE_LENGTHS}.
 *
 * <p>We use this implementation during key value backup.
 */
public class InlineLengthsEncryptedChunkEncoder implements EncryptedChunkEncoder {
    public static final int BYTES_LENGTH = Integer.SIZE / Byte.SIZE;

    private final LengthlessEncryptedChunkEncoder mLengthlessEncryptedChunkEncoder =
            new LengthlessEncryptedChunkEncoder();

    @Override
    public void writeChunkToWriter(BackupWriter writer, EncryptedChunk chunk) throws IOException {
        int length = mLengthlessEncryptedChunkEncoder.getEncodedLengthOfChunk(chunk);
        writer.writeBytes(toByteArray(length));
        mLengthlessEncryptedChunkEncoder.writeChunkToWriter(writer, chunk);
    }

    @Override
    public int getEncodedLengthOfChunk(EncryptedChunk chunk) {
        return BYTES_LENGTH + mLengthlessEncryptedChunkEncoder.getEncodedLengthOfChunk(chunk);
    }

    @Override
    @ChunkOrderingType
    public int getChunkOrderingType() {
        return ChunksMetadataProto.INLINE_LENGTHS;
    }

    /**
     * Returns a big-endian representation of {@code value} in a 4-element byte array; equivalent to
     * {@code ByteBuffer.allocate(4).putInt(value).array()}. For example, the input value {@code
     * 0x12131415} would yield the byte array {@code {0x12, 0x13, 0x14, 0x15}}.
     *
     * <p>Equivalent to guava's Ints.toByteArray.
     */
    static byte[] toByteArray(int value) {
        return new byte[] {
            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
        };
    }
}
