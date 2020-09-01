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

package com.android.server.backup.testing;

import android.util.Pair;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto;

import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Helpers for crypto code tests. */
public class CryptoTestUtils {
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    private CryptoTestUtils() {}

    public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    /** Generates a byte array of size {@code n} containing random bytes. */
    public static byte[] generateRandomBytes(int n) {
        byte[] bytes = new byte[n];
        Random random = new Random();
        random.nextBytes(bytes);
        return bytes;
    }

    public static ChunksMetadataProto.Chunk newChunk(ChunkHash hash, int length) {
        return newChunk(hash.getHash(), length);
    }

    public static ChunksMetadataProto.Chunk newChunk(byte[] hash, int length) {
        ChunksMetadataProto.Chunk newChunk = new ChunksMetadataProto.Chunk();
        newChunk.hash = Arrays.copyOf(hash, hash.length);
        newChunk.length = length;
        return newChunk;
    }

    public static ChunksMetadataProto.ChunkListing newChunkListing(
            String docId,
            byte[] fingerprintSalt,
            int cipherType,
            int orderingType,
            ChunksMetadataProto.Chunk... chunks) {
        ChunksMetadataProto.ChunkListing chunkListing =
                newChunkListingWithoutDocId(fingerprintSalt, cipherType, orderingType, chunks);
        chunkListing.documentId = docId;
        return chunkListing;
    }

    public static ChunksMetadataProto.ChunkListing newChunkListingWithoutDocId(
            byte[] fingerprintSalt,
            int cipherType,
            int orderingType,
            ChunksMetadataProto.Chunk... chunks) {
        ChunksMetadataProto.ChunkListing chunkListing = new ChunksMetadataProto.ChunkListing();
        chunkListing.fingerprintMixerSalt =
                fingerprintSalt == null
                        ? null
                        : Arrays.copyOf(fingerprintSalt, fingerprintSalt.length);
        chunkListing.cipherType = cipherType;
        chunkListing.chunkOrderingType = orderingType;
        chunkListing.chunks = chunks;
        return chunkListing;
    }

    public static ChunksMetadataProto.ChunkOrdering newChunkOrdering(
            int[] starts, byte[] checksum) {
        ChunksMetadataProto.ChunkOrdering chunkOrdering = new ChunksMetadataProto.ChunkOrdering();
        chunkOrdering.starts = starts == null ? null : Arrays.copyOf(starts, starts.length);
        chunkOrdering.checksum =
                checksum == null ? checksum : Arrays.copyOf(checksum, checksum.length);
        return chunkOrdering;
    }

    public static ChunksMetadataProto.ChunksMetadata newChunksMetadata(
            int cipherType, int checksumType, int chunkOrderingType, byte[] chunkOrdering) {
        ChunksMetadataProto.ChunksMetadata metadata = new ChunksMetadataProto.ChunksMetadata();
        metadata.cipherType = cipherType;
        metadata.checksumType = checksumType;
        metadata.chunkOrdering = Arrays.copyOf(chunkOrdering, chunkOrdering.length);
        metadata.chunkOrderingType = chunkOrderingType;
        return metadata;
    }

    public static KeyValuePairProto.KeyValuePair newPair(String key, String value) {
        return newPair(key, value.getBytes(Charset.forName("UTF-8")));
    }

    public static KeyValuePairProto.KeyValuePair newPair(String key, byte[] value) {
        KeyValuePairProto.KeyValuePair newPair = new KeyValuePairProto.KeyValuePair();
        newPair.key = key;
        newPair.value = value;
        return newPair;
    }

    public static ChunksMetadataProto.ChunkListing clone(
            ChunksMetadataProto.ChunkListing original) {
        ChunksMetadataProto.Chunk[] clonedChunks;
        if (original.chunks == null) {
            clonedChunks = null;
        } else {
            clonedChunks = new ChunksMetadataProto.Chunk[original.chunks.length];
            for (int i = 0; i < original.chunks.length; i++) {
                clonedChunks[i] = clone(original.chunks[i]);
            }
        }

        return newChunkListing(
                original.documentId,
                original.fingerprintMixerSalt,
                original.cipherType,
                original.chunkOrderingType,
                clonedChunks);
    }

    public static ChunksMetadataProto.Chunk clone(ChunksMetadataProto.Chunk original) {
        return newChunk(original.hash, original.length);
    }

    public static ChunksMetadataProto.ChunksMetadata clone(
            ChunksMetadataProto.ChunksMetadata original) {
        ChunksMetadataProto.ChunksMetadata cloneMetadata = new ChunksMetadataProto.ChunksMetadata();
        cloneMetadata.chunkOrderingType = original.chunkOrderingType;
        cloneMetadata.chunkOrdering =
                original.chunkOrdering == null
                        ? null
                        : Arrays.copyOf(original.chunkOrdering, original.chunkOrdering.length);
        cloneMetadata.checksumType = original.checksumType;
        cloneMetadata.cipherType = original.cipherType;
        return cloneMetadata;
    }

    public static ChunksMetadataProto.ChunkOrdering clone(
            ChunksMetadataProto.ChunkOrdering original) {
        ChunksMetadataProto.ChunkOrdering clone = new ChunksMetadataProto.ChunkOrdering();
        clone.starts = Arrays.copyOf(original.starts, original.starts.length);
        clone.checksum = Arrays.copyOf(original.checksum, original.checksum.length);
        return clone;
    }

    public static <K, V> Map<K, V> mapOf(Pair<K, V>... pairs) {
        Map<K, V> map = new HashMap<>();
        for (Pair<K, V> pair : pairs) {
            map.put(pair.first, pair.second);
        }
        return map;
    }
}
