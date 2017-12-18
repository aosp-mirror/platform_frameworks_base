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
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyGeneratorTest {
    private static final int TEST_GENERATION_ID = 3;
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALGORITHM = "AES";
    private static final String TEST_ALIAS = "karlin";
    private static final String WRAPPING_KEY_ALIAS = "RecoverableKeyGeneratorTestWrappingKey";

    @Mock
    RecoverableKeyStorage mRecoverableKeyStorage;

    @Captor ArgumentCaptor<KeyProtection> mKeyProtectionArgumentCaptor;

    private PlatformEncryptionKey mPlatformKey;
    private SecretKey mKeyHandle;
    private RecoverableKeyGenerator mRecoverableKeyGenerator;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPlatformKey = new PlatformEncryptionKey(TEST_GENERATION_ID, generateAndroidKeyStoreKey());
        mKeyHandle = generateKey();
        mRecoverableKeyGenerator = RecoverableKeyGenerator.newInstance(
                mPlatformKey, mRecoverableKeyStorage);

        when(mRecoverableKeyStorage.loadFromAndroidKeyStore(any())).thenReturn(mKeyHandle);
    }

    @After
    public void tearDown() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER);
        keyStore.load(/*param=*/ null);
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS);
    }

    @Test
    public void generateAndStoreKey_setsKeyInKeyStore() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(TEST_ALIAS);

        verify(mRecoverableKeyStorage, times(1))
                .importIntoAndroidKeyStore(eq(TEST_ALIAS), any(), any());
    }

    @Test
    public void generateAndStoreKey_storesKeyEnabledForEncryptDecrypt() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(TEST_ALIAS);

        KeyProtection keyProtection = getKeyProtectionUsed();
        assertEquals(KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT,
                keyProtection.getPurposes());
    }

    @Test
    public void generateAndStoreKey_storesKeyEnabledForGCM() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(TEST_ALIAS);

        KeyProtection keyProtection = getKeyProtectionUsed();
        assertArrayEquals(new String[] { KeyProperties.BLOCK_MODE_GCM },
                keyProtection.getBlockModes());
    }

    @Test
    public void generateAndStoreKey_storesKeyEnabledForNoPadding() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(TEST_ALIAS);

        KeyProtection keyProtection = getKeyProtectionUsed();
        assertArrayEquals(new String[] { KeyProperties.ENCRYPTION_PADDING_NONE },
                keyProtection.getEncryptionPaddings());
    }

    @Test
    public void generateAndStoreKey_storesWrappedKey() throws Exception {
        mRecoverableKeyGenerator.generateAndStoreKey(TEST_ALIAS);

        verify(mRecoverableKeyStorage, times(1)).persistToDisk(eq(TEST_ALIAS), any());
    }

    @Test
    public void generateAndStoreKey_returnsKeyHandle() throws Exception {
        SecretKey secretKey = mRecoverableKeyGenerator.generateAndStoreKey(TEST_ALIAS);

        assertEquals(mKeyHandle, secretKey);
    }

    private KeyProtection getKeyProtectionUsed() throws Exception {
        verify(mRecoverableKeyStorage, times(1)).importIntoAndroidKeyStore(
                any(), any(), mKeyProtectionArgumentCaptor.capture());
        return mKeyProtectionArgumentCaptor.getValue();
    }

    private SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
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
}
