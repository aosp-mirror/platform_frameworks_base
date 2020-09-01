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

package com.android.server.backup.encryption.chunk;

import java.util.Arrays;

/**
 * Holds the bytes of an encrypted {@link ChunksMetadataProto.ChunkOrdering}.
 *
 * <p>TODO(b/116575321): After all code is ported, remove the factory method and rename
 * encryptedChunkOrdering() to getBytes().
 */
public class EncryptedChunkOrdering {
    /**
     * Constructs a new object holding the given bytes of an encrypted {@link
     * ChunksMetadataProto.ChunkOrdering}.
     *
     * <p>Note that this just holds an ordering which is already encrypted, it does not encrypt the
     * ordering.
     */
    public static EncryptedChunkOrdering create(byte[] encryptedChunkOrdering) {
        return new EncryptedChunkOrdering(encryptedChunkOrdering);
    }

    private final byte[] mEncryptedChunkOrdering;

    /** Get the encrypted chunk ordering */
    public byte[] encryptedChunkOrdering() {
        return mEncryptedChunkOrdering;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EncryptedChunkOrdering)) {
            return false;
        }

        EncryptedChunkOrdering encryptedChunkOrdering = (EncryptedChunkOrdering) o;
        return Arrays.equals(
                mEncryptedChunkOrdering, encryptedChunkOrdering.mEncryptedChunkOrdering);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mEncryptedChunkOrdering);
    }

    private EncryptedChunkOrdering(byte[] encryptedChunkOrdering) {
        mEncryptedChunkOrdering = encryptedChunkOrdering;
    }
}
