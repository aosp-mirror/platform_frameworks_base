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

import static com.android.internal.util.Preconditions.checkState;

import android.annotation.Nullable;
import android.app.backup.BackupDataInput;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.ChunkEncryptor;
import com.android.server.backup.encryption.chunking.ChunkHasher;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.encryption.kv.KeyValueListingBuilder;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;
import com.android.server.backup.encryption.protos.nano.KeyValuePairProto;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;

/**
 * Reads key value backup data from an input, converts each pair into a chunk and encrypts the
 * chunks.
 *
 * <p>The caller should pass in the key value listing from the previous backup, if there is one.
 * This class emits chunks for both existing and new pairs, using the provided listing to
 * determine the hashes of pairs that already exist. During the backup it computes the new listing,
 * which the caller should store on disk and pass in at the start of the next backup.
 *
 * <p>Also computes the message digest, which is {@code SHA-256(chunk hashes sorted
 * lexicographically)}.
 */
public class KvBackupEncrypter implements BackupEncrypter {
    private final BackupDataInput mBackupDataInput;

    private KeyValueListingProto.KeyValueListing mOldKeyValueListing;
    @Nullable private KeyValueListingBuilder mNewKeyValueListing;

    /**
     * Constructs a new instance which reads data from the given input.
     *
     * <p>By default this performs non-incremental backup, call {@link #setOldKeyValueListing} to
     * perform incremental backup.
     */
    public KvBackupEncrypter(BackupDataInput backupDataInput) {
        mBackupDataInput = backupDataInput;
        mOldKeyValueListing = KeyValueListingBuilder.emptyListing();
    }

    /** Sets the old listing to perform incremental backup against. */
    public void setOldKeyValueListing(KeyValueListingProto.KeyValueListing oldKeyValueListing) {
        mOldKeyValueListing = oldKeyValueListing;
    }

    @Override
    public Result backup(
            SecretKey secretKey,
            @Nullable byte[] unusedFingerprintMixerSalt,
            Set<ChunkHash> unusedExistingChunks)
            throws IOException, GeneralSecurityException {
        ChunkHasher chunkHasher = new ChunkHasher(secretKey);
        ChunkEncryptor chunkEncryptor = new ChunkEncryptor(secretKey, new SecureRandom());
        mNewKeyValueListing = new KeyValueListingBuilder();
        List<ChunkHash> allChunks = new ArrayList<>();
        List<EncryptedChunk> newChunks = new ArrayList<>();

        Map<String, ChunkHash> existingChunksToReuse = buildPairMap(mOldKeyValueListing);

        while (mBackupDataInput.readNextHeader()) {
            String key = mBackupDataInput.getKey();
            Optional<byte[]> value = readEntireValue(mBackupDataInput);

            // As this pair exists in the new backup, we don't need to add it from the previous
            // backup.
            existingChunksToReuse.remove(key);

            // If the value is not present then this key has been deleted.
            if (value.isPresent()) {
                EncryptedChunk newChunk =
                        createEncryptedChunk(chunkHasher, chunkEncryptor, key, value.get());
                allChunks.add(newChunk.key());
                newChunks.add(newChunk);
                mNewKeyValueListing.addPair(key, newChunk.key());
            }
        }

        allChunks.addAll(existingChunksToReuse.values());

        mNewKeyValueListing.addAll(existingChunksToReuse);

        return new Result(allChunks, newChunks, createMessageDigest(allChunks));
    }

    /**
     * Returns a listing containing the pairs in the new backup.
     *
     * <p>You must call {@link #backup} first.
     */
    public KeyValueListingProto.KeyValueListing getNewKeyValueListing() {
        checkState(mNewKeyValueListing != null, "Must call backup() first");
        return mNewKeyValueListing.build();
    }

    private static Map<String, ChunkHash> buildPairMap(
            KeyValueListingProto.KeyValueListing listing) {
        Map<String, ChunkHash> map = new HashMap<>();
        for (KeyValueListingProto.KeyValueEntry entry : listing.entries) {
            map.put(entry.key, new ChunkHash(entry.hash));
        }
        return map;
    }

    private EncryptedChunk createEncryptedChunk(
            ChunkHasher chunkHasher, ChunkEncryptor chunkEncryptor, String key, byte[] value)
            throws InvalidKeyException, IllegalBlockSizeException {
        KeyValuePairProto.KeyValuePair pair = new KeyValuePairProto.KeyValuePair();
        pair.key = key;
        pair.value = Arrays.copyOf(value, value.length);

        byte[] plaintext = KeyValuePairProto.KeyValuePair.toByteArray(pair);
        return chunkEncryptor.encrypt(chunkHasher.computeHash(plaintext), plaintext);
    }

    private static byte[] createMessageDigest(List<ChunkHash> allChunks)
            throws NoSuchAlgorithmException {
        MessageDigest messageDigest =
                MessageDigest.getInstance(BackupEncrypter.MESSAGE_DIGEST_ALGORITHM);
        // TODO:b/141531271 Extract sorted chunks code to utility class
        List<ChunkHash> sortedChunks = new ArrayList<>(allChunks);
        Collections.sort(sortedChunks);
        for (ChunkHash hash : sortedChunks) {
            messageDigest.update(hash.getHash());
        }
        return messageDigest.digest();
    }

    private static Optional<byte[]> readEntireValue(BackupDataInput input) throws IOException {
        // A negative data size indicates that this key should be deleted.
        if (input.getDataSize() < 0) {
            return Optional.empty();
        }

        byte[] value = new byte[input.getDataSize()];
        int bytesRead = 0;
        while (bytesRead < value.length) {
            bytesRead += input.readEntityData(value, bytesRead, value.length - bytesRead);
        }
        return Optional.of(value);
    }
}
