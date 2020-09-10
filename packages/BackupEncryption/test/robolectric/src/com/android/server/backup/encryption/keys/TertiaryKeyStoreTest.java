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

package com.android.server.backup.encryption.keys;

import static com.android.server.backup.testing.CryptoTestUtils.generateAesKey;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import android.content.Context;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.SecretKey;

/** Tests for the tertiary key store */
@RunWith(RobolectricTestRunner.class)
public class TertiaryKeyStoreTest {

    private static final String SECONDARY_KEY_ALIAS = "Robbo/Ranx";

    private Context mApplication;
    private TertiaryKeyStore mTertiaryKeyStore;
    private SecretKey mSecretKey;

    /** Initialise the keystore for testing */
    @Before
    public void setUp() throws Exception {
        mApplication = RuntimeEnvironment.application;
        mSecretKey = generateAesKey();
        mTertiaryKeyStore =
                TertiaryKeyStore.newInstance(
                        mApplication,
                        new RecoverableKeyStoreSecondaryKey(SECONDARY_KEY_ALIAS, mSecretKey));
    }

    /** Test a reound trip for a key */
    @Test
    public void load_loadsAKeyThatWasSaved() throws Exception {
        String packageName = "com.android.example";
        SecretKey packageKey = generateAesKey();
        mTertiaryKeyStore.save(packageName, packageKey);

        Optional<SecretKey> maybeLoadedKey = mTertiaryKeyStore.load(packageName);

        assertTrue(maybeLoadedKey.isPresent());
        assertEquals(packageKey, maybeLoadedKey.get());
    }

    /** Test isolation between packages */
    @Test
    public void load_doesNotLoadAKeyForAnotherSecondary() throws Exception {
        String packageName = "com.android.example";
        SecretKey packageKey = generateAesKey();
        mTertiaryKeyStore.save(packageName, packageKey);
        TertiaryKeyStore managerWithOtherSecondaryKey =
                TertiaryKeyStore.newInstance(
                        mApplication,
                        new RecoverableKeyStoreSecondaryKey(
                                "myNewSecondaryKeyAlias", generateAesKey()));

        assertFalse(managerWithOtherSecondaryKey.load(packageName).isPresent());
    }

    /** Test non-existent key handling */
    @Test
    public void load_returnsAbsentForANonExistentKey() throws Exception {
        assertFalse(mTertiaryKeyStore.load("mystery.package").isPresent());
    }

    /** Test handling incorrect keys */
    @Test
    public void load_throwsIfHasWrongBackupKey() throws Exception {
        String packageName = "com.android.example";
        SecretKey packageKey = generateAesKey();
        mTertiaryKeyStore.save(packageName, packageKey);
        TertiaryKeyStore managerWithBadKey =
                TertiaryKeyStore.newInstance(
                        mApplication,
                        new RecoverableKeyStoreSecondaryKey(SECONDARY_KEY_ALIAS, generateAesKey()));

        assertThrows(InvalidKeyException.class, () -> managerWithBadKey.load(packageName));
    }

    /** Test handling of empty app name */
    @Test
    public void load_throwsForEmptyApplicationName() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mTertiaryKeyStore.load(""));
    }

    /** Test handling of an invalid app name */
    @Test
    public void load_throwsForBadApplicationName() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> mTertiaryKeyStore.load("com/android/example"));
    }

    /** Test key replacement */
    @Test
    public void save_overwritesPreviousKey() throws Exception {
        String packageName = "com.android.example";
        SecretKey oldKey = generateAesKey();
        mTertiaryKeyStore.save(packageName, oldKey);
        SecretKey newKey = generateAesKey();

        mTertiaryKeyStore.save(packageName, newKey);

        Optional<SecretKey> maybeLoadedKey = mTertiaryKeyStore.load(packageName);
        assertTrue(maybeLoadedKey.isPresent());
        SecretKey loadedKey = maybeLoadedKey.get();
        assertThat(loadedKey).isNotEqualTo(oldKey);
        assertThat(loadedKey).isEqualTo(newKey);
    }

    /** Test saving with an empty application name fails */
    @Test
    public void save_throwsForEmptyApplicationName() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> mTertiaryKeyStore.save("", generateAesKey()));
    }

    /** Test saving an invalid application name fails */
    @Test
    public void save_throwsForBadApplicationName() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> mTertiaryKeyStore.save("com/android/example", generateAesKey()));
    }

    /** Test handling an empty database */
    @Test
    public void getAll_returnsEmptyMapForEmptyDb() throws Exception {
        assertThat(mTertiaryKeyStore.getAll()).isEmpty();
    }

    /** Test loading all available keys works as expected */
    @Test
    public void getAll_returnsAllKeysSaved() throws Exception {
        String package1 = "com.android.example";
        SecretKey key1 = generateAesKey();
        String package2 = "com.anndroid.example1";
        SecretKey key2 = generateAesKey();
        String package3 = "com.android.example2";
        SecretKey key3 = generateAesKey();
        mTertiaryKeyStore.save(package1, key1);
        mTertiaryKeyStore.save(package2, key2);
        mTertiaryKeyStore.save(package3, key3);

        Map<String, SecretKey> keys = mTertiaryKeyStore.getAll();

        assertThat(keys).containsExactly(package1, key1, package2, key2, package3, key3);
    }

    /** Test cross-secondary isolation */
    @Test
    public void getAll_doesNotReturnKeysForOtherSecondary() throws Exception {
        String packageName = "com.android.example";
        TertiaryKeyStore managerWithOtherSecondaryKey =
                TertiaryKeyStore.newInstance(
                        mApplication,
                        new RecoverableKeyStoreSecondaryKey(
                                "myNewSecondaryKeyAlias", generateAesKey()));
        managerWithOtherSecondaryKey.save(packageName, generateAesKey());

        assertThat(mTertiaryKeyStore.getAll()).isEmpty();
    }

    /** Test mass put into the keystore */
    @Test
    public void putAll_putsAllWrappedKeysInTheStore() throws Exception {
        String packageName = "com.android.example";
        SecretKey key = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedKey = KeyWrapUtils.wrap(mSecretKey, key);

        Map<String, WrappedKeyProto.WrappedKey> testElements = new HashMap<>();
        testElements.put(packageName, wrappedKey);
        mTertiaryKeyStore.putAll(testElements);

        assertThat(mTertiaryKeyStore.getAll()).containsKey(packageName);
        assertThat(mTertiaryKeyStore.getAll().get(packageName).getEncoded())
                .isEqualTo(key.getEncoded());
    }
}
