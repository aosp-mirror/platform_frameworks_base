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

import static java.util.Collections.unmodifiableList;

import android.annotation.Nullable;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.EncryptedChunk;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

/** Task which reads data from some source, splits it into chunks and encrypts new chunks. */
public interface BackupEncrypter {
    /** The algorithm which we use to compute the digest of the backup file plaintext. */
    String MESSAGE_DIGEST_ALGORITHM = "SHA-256";

    /**
     * Splits the backup input into encrypted chunks and encrypts new chunks.
     *
     * @param secretKey Key used to encrypt backup.
     * @param fingerprintMixerSalt Fingerprint mixer salt used for content-defined chunking during a
     *     full backup. Should be {@code null} for a key-value backup.
     * @param existingChunks Set of the SHA-256 Macs of chunks the server already has.
     * @return a result containing an array of new encrypted chunks to upload, and an ordered
     *     listing of the chunks in the backup file.
     * @throws IOException if a problem occurs reading from the backup data.
     * @throws GeneralSecurityException if there is a problem encrypting the data.
     */
    Result backup(
            SecretKey secretKey,
            @Nullable byte[] fingerprintMixerSalt,
            Set<ChunkHash> existingChunks)
            throws IOException, GeneralSecurityException;

    /**
     * The result of an incremental backup. Contains new encrypted chunks to upload, and an ordered
     * list of the chunks in the backup file.
     */
    class Result {
        private final List<ChunkHash> mAllChunks;
        private final List<EncryptedChunk> mNewChunks;
        private final byte[] mDigest;

        public Result(List<ChunkHash> allChunks, List<EncryptedChunk> newChunks, byte[] digest) {
            mAllChunks = unmodifiableList(new ArrayList<>(allChunks));
            mDigest = digest;
            mNewChunks = unmodifiableList(new ArrayList<>(newChunks));
        }

        /**
         * Returns an unmodifiable list of the hashes of all the chunks in the backup, in the order
         * they appear in the plaintext.
         */
        public List<ChunkHash> getAllChunks() {
            return mAllChunks;
        }

        /** Returns an unmodifiable list of the new chunks in the backup. */
        public List<EncryptedChunk> getNewChunks() {
            return mNewChunks;
        }

        /** Returns the message digest of the backup. */
        public byte[] getDigest() {
            return mDigest;
        }
    }
}
