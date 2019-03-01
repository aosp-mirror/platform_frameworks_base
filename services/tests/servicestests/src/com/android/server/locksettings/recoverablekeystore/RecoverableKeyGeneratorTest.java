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

import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyGeneratorTest {
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int TEST_GENERATION_ID = 3;
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BYTES = RecoverableKeyGenerator.KEY_SIZE_BITS / Byte.SIZE;
    private static final String KEY_WRAP_ALGORITHM = "AES/GCM/NoPadding";
    private static final String TEST_ALIAS = "karlin";
    private static final String WRAPPING_KEY_ALIAS = "RecoverableKeyGeneratorTestWrappingKey";
    private static final int TEST_USER_ID = 1000;
    private static final int KEYSTORE_UID_SELF = -1;
    private static final int GCM_TAG_LENGTH_BITS = 128;

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

        AndroidKeyStoreSecretKey platformKey = generatePlatformKey();
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
    public void generateAndStoreKey_storesWrappedKey() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        WrappedKey wrappedKey = mRecoverableKeyStoreDb.getKey(KEYSTORE_UID_SELF, TEST_ALIAS);
        assertNotNull(wrappedKey);
    }

    @Test
    public void generateAndStoreKey_returnsRawMaterialOfCorrectLength() throws Exception {
        byte[] rawKey = mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        assertEquals(KEY_SIZE_BYTES, rawKey.length);
    }

    @Test
    public void generateAndStoreKey_storesTheWrappedVersionOfTheRawMaterial() throws Exception {
        byte[] rawMaterial = mRecoverableKeyGenerator.generateAndStoreKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS);

        WrappedKey wrappedKey = mRecoverableKeyStoreDb.getKey(KEYSTORE_UID_SELF, TEST_ALIAS);
        Cipher cipher = Cipher.getInstance(KEY_WRAP_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, mDecryptKey.getKey(),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.getNonce()));
        byte[] unwrappedMaterial = cipher.doFinal(wrappedKey.getKeyMaterial());
        assertArrayEquals(rawMaterial, unwrappedMaterial);
    }

    @Test
    public void importKey_storesTheWrappedVersionOfTheRawMaterial() throws Exception {
        byte[] rawMaterial = randomBytes(KEY_SIZE_BYTES);
        mRecoverableKeyGenerator.importKey(
                mPlatformKey, TEST_USER_ID, KEYSTORE_UID_SELF, TEST_ALIAS, rawMaterial);

        WrappedKey wrappedKey = mRecoverableKeyStoreDb.getKey(KEYSTORE_UID_SELF, TEST_ALIAS);
        Cipher cipher = Cipher.getInstance(KEY_WRAP_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, mDecryptKey.getKey(),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.getNonce()));
        byte[] unwrappedMaterial = cipher.doFinal(wrappedKey.getKeyMaterial());
        assertArrayEquals(rawMaterial, unwrappedMaterial);
    }

    private AndroidKeyStoreSecretKey generatePlatformKey() throws Exception {
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

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new Random().nextBytes(bytes);
        return bytes;
    }
}
