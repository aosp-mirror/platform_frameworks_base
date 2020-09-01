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
import android.util.SparseIntArray;

import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunkOrdering;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto.ChunksMetadata;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A backup file consists of, in order:
 *
 * <ul>
 *   <li>A randomly ordered sequence of encrypted chunks
 *   <li>A plaintext {@link ChunksMetadata} proto, containing the bytes of an encrypted {@link
 *       ChunkOrdering} proto.
 *   <li>A 64-bit long denoting the offset of the file at which the ChunkOrdering proto starts.
 * </ul>
 *
 * <p>This task decrypts such a blob and writes the plaintext to another file.
 *
 * <p>The backup file has two formats to indicate the boundaries of the chunks in the encrypted
 * file. In {@link ChunksMetadataProto#EXPLICIT_STARTS} mode the chunk ordering contains the start
 * positions of each chunk and the decryptor outputs the chunks in the order they appeared in the
 * plaintext file. In {@link ChunksMetadataProto#INLINE_LENGTHS} mode the length of each encrypted
 * chunk is prepended to the chunk in the file and the decryptor outputs the chunks in no specific
 * order.
 *
 * <p>{@link ChunksMetadataProto#EXPLICIT_STARTS} is for use with full backup (Currently used for
 * all backups as b/77188289 is not implemented yet), {@link ChunksMetadataProto#INLINE_LENGTHS}
 * will be used for kv backup (once b/77188289 is implemented) to avoid re-uploading the chunk
 * ordering (see b/70782620).
 */
public class BackupFileDecryptorTask {
    private static final String TAG = "BackupFileDecryptorTask";

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;
    private static final String READ_MODE = "r";
    private static final int BYTES_PER_LONG = 64 / BITS_PER_BYTE;

    private final Cipher mCipher;
    private final SecretKey mSecretKey;

    /**
     * A new instance.
     *
     * @param secretKey The tertiary key used to encrypt the backup blob.
     */
    public BackupFileDecryptorTask(SecretKey secretKey)
            throws NoSuchPaddingException, NoSuchAlgorithmException {
        this.mCipher = Cipher.getInstance(CIPHER_ALGORITHM);
        this.mSecretKey = secretKey;
    }

    /**
     * Runs the task, reading the encrypted data from {@code input} and writing the plaintext data
     * to {@code output}.
     *
     * @param inputFile The encrypted backup file.
     * @param decryptedChunkOutput Unopened output to write the plaintext to, which this class will
     *     open and close during decryption.
     * @throws IOException if an error occurred reading the encrypted file or writing the plaintext,
     *     or if one of the protos could not be deserialized.
     */
    public void decryptFile(File inputFile, DecryptedChunkOutput decryptedChunkOutput)
            throws IOException, EncryptedRestoreException, IllegalBlockSizeException,
                    BadPaddingException, InvalidAlgorithmParameterException, InvalidKeyException,
                    ShortBufferException, NoSuchAlgorithmException {
        RandomAccessFile input = new RandomAccessFile(inputFile, READ_MODE);

        long metadataOffset = getChunksMetadataOffset(input);
        ChunksMetadataProto.ChunksMetadata chunksMetadata =
                getChunksMetadata(input, metadataOffset);
        ChunkOrdering chunkOrdering = decryptChunkOrdering(chunksMetadata);

        if (chunksMetadata.chunkOrderingType == ChunksMetadataProto.CHUNK_ORDERING_TYPE_UNSPECIFIED
                || chunksMetadata.chunkOrderingType == ChunksMetadataProto.EXPLICIT_STARTS) {
            Slog.d(TAG, "Using explicit starts");
            decryptFileWithExplicitStarts(
                    input, decryptedChunkOutput, chunkOrdering, metadataOffset);

        } else if (chunksMetadata.chunkOrderingType == ChunksMetadataProto.INLINE_LENGTHS) {
            Slog.d(TAG, "Using inline lengths");
            decryptFileWithInlineLengths(input, decryptedChunkOutput, metadataOffset);

        } else {
            throw new UnsupportedEncryptedFileException(
                    "Unknown chunk ordering type:" + chunksMetadata.chunkOrderingType);
        }

        if (!Arrays.equals(decryptedChunkOutput.getDigest(), chunkOrdering.checksum)) {
            throw new MessageDigestMismatchException("Checksums did not match");
        }
    }

    private void decryptFileWithExplicitStarts(
            RandomAccessFile input,
            DecryptedChunkOutput decryptedChunkOutput,
            ChunkOrdering chunkOrdering,
            long metadataOffset)
            throws IOException, InvalidKeyException, IllegalBlockSizeException,
                    InvalidAlgorithmParameterException, ShortBufferException, BadPaddingException,
                    NoSuchAlgorithmException {
        SparseIntArray chunkLengthsByPosition =
                getChunkLengths(chunkOrdering.starts, (int) metadataOffset);
        int largestChunkLength = getLargestChunkLength(chunkLengthsByPosition);
        byte[] encryptedChunkBuffer = new byte[largestChunkLength];
        // largestChunkLength is 0 if the backup file contains zero chunks e.g. 0 kv pairs.
        int plaintextBufferLength =
                Math.max(0, largestChunkLength - GCM_NONCE_LENGTH_BYTES - GCM_TAG_LENGTH_BYTES);
        byte[] plaintextChunkBuffer = new byte[plaintextBufferLength];

        try (DecryptedChunkOutput output = decryptedChunkOutput.open()) {
            for (int start : chunkOrdering.starts) {
                int length = chunkLengthsByPosition.get(start);

                input.seek(start);
                input.readFully(encryptedChunkBuffer, 0, length);
                int plaintextLength =
                        decryptChunk(encryptedChunkBuffer, length, plaintextChunkBuffer);
                outputChunk(output, plaintextChunkBuffer, plaintextLength);
            }
        }
    }

    private void decryptFileWithInlineLengths(
            RandomAccessFile input, DecryptedChunkOutput decryptedChunkOutput, long metadataOffset)
            throws MalformedEncryptedFileException, IOException, IllegalBlockSizeException,
                    BadPaddingException, InvalidAlgorithmParameterException, ShortBufferException,
                    InvalidKeyException, NoSuchAlgorithmException {
        input.seek(0);
        try (DecryptedChunkOutput output = decryptedChunkOutput.open()) {
            while (input.getFilePointer() < metadataOffset) {
                long start = input.getFilePointer();
                int encryptedChunkLength = input.readInt();

                if (encryptedChunkLength <= 0) {
                    // If the length of the encrypted chunk is not positive we will not make
                    // progress reading the file and so will loop forever.
                    throw new MalformedEncryptedFileException(
                            "Encrypted chunk length not positive:" + encryptedChunkLength);
                }

                if (start + encryptedChunkLength > metadataOffset) {
                    throw new MalformedEncryptedFileException(
                            String.format(
                                    Locale.US,
                                    "Encrypted chunk longer (%d) than file (%d)",
                                    encryptedChunkLength,
                                    metadataOffset));
                }

                byte[] plaintextChunk = new byte[encryptedChunkLength];
                byte[] plaintext =
                        new byte
                                [encryptedChunkLength
                                        - GCM_NONCE_LENGTH_BYTES
                                        - GCM_TAG_LENGTH_BYTES];

                input.readFully(plaintextChunk);

                int plaintextChunkLength =
                        decryptChunk(plaintextChunk, encryptedChunkLength, plaintext);
                outputChunk(output, plaintext, plaintextChunkLength);
            }
        }
    }

    private void outputChunk(
            DecryptedChunkOutput output, byte[] plaintextChunkBuffer, int plaintextLength)
            throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        output.processChunk(plaintextChunkBuffer, plaintextLength);
    }

    /**
     * Decrypts chunk and returns the length of the plaintext.
     *
     * @param encryptedChunkBuffer The encrypted data, prefixed by the nonce.
     * @param encryptedChunkBufferLength The length of the encrypted chunk (including nonce).
     * @param plaintextChunkBuffer The buffer into which to write the plaintext chunk.
     * @return The length of the plaintext chunk.
     */
    private int decryptChunk(
            byte[] encryptedChunkBuffer,
            int encryptedChunkBufferLength,
            byte[] plaintextChunkBuffer)
            throws InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException,
                    ShortBufferException, IllegalBlockSizeException {

        mCipher.init(
                Cipher.DECRYPT_MODE,
                mSecretKey,
                new GCMParameterSpec(
                        GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE,
                        encryptedChunkBuffer,
                        0,
                        GCM_NONCE_LENGTH_BYTES));

        return mCipher.doFinal(
                encryptedChunkBuffer,
                GCM_NONCE_LENGTH_BYTES,
                encryptedChunkBufferLength - GCM_NONCE_LENGTH_BYTES,
                plaintextChunkBuffer);
    }

    /** Given all the lengths, returns the largest length. */
    private int getLargestChunkLength(SparseIntArray lengths) {
        int maxSeen = 0;
        for (int i = 0; i < lengths.size(); i++) {
            maxSeen = Math.max(maxSeen, lengths.valueAt(i));
        }
        return maxSeen;
    }

    /**
     * From a list of the starting position of each chunk in the correct order of the backup data,
     * calculates a mapping from start position to length of that chunk.
     *
     * @param starts The start positions of chunks, in order.
     * @param chunkOrderingPosition Where the {@link ChunkOrdering} proto starts, used to calculate
     *     the length of the last chunk.
     * @return The mapping.
     */
    private SparseIntArray getChunkLengths(int[] starts, int chunkOrderingPosition) {
        int[] boundaries = Arrays.copyOf(starts, starts.length + 1);
        boundaries[boundaries.length - 1] = chunkOrderingPosition;
        Arrays.sort(boundaries);

        SparseIntArray lengths = new SparseIntArray();
        for (int i = 0; i < boundaries.length - 1; i++) {
            lengths.put(boundaries[i], boundaries[i + 1] - boundaries[i]);
        }
        return lengths;
    }

    /**
     * Reads and decrypts the {@link ChunkOrdering} from the {@link ChunksMetadata}.
     *
     * @param metadata The metadata.
     * @return The ordering.
     * @throws InvalidProtocolBufferNanoException if there is an issue deserializing the proto.
     */
    private ChunkOrdering decryptChunkOrdering(ChunksMetadata metadata)
            throws InvalidProtocolBufferNanoException, InvalidAlgorithmParameterException,
                    InvalidKeyException, BadPaddingException, IllegalBlockSizeException,
                    UnsupportedEncryptedFileException {
        assertCryptoSupported(metadata);

        mCipher.init(
                Cipher.DECRYPT_MODE,
                mSecretKey,
                new GCMParameterSpec(
                        GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE,
                        metadata.chunkOrdering,
                        0,
                        GCM_NONCE_LENGTH_BYTES));

        byte[] decrypted =
                mCipher.doFinal(
                        metadata.chunkOrdering,
                        GCM_NONCE_LENGTH_BYTES,
                        metadata.chunkOrdering.length - GCM_NONCE_LENGTH_BYTES);

        return ChunkOrdering.parseFrom(decrypted);
    }

    /**
     * Asserts that the Cipher and MessageDigest algorithms in the backup metadata are supported.
     * For now we only support SHA-256 for checksum and 256-bit AES/GCM/NoPadding for the Cipher.
     *
     * @param chunksMetadata The file metadata.
     * @throws UnsupportedEncryptedFileException if any algorithm is unsupported.
     */
    private void assertCryptoSupported(ChunksMetadata chunksMetadata)
            throws UnsupportedEncryptedFileException {
        if (chunksMetadata.checksumType != ChunksMetadataProto.SHA_256) {
            // For now we only support SHA-256.
            throw new UnsupportedEncryptedFileException(
                    "Unrecognized checksum type for backup (this version of backup only supports"
                        + " SHA-256): "
                            + chunksMetadata.checksumType);
        }

        if (chunksMetadata.cipherType != ChunksMetadataProto.AES_256_GCM) {
            throw new UnsupportedEncryptedFileException(
                    "Unrecognized cipher type for backup (this version of backup only supports"
                        + " AES-256-GCM: "
                            + chunksMetadata.cipherType);
        }
    }

    /**
     * Reads the offset of the {@link ChunksMetadata} proto from the end of the file.
     *
     * @return The offset.
     * @throws IOException if there is an error reading.
     */
    private long getChunksMetadataOffset(RandomAccessFile input) throws IOException {
        input.seek(input.length() - BYTES_PER_LONG);
        return input.readLong();
    }

    /**
     * Reads the {@link ChunksMetadata} proto from the given position in the file.
     *
     * @param input The encrypted file.
     * @param position The position where the proto starts.
     * @return The proto.
     * @throws IOException if there is an issue reading the file or deserializing the proto.
     */
    private ChunksMetadata getChunksMetadata(RandomAccessFile input, long position)
            throws IOException, MalformedEncryptedFileException {
        long length = input.length();
        if (position >= length || position < 0) {
            throw new MalformedEncryptedFileException(
                    String.format(
                            Locale.US,
                            "%d is not valid position for chunks metadata in file of %d bytes",
                            position,
                            length));
        }

        // Read chunk ordering bytes
        input.seek(position);
        long chunksMetadataLength = input.length() - BYTES_PER_LONG - position;
        byte[] chunksMetadataBytes = new byte[(int) chunksMetadataLength];
        input.readFully(chunksMetadataBytes);

        try {
            return ChunksMetadata.parseFrom(chunksMetadataBytes);
        } catch (InvalidProtocolBufferNanoException e) {
            throw new MalformedEncryptedFileException(
                    String.format(
                            Locale.US,
                            "Could not read chunks metadata at position %d of file of %d bytes",
                            position,
                            length));
        }
    }
}
