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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WrappedKeyTest {
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String WRAPPING_KEY_ALIAS = "WrappedKeyTestWrappingKeyAlias";
    private static final int GENERATION_ID = 1;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int BITS_PER_BYTE = 8;
    private static final int GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH_BYTES * BITS_PER_BYTE;

    @After
    public void tearDown() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER);
        keyStore.load(/*param=*/ null);
        keyStore.deleteEntry(WRAPPING_KEY_ALIAS);
    }

    @Test
    public void fromSecretKey_createsWrappedKeyThatCanBeUnwrapped() throws Exception {
        SecretKey wrappingKey = generateAndroidKeyStoreKey();
        SecretKey rawKey = generateKey();

        WrappedKey wrappedKey = WrappedKey.fromSecretKey(wrappingKey, rawKey);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(
                Cipher.UNWRAP_MODE,
                wrappingKey,
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.getNonce()));
        SecretKey unwrappedKey = (SecretKey) cipher.unwrap(
                wrappedKey.getKeyMaterial(), KEY_ALGORITHM, Cipher.SECRET_KEY);
        assertEquals(rawKey, unwrappedKey);
    }

    @Test
    public void decryptWrappedKeys_decryptsWrappedKeys() throws Exception {
        String alias = "karlin";
        PlatformDecryptionKey platformKey = generatePlatformDecryptionKey();
        SecretKey appKey = generateKey();
        WrappedKey wrappedKey = WrappedKey.fromSecretKey(platformKey.getKey(), appKey);
        HashMap<String, WrappedKey> keysByAlias = new HashMap<>();
        keysByAlias.put(alias, wrappedKey);

        Map<String, SecretKey> unwrappedKeys = WrappedKey.unwrapKeys(platformKey, keysByAlias);

        assertEquals(1, unwrappedKeys.size());
        assertTrue(unwrappedKeys.containsKey(alias));
        assertArrayEquals(appKey.getEncoded(), unwrappedKeys.get(alias).getEncoded());
    }

    @Test
    public void decryptWrappedKeys_doesNotDieIfSomeKeysAreUnwrappable() throws Exception {
        String alias = "karlin";
        SecretKey appKey = generateKey();
        WrappedKey wrappedKey = WrappedKey.fromSecretKey(generateKey(), appKey);
        HashMap<String, WrappedKey> keysByAlias = new HashMap<>();
        keysByAlias.put(alias, wrappedKey);

        Map<String, SecretKey> unwrappedKeys = WrappedKey.unwrapKeys(
                generatePlatformDecryptionKey(), keysByAlias);

        assertEquals(0, unwrappedKeys.size());
    }

    @Test
    public void decryptWrappedKeys_throwsIfPlatformKeyGenerationIdDoesNotMatch() throws Exception {
        WrappedKey wrappedKey = WrappedKey.fromSecretKey(generateKey(), generateKey());
        HashMap<String, WrappedKey> keysByAlias = new HashMap<>();
        keysByAlias.put("benji", wrappedKey);

        try {
            WrappedKey.unwrapKeys(
                    generatePlatformDecryptionKey(/*generationId=*/ 2), keysByAlias);
            fail("Should have thrown.");
        } catch (BadPlatformKeyException e) {
            assertEquals(
                    "WrappedKey with alias 'benji' was wrapped with platform key 1,"
                            + " not platform key 2",
                    e.getMessage());
        }
    }

    private SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }

    private AndroidKeyStoreSecretKey generateAndroidKeyStoreKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KEY_ALGORITHM,
                ANDROID_KEY_STORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return (AndroidKeyStoreSecretKey) keyGenerator.generateKey();
    }

    private PlatformDecryptionKey generatePlatformDecryptionKey() throws Exception {
        return generatePlatformDecryptionKey(GENERATION_ID);
    }

    private PlatformDecryptionKey generatePlatformDecryptionKey(int generationId) throws Exception {
        return new PlatformDecryptionKey(generationId, generateAndroidKeyStoreKey());
    }
}
