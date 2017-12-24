/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECPrivateKeySpec;
import javax.crypto.AEADBadTagException;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SecureBoxTest {

    private static final int EC_PUBLIC_KEY_LEN_BYTES = 65;
    private static final int NUM_TEST_ITERATIONS = 100;
    private static final int VERSION_LEN_BYTES = 2;

    // The following fixtures were produced by the C implementation of SecureBox v2. We use these to
    // cross-verify the two implementations.
    private static final byte[] VAULT_PARAMS =
            new byte[] {
                (byte) 0x04, (byte) 0xb8, (byte) 0x00, (byte) 0x11, (byte) 0x18, (byte) 0x98,
                (byte) 0x1d, (byte) 0xf0, (byte) 0x6e, (byte) 0xb4, (byte) 0x94, (byte) 0xfe,
                (byte) 0x86, (byte) 0xda, (byte) 0x1c, (byte) 0x07, (byte) 0x8d, (byte) 0x01,
                (byte) 0xb4, (byte) 0x3a, (byte) 0xf6, (byte) 0x8d, (byte) 0xdc, (byte) 0x61,
                (byte) 0xd0, (byte) 0x46, (byte) 0x49, (byte) 0x95, (byte) 0x0f, (byte) 0x10,
                (byte) 0x86, (byte) 0x93, (byte) 0x24, (byte) 0x66, (byte) 0xe0, (byte) 0x3f,
                (byte) 0xd2, (byte) 0xdf, (byte) 0xf3, (byte) 0x79, (byte) 0x20, (byte) 0x1d,
                (byte) 0x91, (byte) 0x55, (byte) 0xb0, (byte) 0xe5, (byte) 0xbd, (byte) 0x7a,
                (byte) 0x8b, (byte) 0x32, (byte) 0x7d, (byte) 0x25, (byte) 0x53, (byte) 0xa2,
                (byte) 0xfc, (byte) 0xa5, (byte) 0x65, (byte) 0xe1, (byte) 0xbd, (byte) 0x21,
                (byte) 0x44, (byte) 0x7e, (byte) 0x78, (byte) 0x52, (byte) 0xfa, (byte) 0x31,
                (byte) 0x32, (byte) 0x33, (byte) 0x34, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0a, (byte) 0x00, (byte) 0x00,
                (byte) 0x00
            };
    private static final byte[] VAULT_CHALLENGE = getBytes("Not a real vault challenge");
    private static final byte[] THM_KF_HASH = getBytes("12345678901234567890123456789012");
    private static final byte[] ENCRYPTED_RECOVERY_KEY =
            new byte[] {
                (byte) 0x02, (byte) 0x00, (byte) 0x04, (byte) 0xe3, (byte) 0xa8, (byte) 0xd0,
                (byte) 0x32, (byte) 0x3c, (byte) 0xc7, (byte) 0xe5, (byte) 0xe8, (byte) 0xc1,
                (byte) 0x73, (byte) 0x4c, (byte) 0x75, (byte) 0x20, (byte) 0x2e, (byte) 0xb7,
                (byte) 0xba, (byte) 0xef, (byte) 0x3e, (byte) 0x3e, (byte) 0xa6, (byte) 0x93,
                (byte) 0xe9, (byte) 0xde, (byte) 0xa7, (byte) 0x00, (byte) 0x09, (byte) 0xba,
                (byte) 0xa8, (byte) 0x9c, (byte) 0xac, (byte) 0x72, (byte) 0xff, (byte) 0xf6,
                (byte) 0x84, (byte) 0x16, (byte) 0xb0, (byte) 0xff, (byte) 0x47, (byte) 0x98,
                (byte) 0x53, (byte) 0xc4, (byte) 0xa3, (byte) 0x4a, (byte) 0x54, (byte) 0x21,
                (byte) 0x8e, (byte) 0x00, (byte) 0x4b, (byte) 0xfa, (byte) 0xce, (byte) 0xe3,
                (byte) 0x79, (byte) 0x8e, (byte) 0x20, (byte) 0x7c, (byte) 0x9b, (byte) 0xc4,
                (byte) 0x7c, (byte) 0xd5, (byte) 0x33, (byte) 0x70, (byte) 0x96, (byte) 0xdc,
                (byte) 0xa0, (byte) 0x1f, (byte) 0x6e, (byte) 0xbb, (byte) 0x5d, (byte) 0x0c,
                (byte) 0x64, (byte) 0x5f, (byte) 0xed, (byte) 0xbf, (byte) 0x79, (byte) 0x8a,
                (byte) 0x0e, (byte) 0xd6, (byte) 0x4b, (byte) 0x93, (byte) 0xc9, (byte) 0xcd,
                (byte) 0x25, (byte) 0x06, (byte) 0x73, (byte) 0x5e, (byte) 0xdb, (byte) 0xac,
                (byte) 0xa8, (byte) 0xeb, (byte) 0x6e, (byte) 0x26, (byte) 0x77, (byte) 0x56,
                (byte) 0xd1, (byte) 0x23, (byte) 0x48, (byte) 0xb6, (byte) 0x6a, (byte) 0x15,
                (byte) 0xd4, (byte) 0x3e, (byte) 0x38, (byte) 0x7d, (byte) 0x6f, (byte) 0x6f,
                (byte) 0x7c, (byte) 0x0b, (byte) 0x93, (byte) 0x4e, (byte) 0xb3, (byte) 0x21,
                (byte) 0x44, (byte) 0x86, (byte) 0xf3, (byte) 0x2e
            };
    private static final byte[] KEY_CLAIMANT = getBytes("asdfasdfasdfasdf");
    private static final byte[] RECOVERY_CLAIM =
            new byte[] {
                (byte) 0x02, (byte) 0x00, (byte) 0x04, (byte) 0x16, (byte) 0x75, (byte) 0x5b,
                (byte) 0xa2, (byte) 0xdc, (byte) 0x2b, (byte) 0x58, (byte) 0xb9, (byte) 0x66,
                (byte) 0xcb, (byte) 0x6f, (byte) 0xb1, (byte) 0xc1, (byte) 0xb0, (byte) 0x1d,
                (byte) 0x82, (byte) 0x29, (byte) 0x97, (byte) 0xec, (byte) 0x65, (byte) 0x5e,
                (byte) 0xef, (byte) 0x14, (byte) 0xc7, (byte) 0xf0, (byte) 0xf1, (byte) 0x83,
                (byte) 0x15, (byte) 0x0b, (byte) 0xcb, (byte) 0x33, (byte) 0x2d, (byte) 0x05,
                (byte) 0x20, (byte) 0xdc, (byte) 0xc7, (byte) 0x0d, (byte) 0xc8, (byte) 0xc0,
                (byte) 0xc9, (byte) 0xa8, (byte) 0x67, (byte) 0xc8, (byte) 0x16, (byte) 0xfe,
                (byte) 0xfb, (byte) 0xb0, (byte) 0x28, (byte) 0x8e, (byte) 0x4f, (byte) 0xd5,
                (byte) 0x31, (byte) 0xa7, (byte) 0x94, (byte) 0x33, (byte) 0x23, (byte) 0x15,
                (byte) 0x04, (byte) 0xbf, (byte) 0x13, (byte) 0x6a, (byte) 0x28, (byte) 0x8f,
                (byte) 0xa6, (byte) 0xfc, (byte) 0x01, (byte) 0xd5, (byte) 0x69, (byte) 0x3d,
                (byte) 0x96, (byte) 0x0c, (byte) 0x37, (byte) 0xb4, (byte) 0x1e, (byte) 0x13,
                (byte) 0x40, (byte) 0xcc, (byte) 0x44, (byte) 0x19, (byte) 0xf2, (byte) 0xdb,
                (byte) 0x49, (byte) 0x80, (byte) 0x9f, (byte) 0xef, (byte) 0xee, (byte) 0x41,
                (byte) 0xe6, (byte) 0x3f, (byte) 0xa8, (byte) 0xea, (byte) 0x89, (byte) 0xfe,
                (byte) 0x56, (byte) 0x20, (byte) 0xba, (byte) 0x90, (byte) 0x9a, (byte) 0xba,
                (byte) 0x0e, (byte) 0x30, (byte) 0xa7, (byte) 0x2b, (byte) 0x0a, (byte) 0x12,
                (byte) 0x0b, (byte) 0x03, (byte) 0xd1, (byte) 0x0c, (byte) 0x8e, (byte) 0x82,
                (byte) 0x03, (byte) 0xa1, (byte) 0x7f, (byte) 0xc8, (byte) 0xd0, (byte) 0xa9,
                (byte) 0x86, (byte) 0x55, (byte) 0x63, (byte) 0xdc, (byte) 0x70, (byte) 0x34,
                (byte) 0x21, (byte) 0x2a, (byte) 0x41, (byte) 0x3f, (byte) 0xbb, (byte) 0x82,
                (byte) 0x82, (byte) 0xf9, (byte) 0x2b, (byte) 0xd2, (byte) 0x33, (byte) 0x03,
                (byte) 0x50, (byte) 0xd2, (byte) 0x27, (byte) 0xeb, (byte) 0x1a
            };

    private static final byte[] TEST_SHARED_SECRET = getBytes("TEST_SHARED_SECRET");
    private static final byte[] TEST_HEADER = getBytes("TEST_HEADER");
    private static final byte[] TEST_PAYLOAD = getBytes("TEST_PAYLOAD");

    private static final PublicKey THM_PUBLIC_KEY;
    private static final PrivateKey THM_PRIVATE_KEY;

    static {
        try {
            THM_PUBLIC_KEY =
                    SecureBox.decodePublicKey(
                            new byte[] {
                                (byte) 0x04, (byte) 0xb8, (byte) 0x00, (byte) 0x11, (byte) 0x18,
                                (byte) 0x98, (byte) 0x1d, (byte) 0xf0, (byte) 0x6e, (byte) 0xb4,
                                (byte) 0x94, (byte) 0xfe, (byte) 0x86, (byte) 0xda, (byte) 0x1c,
                                (byte) 0x07, (byte) 0x8d, (byte) 0x01, (byte) 0xb4, (byte) 0x3a,
                                (byte) 0xf6, (byte) 0x8d, (byte) 0xdc, (byte) 0x61, (byte) 0xd0,
                                (byte) 0x46, (byte) 0x49, (byte) 0x95, (byte) 0x0f, (byte) 0x10,
                                (byte) 0x86, (byte) 0x93, (byte) 0x24, (byte) 0x66, (byte) 0xe0,
                                (byte) 0x3f, (byte) 0xd2, (byte) 0xdf, (byte) 0xf3, (byte) 0x79,
                                (byte) 0x20, (byte) 0x1d, (byte) 0x91, (byte) 0x55, (byte) 0xb0,
                                (byte) 0xe5, (byte) 0xbd, (byte) 0x7a, (byte) 0x8b, (byte) 0x32,
                                (byte) 0x7d, (byte) 0x25, (byte) 0x53, (byte) 0xa2, (byte) 0xfc,
                                (byte) 0xa5, (byte) 0x65, (byte) 0xe1, (byte) 0xbd, (byte) 0x21,
                                (byte) 0x44, (byte) 0x7e, (byte) 0x78, (byte) 0x52, (byte) 0xfa
                            });
            THM_PRIVATE_KEY =
                    decodePrivateKey(
                            new byte[] {
                                (byte) 0x70, (byte) 0x01, (byte) 0xc7, (byte) 0x87, (byte) 0x32,
                                (byte) 0x2f, (byte) 0x1c, (byte) 0x9a, (byte) 0x6e, (byte) 0xb1,
                                (byte) 0x91, (byte) 0xca, (byte) 0x4e, (byte) 0xb5, (byte) 0x44,
                                (byte) 0xba, (byte) 0xc8, (byte) 0x68, (byte) 0xc6, (byte) 0x0a,
                                (byte) 0x76, (byte) 0xcb, (byte) 0xd3, (byte) 0x63, (byte) 0x67,
                                (byte) 0x7c, (byte) 0xb0, (byte) 0x11, (byte) 0x82, (byte) 0x65,
                                (byte) 0x77, (byte) 0x01
                            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    public void genKeyPair_alwaysReturnsANewKeyPair() throws Exception {
        KeyPair keyPair1 = SecureBox.genKeyPair();
        KeyPair keyPair2 = SecureBox.genKeyPair();
        assertThat(keyPair1).isNotEqualTo(keyPair2);
    }

    @Test
    public void decryptRecoveryClaim() throws Exception {
        byte[] claimContent =
                SecureBox.decrypt(
                        THM_PRIVATE_KEY,
                        /*sharedSecret=*/ null,
                        SecureBox.concat(getBytes("V1 KF_claim"), VAULT_PARAMS, VAULT_CHALLENGE),
                        RECOVERY_CLAIM);
        assertThat(claimContent).isEqualTo(SecureBox.concat(THM_KF_HASH, KEY_CLAIMANT));
    }

    @Test
    public void decryptRecoveryKey_doesNotThrowForValidAuthenticationTag() throws Exception {
        SecureBox.decrypt(
                THM_PRIVATE_KEY,
                THM_KF_HASH,
                SecureBox.concat(getBytes("V1 THM_encrypted_recovery_key"), VAULT_PARAMS),
                ENCRYPTED_RECOVERY_KEY);
    }

    @Test
    public void encryptThenDecrypt() throws Exception {
        byte[] state = TEST_PAYLOAD;
        // Iterate multiple times to amplify any errors
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            state = SecureBox.encrypt(THM_PUBLIC_KEY, TEST_SHARED_SECRET, TEST_HEADER, state);
        }
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            state = SecureBox.decrypt(THM_PRIVATE_KEY, TEST_SHARED_SECRET, TEST_HEADER, state);
        }
        assertThat(state).isEqualTo(TEST_PAYLOAD);
    }

    @Test
    public void encryptThenDecrypt_nullPublicPrivateKeys() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(
                        /*theirPublicKey=*/ null, TEST_SHARED_SECRET, TEST_HEADER, TEST_PAYLOAD);
        byte[] decrypted =
                SecureBox.decrypt(
                        /*ourPrivateKey=*/ null, TEST_SHARED_SECRET, TEST_HEADER, encrypted);
        assertThat(decrypted).isEqualTo(TEST_PAYLOAD);
    }

    @Test
    public void encryptThenDecrypt_nullSharedSecret() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(
                        THM_PUBLIC_KEY, /*sharedSecret=*/ null, TEST_HEADER, TEST_PAYLOAD);
        byte[] decrypted =
                SecureBox.decrypt(THM_PRIVATE_KEY, /*sharedSecret=*/ null, TEST_HEADER, encrypted);
        assertThat(decrypted).isEqualTo(TEST_PAYLOAD);
    }

    @Test
    public void encryptThenDecrypt_nullHeader() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(
                        THM_PUBLIC_KEY, TEST_SHARED_SECRET, /*header=*/ null, TEST_PAYLOAD);
        byte[] decrypted =
                SecureBox.decrypt(THM_PRIVATE_KEY, TEST_SHARED_SECRET, /*header=*/ null, encrypted);
        assertThat(decrypted).isEqualTo(TEST_PAYLOAD);
    }

    @Test
    public void encryptThenDecrypt_nullPayload() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(
                        THM_PUBLIC_KEY, TEST_SHARED_SECRET, TEST_HEADER, /*payload=*/ null);
        byte[] decrypted =
                SecureBox.decrypt(
                        THM_PRIVATE_KEY,
                        TEST_SHARED_SECRET,
                        TEST_HEADER,
                        /*encryptedPayload=*/ encrypted);
        assertThat(decrypted.length).isEqualTo(0);
    }

    @Test
    public void encrypt_nullPublicKeyAndSharedSecret() throws Exception {
        IllegalArgumentException expected =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                SecureBox.encrypt(
                                        /*theirPublicKey=*/ null,
                                        /*sharedSecret=*/ null,
                                        TEST_HEADER,
                                        TEST_PAYLOAD));
        assertThat(expected.getMessage()).contains("public key and shared secret");
    }

    @Test
    public void decrypt_nullPrivateKeyAndSharedSecret() throws Exception {
        IllegalArgumentException expected =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                SecureBox.decrypt(
                                        /*ourPrivateKey=*/ null,
                                        /*sharedSecret=*/ null,
                                        TEST_HEADER,
                                        TEST_PAYLOAD));
        assertThat(expected.getMessage()).contains("private key and shared secret");
    }

    @Test
    public void decrypt_nullEncryptedPayload() throws Exception {
        NullPointerException expected =
                expectThrows(
                        NullPointerException.class,
                        () ->
                                SecureBox.decrypt(
                                        THM_PRIVATE_KEY,
                                        TEST_SHARED_SECRET,
                                        TEST_HEADER,
                                        /*encryptedPayload=*/ null));
        assertThat(expected.getMessage()).contains("payload");
    }

    @Test
    public void decrypt_badAuthenticationTag() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(THM_PUBLIC_KEY, TEST_SHARED_SECRET, TEST_HEADER, TEST_PAYLOAD);
        encrypted[encrypted.length - 1] ^= (byte) 1;

        assertThrows(
                AEADBadTagException.class,
                () ->
                        SecureBox.decrypt(
                                THM_PRIVATE_KEY, TEST_SHARED_SECRET, TEST_HEADER, encrypted));
    }

    @Test
    public void encrypt_invalidPublicKey() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        PublicKey publicKey = keyGen.genKeyPair().getPublic();

        assertThrows(
                InvalidKeyException.class,
                () -> SecureBox.encrypt(publicKey, TEST_SHARED_SECRET, TEST_HEADER, TEST_PAYLOAD));
    }

    @Test
    public void decrypt_invalidPrivateKey() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(THM_PUBLIC_KEY, TEST_SHARED_SECRET, TEST_HEADER, TEST_PAYLOAD);
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        PrivateKey privateKey = keyGen.genKeyPair().getPrivate();

        assertThrows(
                InvalidKeyException.class,
                () -> SecureBox.decrypt(privateKey, TEST_SHARED_SECRET, TEST_HEADER, encrypted));
    }

    @Test
    public void decrypt_publicKeyOutsideCurve() throws Exception {
        byte[] encrypted =
                SecureBox.encrypt(THM_PUBLIC_KEY, TEST_SHARED_SECRET, TEST_HEADER, TEST_PAYLOAD);
        // Flip the least significant bit of the encoded public key
        encrypted[VERSION_LEN_BYTES + EC_PUBLIC_KEY_LEN_BYTES - 1] ^= (byte) 1;

        InvalidKeyException expected =
                expectThrows(
                        InvalidKeyException.class,
                        () ->
                                SecureBox.decrypt(
                                        THM_PRIVATE_KEY,
                                        TEST_SHARED_SECRET,
                                        TEST_HEADER,
                                        encrypted));
        assertThat(expected.getMessage()).contains("expected curve");
    }

    @Test
    public void encodeThenDecodePublicKey() throws Exception {
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            PublicKey originalKey = SecureBox.genKeyPair().getPublic();
            byte[] encodedKey = SecureBox.encodePublicKey(originalKey);
            PublicKey decodedKey = SecureBox.decodePublicKey(encodedKey);
            assertThat(originalKey).isEqualTo(decodedKey);
        }
    }

    private static byte[] getBytes(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    private static PrivateKey decodePrivateKey(byte[] keyBytes) throws Exception {
        assertThat(keyBytes.length).isEqualTo(32);
        BigInteger priv = new BigInteger(/*signum=*/ 1, keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        return keyFactory.generatePrivate(new ECPrivateKeySpec(priv, SecureBox.EC_PARAM_SPEC));
    }
}
