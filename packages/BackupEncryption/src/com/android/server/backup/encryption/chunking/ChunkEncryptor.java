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

package com.android.server.backup.encryption.chunking;

import com.android.server.backup.encryption.chunk.ChunkHash;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts chunks of a file using AES/GCM. */
public class ChunkEncryptor {
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;

    private final SecretKey mSecretKey;
    private final SecureRandom mSecureRandom;

    /**
     * A new instance using {@code mSecretKey} to encrypt chunks and {@code mSecureRandom} to
     * generate nonces.
     */
    public ChunkEncryptor(SecretKey secretKey, SecureRandom secureRandom) {
        this.mSecretKey = secretKey;
        this.mSecureRandom = secureRandom;
    }

    /**
     * Transforms {@code plaintext} into an {@link EncryptedChunk}.
     *
     * @param plaintextHash The hash of the plaintext to encrypt, to attach as the key of the chunk.
     * @param plaintext Bytes to encrypt.
     * @throws InvalidKeyException If the given secret key is not a valid AES key for decryption.
     * @throws IllegalBlockSizeException If the input data cannot be encrypted using
     *     AES/GCM/NoPadding. This should never be the case.
     */
    public EncryptedChunk encrypt(ChunkHash plaintextHash, byte[] plaintext)
            throws InvalidKeyException, IllegalBlockSizeException {
        byte[] nonce = generateNonce();
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    mSecretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BYTES * 8, nonce));
        } catch (NoSuchAlgorithmException
                | NoSuchPaddingException
                | InvalidAlgorithmParameterException e) {
            // This can not happen - AES/GCM/NoPadding is supported.
            throw new AssertionError(e);
        }
        byte[] encryptedBytes;
        try {
            encryptedBytes = cipher.doFinal(plaintext);
        } catch (BadPaddingException e) {
            // This can not happen - BadPaddingException can only be thrown in decrypt mode.
            throw new AssertionError("Impossible: threw BadPaddingException in encrypt mode.");
        }

        return EncryptedChunk.create(/*key=*/ plaintextHash, nonce, encryptedBytes);
    }

    private byte[] generateNonce() {
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        mSecureRandom.nextBytes(nonce);
        return nonce;
    }
}
