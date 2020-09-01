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

package com.android.server.backup.encryption.chunk;

import android.annotation.Nullable;

import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunk listing in a format optimized for quick look up of chunks via their hash keys. This is
 * useful when building an incremental backup. After a chunk has been produced, the algorithm can
 * quickly look up whether the chunk existed in the previous backup by checking this chunk listing.
 * It can then tell the server to use that chunk, through telling it the position and length of the
 * chunk in the previous backup's blob.
 */
public class ChunkListingMap {

    private final Map<ChunkHash, Entry> mChunksByHash;

    /** Construct a map from a {@link ChunksMetadataProto.ChunkListing} protobuf */
    public static ChunkListingMap fromProto(ChunksMetadataProto.ChunkListing chunkListingProto) {
        Map<ChunkHash, Entry> entries = new HashMap<>();

        long start = 0;

        for (ChunksMetadataProto.Chunk chunk : chunkListingProto.chunks) {
            entries.put(new ChunkHash(chunk.hash), new Entry(start, chunk.length));
            start += chunk.length;
        }

        return new ChunkListingMap(entries);
    }

    private ChunkListingMap(Map<ChunkHash, Entry> chunksByHash) {
        // This is only called from the {@link #fromProto} method, so we don't
        // need to take a copy.
        this.mChunksByHash = chunksByHash;
    }

    /** Returns {@code true} if there is a chunk with the given SHA-256 MAC key in the listing. */
    public boolean hasChunk(ChunkHash hash) {
        return mChunksByHash.containsKey(hash);
    }

    /**
     * Returns the entry for the chunk with the given hash.
     *
     * @param hash The SHA-256 MAC of the plaintext of the chunk.
     * @return The entry, containing position and length of the chunk in the backup blob, or null if
     *     it does not exist.
     */
    @Nullable
    public Entry getChunkEntry(ChunkHash hash) {
        return mChunksByHash.get(hash);
    }

    /** Information about a chunk entry in a backup blob - i.e., its position and length. */
    public static final class Entry {
        private final int mLength;
        private final long mStart;

        private Entry(long start, int length) {
            mLength = length;
            mStart = start;
        }

        /** Returns the length of the chunk in bytes. */
        public int getLength() {
            return mLength;
        }

        /** Returns the start position of the chunk in the backup blob, in bytes. */
        public long getStart() {
            return mStart;
        }
    }
}
