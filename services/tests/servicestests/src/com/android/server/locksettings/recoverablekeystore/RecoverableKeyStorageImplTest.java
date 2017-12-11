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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyStorageImplTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;
    private static final int GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE;
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final String TEST_KEY_ALIAS = "RecoverableKeyStorageImplTestKey";
    private static final int KEYSTORE_UID_SELF = -1;

    private RecoverableKeyStorageImpl mRecoverableKeyStorage;

    @Before
    public void setUp() throws Exception {
        mRecoverableKeyStorage = RecoverableKeyStorageImpl.newInstance(
                /*userId=*/ KEYSTORE_UID_SELF);
    }

    @After
    public void tearDown() {
        try {
            mRecoverableKeyStorage.removeFromAndroidKeyStore(TEST_KEY_ALIAS);
        } catch (KeyStoreException e) {
            // Do nothing.
        }
    }

    @Test
    public void loadFromAndroidKeyStore_loadsAKeyThatWasImported() throws Exception {
        SecretKey key = generateKey();
        mRecoverableKeyStorage.importIntoAndroidKeyStore(
                TEST_KEY_ALIAS,
                key,
                getKeyProperties());

        assertKeysAreEquivalent(
                key, mRecoverableKeyStorage.loadFromAndroidKeyStore(TEST_KEY_ALIAS));
    }

    @Test
    public void importIntoAndroidKeyStore_importsWithKeyProperties() throws Exception {
        mRecoverableKeyStorage.importIntoAndroidKeyStore(
                TEST_KEY_ALIAS,
                generateKey(),
                getKeyProperties());

        SecretKey key = mRecoverableKeyStorage.loadFromAndroidKeyStore(TEST_KEY_ALIAS);

        Mac mac = Mac.getInstance("HmacSHA256");
        try {
            // Fails because missing PURPOSE_SIGN or PURPOSE_VERIFY
            mac.init(key);
            fail("Was able to initialize Mac with an ENCRYPT/DECRYPT-only key.");
        } catch (InvalidKeyException e) {
            // expect exception
        }
    }

    @Test
    public void removeFromAndroidKeyStore_removesAnEntry() throws Exception {
        mRecoverableKeyStorage.importIntoAndroidKeyStore(
                TEST_KEY_ALIAS,
                generateKey(),
                getKeyProperties());

        mRecoverableKeyStorage.removeFromAndroidKeyStore(TEST_KEY_ALIAS);

        assertNull(mRecoverableKeyStorage.loadFromAndroidKeyStore(TEST_KEY_ALIAS));
    }

    private static KeyProtection getKeyProperties() {
        return new KeyProtection.Builder(
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build();
    }

    /**
     * Asserts that {@code b} key can decrypt data encrypted with {@code a} key. Otherwise throws.
     */
    private static void assertKeysAreEquivalent(SecretKey a, SecretKey b) throws Exception {
        byte[] plaintext = "doge".getBytes(StandardCharsets.UTF_8);
        byte[] nonce = generateGcmNonce();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, a, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        byte[] encrypted = cipher.doFinal(plaintext);

        cipher.init(Cipher.DECRYPT_MODE, b, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        byte[] decrypted = cipher.doFinal(encrypted);

        assertArrayEquals(decrypted, plaintext);
    }

    /**
     * Returns a new random GCM nonce.
     */
    private static byte[] generateGcmNonce() {
        Random random = new Random();
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    private static SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }
}
