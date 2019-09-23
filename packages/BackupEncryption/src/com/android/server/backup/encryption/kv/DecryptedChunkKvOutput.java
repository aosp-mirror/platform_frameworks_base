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

package com.android.server.backup.encryption.kv;

import static com.android.internal.util.Preconditions.checkState;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto;
import com.android.server.backup.encryption.tasks.DecryptedChunkOutput;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a key value backup set from plaintext chunks. Computes a digest over the sorted SHA-256
 * hashes of the chunks.
 */
public class DecryptedChunkKvOutput implements DecryptedChunkOutput {
    @VisibleForTesting static final String DIGEST_ALGORITHM = "SHA-256";

    private final ChunkHasher mChunkHasher;
    private final List<KeyValuePairProto.KeyValuePair> mUnsortedPairs = new ArrayList<>();
    private final List<ChunkHash> mUnsortedHashes = new ArrayList<>();
    private boolean mClosed;

    /** Constructs a new instance which computers the digest using the given hasher. */
    public DecryptedChunkKvOutput(ChunkHasher chunkHasher) {
        mChunkHasher = chunkHasher;
    }

    @Override
    public DecryptedChunkOutput open() {
        // As we don't have any resources there is nothing to open.
        return this;
    }

    @Override
    public void processChunk(byte[] plaintextBuffer, int length)
            throws IOException, InvalidKeyException {
        checkState(!mClosed, "Cannot process chunk after close()");
        KeyValuePairProto.KeyValuePair kvPair = new KeyValuePairProto.KeyValuePair();
        KeyValuePairProto.KeyValuePair.mergeFrom(kvPair, plaintextBuffer, 0, length);
        mUnsortedPairs.add(kvPair);
        // TODO(b/71492289): Update ChunkHasher to accept offset and length so we don't have to copy
        // the buffer into a smaller array.
        mUnsortedHashes.add(mChunkHasher.computeHash(Arrays.copyOf(plaintextBuffer, length)));
    }

    @Override
    public void close() {
        // As we don't have any resources there is nothing to close.
        mClosed = true;
    }

    @Override
    public byte[] getDigest() throws NoSuchAlgorithmException {
        checkState(mClosed, "Must close() before getDigest()");
        MessageDigest digest = getMessageDigest();
        Collections.sort(mUnsortedHashes);
        for (ChunkHash hash : mUnsortedHashes) {
            digest.update(hash.getHash());
        }
        return digest.digest();
    }

    private static MessageDigest getMessageDigest() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance(DIGEST_ALGORITHM);
    }

    /**
     * Returns the key value pairs from the backup, sorted lexicographically by key.
     *
     * <p>You must call {@link #close} first.
     */
    public List<KeyValuePairProto.KeyValuePair> getPairs() {
        checkState(mClosed, "Must close() before getPairs()");
        Collections.sort(
                mUnsortedPairs,
                new Comparator<KeyValuePairProto.KeyValuePair>() {
                    @Override
                    public int compare(
                            KeyValuePairProto.KeyValuePair o1, KeyValuePairProto.KeyValuePair o2) {
                        return o1.key.compareTo(o2.key);
                    }
                });
        return mUnsortedPairs;
    }
}
