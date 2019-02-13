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
import android.util.proto.ProtoInputStream;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Chunk listing in a format optimized for quick look-up of chunks via their hash keys. This is
 * useful when building an incremental backup. After a chunk has been produced, the algorithm can
 * quickly look up whether the chunk existed in the previous backup by checking this chunk listing.
 * It can then tell the server to use that chunk, through telling it the position and length of the
 * chunk in the previous backup's blob.
 */
public class ChunkListingMap {
    /**
     * Reads a ChunkListingMap from a {@link ProtoInputStream}. Expects the message to be of format
     * {@link ChunksMetadataProto.ChunkListing}.
     *
     * @param inputStream Currently at a {@link ChunksMetadataProto.ChunkListing} message.
     * @throws IOException when the message is not structured as expected or a field can not be
     *     read.
     */
    public static ChunkListingMap readFromProto(ProtoInputStream inputStream) throws IOException {
        Map<ChunkHash, Entry> entries = new HashMap();

        long start = 0;

        while (inputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (inputStream.getFieldNumber() == (int) ChunksMetadataProto.ChunkListing.CHUNKS) {
                long chunkToken = inputStream.start(ChunksMetadataProto.ChunkListing.CHUNKS);
                Chunk chunk = Chunk.readFromProto(inputStream);
                entries.put(new ChunkHash(chunk.getHash()), new Entry(start, chunk.getLength()));
                start += chunk.getLength();
                inputStream.end(chunkToken);
            }
        }

        return new ChunkListingMap(entries);
    }

    private final Map<ChunkHash, Entry> mChunksByHash;

    private ChunkListingMap(Map<ChunkHash, Entry> chunksByHash) {
        mChunksByHash = Collections.unmodifiableMap(new HashMap<>(chunksByHash));
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

    /** Returns the number of chunks in this listing. */
    public int getChunkCount() {
        return mChunksByHash.size();
    }

    /** Information about a chunk entry in a backup blob - i.e., its position and length. */
    public static final class Entry {
        private final int mLength;
        private final long mStart;

        private Entry(long start, int length) {
            mStart = start;
            mLength = length;
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
