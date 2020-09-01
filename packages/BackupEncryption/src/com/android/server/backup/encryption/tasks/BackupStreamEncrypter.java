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

import android.util.Slog;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkEncryptor;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.encryption.chunking.cdc.ContentDefinedChunker;
import com.android.server.backup.encryption.chunking.cdc.FingerprintMixer;
import com.android.server.backup.encryption.chunking.cdc.IsChunkBreakpoint;
import com.android.server.backup.encryption.chunking.cdc.RabinFingerprint64;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

/**
 * Splits backup data into variable-sized chunks using content-defined chunking, then encrypts the
 * chunks. Given a hash of the SHA-256s of existing chunks, performs an incremental backup (i.e.,
 * only encrypts new chunks).
 */
public class BackupStreamEncrypter implements BackupEncrypter {
    private static final String TAG = "BackupStreamEncryptor";

    private final InputStream mData;
    private final int mMinChunkSizeBytes;
    private final int mMaxChunkSizeBytes;
    private final int mAverageChunkSizeBytes;

    /**
     * A new instance over the given distribution of chunk sizes.
     *
     * @param data The data to be backed up.
     * @param minChunkSizeBytes The minimum chunk size. No chunk will be smaller than this.
     * @param maxChunkSizeBytes The maximum chunk size. No chunk will be larger than this.
     * @param averageChunkSizeBytes The average chunk size. The mean size of chunks will be roughly
     *     this (with a few tens of bytes of overhead for the initialization vector and message
     *     authentication code).
     */
    public BackupStreamEncrypter(
            InputStream data,
            int minChunkSizeBytes,
            int maxChunkSizeBytes,
            int averageChunkSizeBytes) {
        this.mData = data;
        this.mMinChunkSizeBytes = minChunkSizeBytes;
        this.mMaxChunkSizeBytes = maxChunkSizeBytes;
        this.mAverageChunkSizeBytes = averageChunkSizeBytes;
    }

    @Override
    public Result backup(
            SecretKey secretKey, byte[] fingerprintMixerSalt, Set<ChunkHash> existingChunks)
            throws IOException, GeneralSecurityException {
        MessageDigest messageDigest =
                MessageDigest.getInstance(BackupEncrypter.MESSAGE_DIGEST_ALGORITHM);
        RabinFingerprint64 rabinFingerprint64 = new RabinFingerprint64();
        FingerprintMixer fingerprintMixer = new FingerprintMixer(secretKey, fingerprintMixerSalt);
        IsChunkBreakpoint isChunkBreakpoint =
                new IsChunkBreakpoint(mAverageChunkSizeBytes - mMinChunkSizeBytes);
        ContentDefinedChunker chunker =
                new ContentDefinedChunker(
                        mMinChunkSizeBytes,
                        mMaxChunkSizeBytes,
                        rabinFingerprint64,
                        fingerprintMixer,
                        isChunkBreakpoint);
        ChunkHasher chunkHasher = new ChunkHasher(secretKey);
        ChunkEncryptor encryptor = new ChunkEncryptor(secretKey, new SecureRandom());
        Set<ChunkHash> includedChunks = new HashSet<>();
        // New chunks will be added only once to this list, even if they occur multiple times.
        List<EncryptedChunk> newChunks = new ArrayList<>();
        // All chunks (including multiple occurrences) will be added to the chunkListing.
        List<ChunkHash> chunkListing = new ArrayList<>();

        includedChunks.addAll(existingChunks);

        chunker.chunkify(
                mData,
                chunk -> {
                    messageDigest.update(chunk);
                    ChunkHash key = chunkHasher.computeHash(chunk);

                    if (!includedChunks.contains(key)) {
                        newChunks.add(encryptor.encrypt(key, chunk));
                        includedChunks.add(key);
                    }
                    chunkListing.add(key);
                });

        Slog.i(
                TAG,
                String.format(
                        "Chunks: %d total, %d unique, %d new",
                        chunkListing.size(), new HashSet<>(chunkListing).size(), newChunks.size()));
        return new Result(
                Collections.unmodifiableList(chunkListing),
                Collections.unmodifiableList(newChunks),
                messageDigest.digest());
    }
}
