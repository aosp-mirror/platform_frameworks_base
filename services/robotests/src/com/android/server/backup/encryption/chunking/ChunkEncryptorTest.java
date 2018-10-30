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
 * limitations under the License
 */

package com.android.server.backup.encryption.chunking;

import static com.android.server.backup.testing.CryptoTestUtils.generateAesKey;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.chunk.ChunkHash;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ChunkEncryptorTest {
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final String CHUNK_PLAINTEXT =
            "A little Learning is a dang'rous Thing;\n"
                    + "Drink deep, or taste not the Pierian Spring:\n"
                    + "There shallow Draughts intoxicate the Brain,\n"
                    + "And drinking largely sobers us again.";
    private static final byte[] PLAINTEXT_BYTES = CHUNK_PLAINTEXT.getBytes(UTF_8);
    private static final byte[] NONCE_1 = "0123456789abc".getBytes(UTF_8);
    private static final byte[] NONCE_2 = "123456789abcd".getBytes(UTF_8);

    private static final byte[][] NONCES = new byte[][] {NONCE_1, NONCE_2};

    @Mock private SecureRandom mSecureRandomMock;
    private SecretKey mSecretKey;
    private ChunkHash mPlaintextHash;
    private ChunkEncryptor mChunkEncryptor;

    @Before
    public void setUp() throws Exception {
        mSecretKey = generateAesKey();
        ChunkHasher chunkHasher = new ChunkHasher(mSecretKey);
        mPlaintextHash = chunkHasher.computeHash(PLAINTEXT_BYTES);
        mSecureRandomMock = mock(SecureRandom.class);
        mChunkEncryptor = new ChunkEncryptor(mSecretKey, mSecureRandomMock);

        // Return NONCE_1, then NONCE_2 for invocations of mSecureRandomMock.nextBytes().
        doAnswer(
                        new Answer<Void>() {
                            private int mInvocation = 0;

                            @Override
                            public Void answer(InvocationOnMock invocation) {
                                byte[] nonceDestination = invocation.getArgument(0);
                                System.arraycopy(
                                        NONCES[this.mInvocation],
                                        0,
                                        nonceDestination,
                                        0,
                                        GCM_NONCE_LENGTH_BYTES);
                                this.mInvocation++;
                                return null;
                            }
                        })
                .when(mSecureRandomMock)
                .nextBytes(any(byte[].class));
    }

    @Test
    public void encrypt_withHash_resultContainsHashAsKey() throws Exception {
        EncryptedChunk chunk = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        assertThat(chunk.key()).isEqualTo(mPlaintextHash);
    }

    @Test
    public void encrypt_generatesHmacOfPlaintext() throws Exception {
        EncryptedChunk chunk = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        byte[] generatedHash = chunk.key().getHash();
        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        mac.init(mSecretKey);
        byte[] plaintextHmac = mac.doFinal(PLAINTEXT_BYTES);
        assertThat(generatedHash).isEqualTo(plaintextHmac);
    }

    @Test
    public void encrypt_whenInvokedAgain_generatesNewNonce() throws Exception {
        EncryptedChunk chunk1 = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        EncryptedChunk chunk2 = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        assertThat(chunk1.nonce()).isNotEqualTo(chunk2.nonce());
    }

    @Test
    public void encrypt_whenInvokedAgain_generatesNewCiphertext() throws Exception {
        EncryptedChunk chunk1 = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        EncryptedChunk chunk2 = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        assertThat(chunk1.encryptedBytes()).isNotEqualTo(chunk2.encryptedBytes());
    }

    @Test
    public void encrypt_generates12ByteNonce() throws Exception {
        EncryptedChunk encryptedChunk = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        byte[] nonce = encryptedChunk.nonce();
        assertThat(nonce).hasLength(GCM_NONCE_LENGTH_BYTES);
    }

    @Test
    public void encrypt_decryptedResultCorrespondsToPlaintext() throws Exception {
        EncryptedChunk chunk = mChunkEncryptor.encrypt(mPlaintextHash, PLAINTEXT_BYTES);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(
                Cipher.DECRYPT_MODE,
                mSecretKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BYTES * 8, chunk.nonce()));
        byte[] decrypted = cipher.doFinal(chunk.encryptedBytes());
        assertThat(decrypted).isEqualTo(PLAINTEXT_BYTES);
    }
}
