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

import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.os.Build.VERSION_CODES;
import android.util.Slog;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.chunking.BackupFileBuilder;
import com.android.server.backup.encryption.chunking.EncryptedChunk;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Task which reads encrypted chunks from a {@link BackupEncrypter}, builds a backup file and
 * uploads it to the server.
 */
@TargetApi(VERSION_CODES.P)
public class EncryptedBackupTask {
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;

    private static final String TAG = "EncryptedBackupTask";

    private final CryptoBackupServer mCryptoBackupServer;
    private final SecureRandom mSecureRandom;
    private final String mPackageName;
    private final ByteArrayOutputStream mBackupDataOutput;
    private final BackupEncrypter mBackupEncrypter;
    private final AtomicBoolean mCancelled;

    /** Creates a new instance which reads data from the given input stream. */
    public EncryptedBackupTask(
            CryptoBackupServer cryptoBackupServer,
            SecureRandom secureRandom,
            String packageName,
            BackupEncrypter backupEncrypter) {
        mCryptoBackupServer = cryptoBackupServer;
        mSecureRandom = secureRandom;
        mPackageName = packageName;
        mBackupEncrypter = backupEncrypter;

        mBackupDataOutput = new ByteArrayOutputStream();
        mCancelled = new AtomicBoolean(false);
    }

    /**
     * Creates a non-incremental backup file and uploads it to the server.
     *
     * @param fingerprintMixerSalt Fingerprint mixer salt used for content-defined chunking during a
     *     full backup. May be {@code null} for a key-value backup.
     */
    public ChunksMetadataProto.ChunkListing performNonIncrementalBackup(
            SecretKey tertiaryKey,
            WrappedKeyProto.WrappedKey wrappedTertiaryKey,
            @Nullable byte[] fingerprintMixerSalt)
            throws IOException, GeneralSecurityException {

        ChunksMetadataProto.ChunkListing newChunkListing =
                performBackup(
                        tertiaryKey,
                        fingerprintMixerSalt,
                        BackupFileBuilder.createForNonIncremental(mBackupDataOutput),
                        new HashSet<>());

        throwIfCancelled();

        newChunkListing.documentId =
                mCryptoBackupServer.uploadNonIncrementalBackup(
                        mPackageName, mBackupDataOutput.toByteArray(), wrappedTertiaryKey);

        return newChunkListing;
    }

    /** Creates an incremental backup file and uploads it to the server. */
    public ChunksMetadataProto.ChunkListing performIncrementalBackup(
            SecretKey tertiaryKey,
            WrappedKeyProto.WrappedKey wrappedTertiaryKey,
            ChunksMetadataProto.ChunkListing oldChunkListing)
            throws IOException, GeneralSecurityException {

        ChunksMetadataProto.ChunkListing newChunkListing =
                performBackup(
                        tertiaryKey,
                        oldChunkListing.fingerprintMixerSalt,
                        BackupFileBuilder.createForIncremental(mBackupDataOutput, oldChunkListing),
                        getChunkHashes(oldChunkListing));

        throwIfCancelled();

        String oldDocumentId = oldChunkListing.documentId;
        Slog.v(TAG, "Old doc id: " + oldDocumentId);

        newChunkListing.documentId =
                mCryptoBackupServer.uploadIncrementalBackup(
                        mPackageName,
                        oldDocumentId,
                        mBackupDataOutput.toByteArray(),
                        wrappedTertiaryKey);
        return newChunkListing;
    }

    /**
     * Signals to the task that the backup has been cancelled. If the upload has not yet started
     * then the task will not upload any data to the server or save the new chunk listing.
     */
    public void cancel() {
        mCancelled.getAndSet(true);
    }

    private void throwIfCancelled() {
        if (mCancelled.get()) {
            throw new CancellationException("EncryptedBackupTask was cancelled");
        }
    }

    private ChunksMetadataProto.ChunkListing performBackup(
            SecretKey tertiaryKey,
            @Nullable byte[] fingerprintMixerSalt,
            BackupFileBuilder backupFileBuilder,
            Set<ChunkHash> existingChunkHashes)
            throws IOException, GeneralSecurityException {
        BackupEncrypter.Result result =
                mBackupEncrypter.backup(tertiaryKey, fingerprintMixerSalt, existingChunkHashes);
        backupFileBuilder.writeChunks(result.getAllChunks(), buildChunkMap(result.getNewChunks()));

        ChunksMetadataProto.ChunkOrdering chunkOrdering =
                backupFileBuilder.getNewChunkOrdering(result.getDigest());
        backupFileBuilder.finish(buildMetadata(tertiaryKey, chunkOrdering));

        return backupFileBuilder.getNewChunkListing(fingerprintMixerSalt);
    }

    /** Returns a set containing the hashes of every chunk in the given listing. */
    private static Set<ChunkHash> getChunkHashes(ChunksMetadataProto.ChunkListing chunkListing) {
        Set<ChunkHash> hashes = new HashSet<>();
        for (ChunksMetadataProto.Chunk chunk : chunkListing.chunks) {
            hashes.add(new ChunkHash(chunk.hash));
        }
        return hashes;
    }

    /** Returns a map from chunk hash to chunk containing every chunk in the given list. */
    private static Map<ChunkHash, EncryptedChunk> buildChunkMap(List<EncryptedChunk> chunks) {
        Map<ChunkHash, EncryptedChunk> chunkMap = new HashMap<>();
        for (EncryptedChunk chunk : chunks) {
            chunkMap.put(chunk.key(), chunk);
        }
        return chunkMap;
    }

    private ChunksMetadataProto.ChunksMetadata buildMetadata(
            SecretKey tertiaryKey, ChunksMetadataProto.ChunkOrdering chunkOrdering)
            throws InvalidKeyException, IllegalBlockSizeException, BadPaddingException,
                    InvalidAlgorithmParameterException, NoSuchAlgorithmException,
                    ShortBufferException, NoSuchPaddingException {
        ChunksMetadataProto.ChunksMetadata metaData = new ChunksMetadataProto.ChunksMetadata();
        metaData.cipherType = ChunksMetadataProto.AES_256_GCM;
        metaData.checksumType = ChunksMetadataProto.SHA_256;
        metaData.chunkOrdering = encryptChunkOrdering(tertiaryKey, chunkOrdering);
        return metaData;
    }

    private byte[] encryptChunkOrdering(
            SecretKey tertiaryKey, ChunksMetadataProto.ChunkOrdering chunkOrdering)
            throws InvalidKeyException, IllegalBlockSizeException, BadPaddingException,
                    NoSuchPaddingException, NoSuchAlgorithmException,
                    InvalidAlgorithmParameterException, ShortBufferException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);

        byte[] nonce = generateNonce();

        cipher.init(
                Cipher.ENCRYPT_MODE,
                tertiaryKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE, nonce));

        byte[] orderingBytes = ChunksMetadataProto.ChunkOrdering.toByteArray(chunkOrdering);
        // We prepend the nonce to the ordering.
        byte[] output =
                Arrays.copyOf(
                        nonce,
                        GCM_NONCE_LENGTH_BYTES + orderingBytes.length + GCM_TAG_LENGTH_BYTES);

        cipher.doFinal(
                orderingBytes,
                /*inputOffset=*/ 0,
                /*inputLen=*/ orderingBytes.length,
                output,
                /*outputOffset=*/ GCM_NONCE_LENGTH_BYTES);

        return output;
    }

    private byte[] generateNonce() {
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        mSecureRandom.nextBytes(nonce);
        return nonce;
    }
}
