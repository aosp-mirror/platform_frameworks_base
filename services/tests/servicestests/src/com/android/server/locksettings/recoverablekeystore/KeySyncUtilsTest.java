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

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.ImmutableMap;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeySyncUtilsTest {
    private static final int RECOVERY_KEY_LENGTH_BITS = 256;
    private static final int THM_KF_HASH_SIZE = 256;
    private static final int KEY_CLAIMANT_LENGTH_BYTES = 16;
    private static final byte[] TEST_VAULT_HANDLE =
            new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int VAULT_PARAMS_LENGTH_BYTES = 94;
    private static final int VAULT_HANDLE_LENGTH_BYTES = 17;
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final String APPLICATION_KEY_ALGORITHM = "AES";
    private static final byte[] LOCK_SCREEN_HASH_1 =
            utf8Bytes("g09TEvo6XqVdNaYdRggzn5w2C5rCeE1F");
    private static final byte[] LOCK_SCREEN_HASH_2 =
            utf8Bytes("snQzsbvclkSsG6PwasAp1oFLzbq3KtFe");
    private static final byte[] RECOVERY_CLAIM_HEADER =
            "V1 KF_claim".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECOVERY_RESPONSE_HEADER =
            "V1 reencrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final int PUBLIC_KEY_LENGTH_BYTES = 65;
    private static final byte[] NULL_METADATA = null;
    private static final byte[] NON_NULL_METADATA = "somemetadata".getBytes(StandardCharsets.UTF_8);

    @Test
    public void calculateThmKfHash_isShaOfLockScreenHashWithPrefix() throws Exception {
        byte[] lockScreenHash = utf8Bytes("012345678910");

        byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(lockScreenHash);

        assertArrayEquals(calculateSha256(utf8Bytes("THM_KF_hash012345678910")), thmKfHash);
    }

    @Test
    public void calculateThmKfHash_is256BitsLong() throws Exception {
        byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(utf8Bytes("1234"));

        assertEquals(THM_KF_HASH_SIZE / Byte.SIZE, thmKfHash.length);
    }

    @Test
    public void generateRecoveryKey_returnsA256BitKey() throws Exception {
        SecretKey key = KeySyncUtils.generateRecoveryKey();

        assertEquals(RECOVERY_KEY_LENGTH_BITS / Byte.SIZE, key.getEncoded().length);
    }

    @Test
    public void generateRecoveryKey_generatesANewKeyEachTime() throws Exception {
        SecretKey a = KeySyncUtils.generateRecoveryKey();
        SecretKey b = KeySyncUtils.generateRecoveryKey();

        assertFalse(Arrays.equals(a.getEncoded(), b.getEncoded()));
    }

    @Test
    public void generateKeyClaimant_returns16Bytes() throws Exception {
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();

        assertEquals(KEY_CLAIMANT_LENGTH_BYTES, keyClaimant.length);
    }

    @Test
    public void generateKeyClaimant_generatesANewClaimantEachTime() {
        byte[] a = KeySyncUtils.generateKeyClaimant();
        byte[] b = KeySyncUtils.generateKeyClaimant();

        assertFalse(Arrays.equals(a, b));
    }

    @Test
    public void concat_concatenatesArrays() {
        assertArrayEquals(
                utf8Bytes("hello, world!"),
                KeySyncUtils.concat(
                        utf8Bytes("hello"),
                        utf8Bytes(", "),
                        utf8Bytes("world"),
                        utf8Bytes("!")));
    }

    @Test
    public void decryptApplicationKey_decryptsAnApplicationKey_nullMetadata() throws Exception {
        String alias = "phoebe";
        SecretKey recoveryKey = KeySyncUtils.generateRecoveryKey();
        SecretKey applicationKey = generateApplicationKey();
        Map<String, byte[]> encryptedKeys =
                KeySyncUtils.encryptKeysWithRecoveryKey(
                        recoveryKey,
                        ImmutableMap.of(alias, Pair.create(applicationKey, NULL_METADATA)));
        byte[] encryptedKey = encryptedKeys.get(alias);

        byte[] keyMaterial = KeySyncUtils.decryptApplicationKey(recoveryKey.getEncoded(),
                encryptedKey, NULL_METADATA);

        assertArrayEquals(applicationKey.getEncoded(), keyMaterial);
    }

    @Test
    public void decryptApplicationKey_decryptsAnApplicationKey_nonNullMetadata() throws Exception {
        String alias = "phoebe";
        SecretKey recoveryKey = KeySyncUtils.generateRecoveryKey();
        SecretKey applicationKey = generateApplicationKey();
        Map<String, byte[]> encryptedKeys =
                KeySyncUtils.encryptKeysWithRecoveryKey(
                        recoveryKey,
                        ImmutableMap.of(alias, Pair.create(applicationKey, NON_NULL_METADATA)));
        byte[] encryptedKey = encryptedKeys.get(alias);

        byte[] keyMaterial = KeySyncUtils.decryptApplicationKey(recoveryKey.getEncoded(),
                encryptedKey, NON_NULL_METADATA);

        assertArrayEquals(applicationKey.getEncoded(), keyMaterial);
    }

    @Test
    public void decryptApplicationKey_throwsIfUnableToDecrypt() throws Exception {
        String alias = "casper";
        Map<String, byte[]> encryptedKeys =
                KeySyncUtils.encryptKeysWithRecoveryKey(
                        KeySyncUtils.generateRecoveryKey(),
                        ImmutableMap.of("casper",
                                Pair.create(generateApplicationKey(), NULL_METADATA)));
        byte[] encryptedKey = encryptedKeys.get(alias);

        try {
            KeySyncUtils.decryptApplicationKey(KeySyncUtils.generateRecoveryKey().getEncoded(),
                    encryptedKey, NULL_METADATA);
            fail("Did not throw decrypting with bad key.");
        } catch (AEADBadTagException error) {
            // expected
        }
    }

    @Test
    public void decryptApplicationKey_throwsIfWrongMetadata() throws Exception {
        String alias1 = "casper1";
        String alias2 = "casper2";
        String alias3 = "casper3";
        SecretKey recoveryKey = KeySyncUtils.generateRecoveryKey();

        Map<String, byte[]> encryptedKeys =
                KeySyncUtils.encryptKeysWithRecoveryKey(
                        recoveryKey,
                        ImmutableMap.of(
                                alias1,
                                Pair.create(generateApplicationKey(), NULL_METADATA),
                                alias2,
                                Pair.create(generateApplicationKey(), NON_NULL_METADATA),
                                alias3,
                                Pair.create(generateApplicationKey(), NON_NULL_METADATA)));

        try {
            KeySyncUtils.decryptApplicationKey(recoveryKey.getEncoded(),
                    encryptedKeys.get(alias1), NON_NULL_METADATA);
            fail("Did not throw decrypting with wrong metadata.");
        } catch (AEADBadTagException error) {
            // expected
        }
        try {
            KeySyncUtils.decryptApplicationKey(recoveryKey.getEncoded(),
                    encryptedKeys.get(alias2), NULL_METADATA);
            fail("Did not throw decrypting with wrong metadata.");
        } catch (AEADBadTagException error) {
            // expected
        }
        try {
            KeySyncUtils.decryptApplicationKey(recoveryKey.getEncoded(),
                    encryptedKeys.get(alias3), "different".getBytes(StandardCharsets.UTF_8));
            fail("Did not throw decrypting with wrong metadata.");
        } catch (AEADBadTagException error) {
            // expected
        }
    }

    @Test
    public void decryptRecoveryKey_decryptsALocallyEncryptedKey() throws Exception {
        SecretKey recoveryKey = KeySyncUtils.generateRecoveryKey();
        byte[] encrypted = KeySyncUtils.locallyEncryptRecoveryKey(
                LOCK_SCREEN_HASH_1, recoveryKey);

        byte[] keyMaterial = KeySyncUtils.decryptRecoveryKey(LOCK_SCREEN_HASH_1, encrypted);

        assertArrayEquals(recoveryKey.getEncoded(), keyMaterial);
    }

    @Test
    public void decryptRecoveryKey_throwsIfCannotDecrypt() throws Exception {
        SecretKey recoveryKey = KeySyncUtils.generateRecoveryKey();
        byte[] encrypted = KeySyncUtils.locallyEncryptRecoveryKey(LOCK_SCREEN_HASH_1, recoveryKey);

        try {
            KeySyncUtils.decryptRecoveryKey(LOCK_SCREEN_HASH_2, encrypted);
            fail("Did not throw decrypting with bad key.");
        } catch (AEADBadTagException error) {
            // expected
        }
    }

    @Test
    public void decryptRecoveryClaimResponse_decryptsAValidResponse() throws Exception {
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] vaultParams = randomBytes(100);
        byte[] recoveryKey = randomBytes(32);
        byte[] encryptedPayload = SecureBox.encrypt(
                /*theirPublicKey=*/ null,
                /*sharedSecret=*/ keyClaimant,
                /*header=*/ KeySyncUtils.concat(RECOVERY_RESPONSE_HEADER, vaultParams),
                /*payload=*/ recoveryKey);

        byte[] decrypted = KeySyncUtils.decryptRecoveryClaimResponse(
                keyClaimant, vaultParams, encryptedPayload);

        assertArrayEquals(recoveryKey, decrypted);
    }

    @Test
    public void decryptRecoveryClaimResponse_throwsIfCannotDecrypt() throws Exception {
        byte[] vaultParams = randomBytes(100);
        byte[] recoveryKey = randomBytes(32);
        byte[] encryptedPayload = SecureBox.encrypt(
                /*theirPublicKey=*/ null,
                /*sharedSecret=*/ KeySyncUtils.generateKeyClaimant(),
                /*header=*/ KeySyncUtils.concat(RECOVERY_RESPONSE_HEADER, vaultParams),
                /*payload=*/ recoveryKey);

        try {
            KeySyncUtils.decryptRecoveryClaimResponse(
                    KeySyncUtils.generateKeyClaimant(), vaultParams, encryptedPayload);
            fail("Did not throw decrypting with bad keyClaimant");
        } catch (AEADBadTagException error) {
            // expected
        }
    }

    @Test
    public void encryptRecoveryClaim_encryptsLockScreenAndKeyClaimant() throws Exception {
        KeyPair keyPair = SecureBox.genKeyPair();
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] challenge = randomBytes(32);
        byte[] vaultParams = randomBytes(100);

        byte[] encryptedRecoveryClaim = KeySyncUtils.encryptRecoveryClaim(
                keyPair.getPublic(),
                vaultParams,
                challenge,
                LOCK_SCREEN_HASH_1,
                keyClaimant);

        byte[] decrypted = SecureBox.decrypt(
                keyPair.getPrivate(),
                /*sharedSecret=*/ null,
                /*header=*/ KeySyncUtils.concat(RECOVERY_CLAIM_HEADER, vaultParams, challenge),
                encryptedRecoveryClaim);
        assertArrayEquals(KeySyncUtils.concat(LOCK_SCREEN_HASH_1, keyClaimant), decrypted);
    }

    @Test
    public void encryptRecoveryClaim_cannotBeDecryptedWithoutChallenge() throws Exception {
        KeyPair keyPair = SecureBox.genKeyPair();
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] vaultParams = randomBytes(100);

        byte[] encryptedRecoveryClaim = KeySyncUtils.encryptRecoveryClaim(
                keyPair.getPublic(),
                vaultParams,
                /*challenge=*/ randomBytes(32),
                LOCK_SCREEN_HASH_1,
                keyClaimant);

        try {
            SecureBox.decrypt(
                    keyPair.getPrivate(),
                    /*sharedSecret=*/ null,
                    /*header=*/ KeySyncUtils.concat(
                            RECOVERY_CLAIM_HEADER, vaultParams, randomBytes(32)),
                    encryptedRecoveryClaim);
            fail("Should throw if challenge is incorrect.");
        } catch (AEADBadTagException e) {
            // expected
        }
    }

    @Test
    public void encryptRecoveryClaim_cannotBeDecryptedWithoutCorrectSecretKey() throws Exception {
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] challenge = randomBytes(32);
        byte[] vaultParams = randomBytes(100);

        byte[] encryptedRecoveryClaim = KeySyncUtils.encryptRecoveryClaim(
                SecureBox.genKeyPair().getPublic(),
                vaultParams,
                challenge,
                LOCK_SCREEN_HASH_1,
                keyClaimant);

        try {
            SecureBox.decrypt(
                    SecureBox.genKeyPair().getPrivate(),
                    /*sharedSecret=*/ null,
                    /*header=*/ KeySyncUtils.concat(
                            RECOVERY_CLAIM_HEADER, vaultParams, challenge),
                    encryptedRecoveryClaim);
            fail("Should throw if secret key is incorrect.");
        } catch (AEADBadTagException e) {
            // expected
        }
    }

    @Test
    public void encryptRecoveryClaim_cannotBeDecryptedWithoutCorrectVaultParams() throws Exception {
        KeyPair keyPair = SecureBox.genKeyPair();
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] challenge = randomBytes(32);

        byte[] encryptedRecoveryClaim = KeySyncUtils.encryptRecoveryClaim(
                keyPair.getPublic(),
                /*vaultParams=*/ randomBytes(100),
                challenge,
                LOCK_SCREEN_HASH_1,
                keyClaimant);

        try {
            SecureBox.decrypt(
                    keyPair.getPrivate(),
                    /*sharedSecret=*/ null,
                    /*header=*/ KeySyncUtils.concat(
                            RECOVERY_CLAIM_HEADER, randomBytes(100), challenge),
                    encryptedRecoveryClaim);
            fail("Should throw if vault params is incorrect.");
        } catch (AEADBadTagException e) {
            // expected
        }
    }

    @Test
    public void encryptRecoveryClaim_cannotBeDecryptedWithoutCorrectHeader() throws Exception {
        KeyPair keyPair = SecureBox.genKeyPair();
        byte[] keyClaimant = KeySyncUtils.generateKeyClaimant();
        byte[] challenge = randomBytes(32);
        byte[] vaultParams = randomBytes(100);

        byte[] encryptedRecoveryClaim = KeySyncUtils.encryptRecoveryClaim(
                keyPair.getPublic(),
                vaultParams,
                challenge,
                LOCK_SCREEN_HASH_1,
                keyClaimant);

        try {
            SecureBox.decrypt(
                    keyPair.getPrivate(),
                    /*sharedSecret=*/ null,
                    /*header=*/ KeySyncUtils.concat(randomBytes(10), vaultParams, challenge),
                    encryptedRecoveryClaim);
            fail("Should throw if header is incorrect.");
        } catch (AEADBadTagException e) {
            // expected
        }
    }

    @Test
    public void packVaultParams_returnsCorrectSize() throws Exception {
        PublicKey thmPublicKey = SecureBox.genKeyPair().getPublic();

        byte[] packedForm = KeySyncUtils.packVaultParams(
                thmPublicKey,
                /*counterId=*/ 1001L,
                /*maxAttempts=*/ 10,
                TEST_VAULT_HANDLE);

        assertEquals(VAULT_PARAMS_LENGTH_BYTES, packedForm.length);
    }

    @Test
    public void packVaultParams_encodesPublicKeyInFirst65Bytes() throws Exception {
        PublicKey thmPublicKey = SecureBox.genKeyPair().getPublic();

        byte[] packedForm = KeySyncUtils.packVaultParams(
                thmPublicKey,
                /*counterId=*/ 1001L,
                /*maxAttempts=*/ 10,
                TEST_VAULT_HANDLE);

        assertArrayEquals(
                SecureBox.encodePublicKey(thmPublicKey),
                Arrays.copyOf(packedForm, PUBLIC_KEY_LENGTH_BYTES));
    }

    @Test
    public void packVaultParams_encodesCounterIdAsSecondParam() throws Exception {
        long counterId = 103502L;

        byte[] packedForm = KeySyncUtils.packVaultParams(
                SecureBox.genKeyPair().getPublic(),
                counterId,
                /*maxAttempts=*/ 10,
                TEST_VAULT_HANDLE);

        ByteBuffer byteBuffer = ByteBuffer.wrap(packedForm)
                .order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.position(PUBLIC_KEY_LENGTH_BYTES);
        assertEquals(counterId, byteBuffer.getLong());
    }

    @Test
    public void packVaultParams_encodesMaxAttemptsAsThirdParam() throws Exception {
        int maxAttempts = 10;

        byte[] packedForm = KeySyncUtils.packVaultParams(
                SecureBox.genKeyPair().getPublic(),
                /*counterId=*/ 1001L,
                maxAttempts,
                TEST_VAULT_HANDLE);

        ByteBuffer byteBuffer = ByteBuffer.wrap(packedForm)
                .order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.position(PUBLIC_KEY_LENGTH_BYTES + Long.BYTES);
        assertEquals(maxAttempts, byteBuffer.getInt());
    }

    @Test
    public void packVaultParams_encodesVaultHandleAsLastParam() throws Exception {
        byte[] packedForm = KeySyncUtils.packVaultParams(
                SecureBox.genKeyPair().getPublic(),
                /*counterId=*/ 10021L,
                /*maxAttempts=*/ 10,
                TEST_VAULT_HANDLE);

        ByteBuffer byteBuffer = ByteBuffer.wrap(packedForm)
                .order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.position(PUBLIC_KEY_LENGTH_BYTES + Long.BYTES + Integer.BYTES);
        byte[] vaultHandle = new byte[VAULT_HANDLE_LENGTH_BYTES];
        byteBuffer.get(vaultHandle);
        assertArrayEquals(TEST_VAULT_HANDLE, vaultHandle);
    }

    @Test
    public void packVaultParams_encodesVaultHandleWithLength8AsLastParam() throws Exception {
        byte[] vaultHandleWithLenght8 = new byte[] {1, 2, 3, 4, 1, 2, 3, 4};
        byte[] packedForm = KeySyncUtils.packVaultParams(
                SecureBox.genKeyPair().getPublic(),
                /*counterId=*/ 10021L,
                /*maxAttempts=*/ 10,
                vaultHandleWithLenght8);

        ByteBuffer byteBuffer = ByteBuffer.wrap(packedForm)
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(PUBLIC_KEY_LENGTH_BYTES + Long.BYTES + Integer.BYTES + 8, packedForm.length);
        byteBuffer.position(PUBLIC_KEY_LENGTH_BYTES + Long.BYTES + Integer.BYTES);
        byte[] vaultHandle = new byte[8];
        byteBuffer.get(vaultHandle);
        assertArrayEquals(vaultHandleWithLenght8, vaultHandle);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new Random().nextBytes(bytes);
        return bytes;
    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] calculateSha256(byte[] bytes) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance(SHA_256_ALGORITHM);
        messageDigest.update(bytes);
        return messageDigest.digest();
    }

    private static SecretKey generateApplicationKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(APPLICATION_KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }
}
