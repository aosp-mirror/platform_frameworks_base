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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import com.google.common.collect.ImmutableMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyGeneratorTest {
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int TEST_GENERATION_ID = 3;
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALGORITHM = "AES";
    private static final String SUPPORTED_CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String UNSUPPORTED_CIPHER_ALGORITHM = "AES/CTR/NoPadding";
    private static final String TEST_ALIAS = "karlin";
    private static final String WRAPPING_KEY_ALIAS = "RecoverableKeyGeneratorTestWrappingKey";
    private static final int TEST_USER_ID = 1000;
    private static final int KEYSTORE_UID_SELF = -1;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_NONCE_LENGTH_BYTES = 12;

    private PlatformEncryptionKey mPlatformKey;
    private PlatformDecryptionKey mDecryptKey;
    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;
    private RecoverableKeyGenerator mRecoverableKeyGenerator;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);

        AndroidKeyStoreSecretKey platformKey = generateAndroidKeyStoreKey();
        mPlatformKey = new PlatformEncryptionKey(TEST_GENERATION_ID, platformKey);
        mDecryptKey = new PlatformDecryptionKey(TEST_GENERATION_ID, platformKey);
        mRecoverableKeyGenerator = RecoverableKeyGenerator.newInstance(mRecoverableKeyStoreDb);
    }

    @After
    public void tearDown() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER);
        keyStore.load(/*param=*/ null);
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS);

        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void generateAndStoreKey_setsKeyInKeyStore() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        KeyStore keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(KEYSTORE_UID_SELF);
        assertTrue(keyStore.containsAlias(TEST_ALIAS));
    }

    @Test
    public void generateAndStoreKey_storesKeyEnabledForAesGcmNoPaddingEncryptDecrypt()
            throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        KeyStore keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(KEYSTORE_UID_SELF);
        SecretKey key = (SecretKey) keyStore.getKey(TEST_ALIAS, /*password=*/ null);
        Cipher cipher = Cipher.getInstance(SUPPORTED_CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        Arrays.fill(nonce, (byte) 0);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
    }

    @Test
    public void generateAndStoreKey_storesKeyDisabledForOtherModes() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        KeyStore keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(KEYSTORE_UID_SELF);
        SecretKey key = (SecretKey) keyStore.getKey(TEST_ALIAS, /*password=*/ null);
        Cipher cipher = Cipher.getInstance(UNSUPPORTED_CIPHER_ALGORITHM);

        try {
            cipher.init(Cipher.ENCRYPT_MODE, key);
            fail("Should not be able to use key for " + UNSUPPORTED_CIPHER_ALGORITHM);
        } catch (InvalidKeyException e) {
            // expected
        }
    }

    @Test
    public void generateAndStoreKey_storesWrappedKey() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        KeyStore keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(KEYSTORE_UID_SELF);
        SecretKey key = (SecretKey) keyStore.getKey(TEST_ALIAS, /*password=*/ null);
        WrappedKey wrappedKey = mRecoverableKeyStoreDb.getKey(KEYSTORE_UID_SELF, TEST_ALIAS);
        SecretKey unwrappedKey = WrappedKey
                .unwrapKeys(mDecryptKey, ImmutableMap.of(TEST_ALIAS, wrappedKey))
                .get(TEST_ALIAS);

        // key and unwrappedKey should be equivalent. let's check!
        byte[] plaintext = getUtf8Bytes("dtianpos");
        Cipher cipher = Cipher.getInstance(SUPPORTED_CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        cipher.init(Cipher.DECRYPT_MODE, unwrappedKey, new GCMParameterSpec(128, iv));
        byte[] decrypted = cipher.doFinal(encrypted);
        assertArrayEquals(decrypted, plaintext);
    }

    private AndroidKeyStoreSecretKey generateAndroidKeyStoreKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KEY_ALGORITHM,
                ANDROID_KEY_STORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
        return (AndroidKeyStoreSecretKey) keyGenerator.generateKey();
    }

    private static byte[] getUtf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
