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

package com.android.server.backup.encryption.storage;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.CryptoTestUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Map;
import java.util.Optional;

/** Tests for {@link TertiaryKeysTable}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TertiaryKeysTableTest {
    private static final int KEY_SIZE_BYTES = 32;
    private static final String SECONDARY_ALIAS = "phoebe";
    private static final String PACKAGE_NAME = "generic.package.name";

    private TertiaryKeysTable mTertiaryKeysTable;

    /** Creates an empty {@link BackupEncryptionDb}. */
    @Before
    public void setUp() {
        mTertiaryKeysTable =
                BackupEncryptionDb.newInstance(RuntimeEnvironment.application)
                        .getTertiaryKeysTable();
    }

    /** Tests that new {@link TertiaryKey}s get successfully added to the database. */
    @Test
    public void addKey_onEmptyDatabase_putsKeyInDb() throws Exception {
        byte[] key = generateRandomKey();
        TertiaryKey keyToInsert = new TertiaryKey(SECONDARY_ALIAS, PACKAGE_NAME, key);

        long result = mTertiaryKeysTable.addKey(keyToInsert);

        assertThat(result).isNotEqualTo(-1);
        Optional<TertiaryKey> maybeKeyInDb =
                mTertiaryKeysTable.getKey(SECONDARY_ALIAS, PACKAGE_NAME);
        assertThat(maybeKeyInDb.isPresent()).isTrue();
        TertiaryKey keyInDb = maybeKeyInDb.get();
        assertTertiaryKeysEqual(keyInDb, keyToInsert);
    }

    /** Tests that keys replace older keys with the same secondary alias and package name. */
    @Test
    public void addKey_havingSameSecondaryAliasAndPackageName_replacesOldKey() throws Exception {
        mTertiaryKeysTable.addKey(
                new TertiaryKey(SECONDARY_ALIAS, PACKAGE_NAME, generateRandomKey()));
        byte[] newKey = generateRandomKey();

        long result =
                mTertiaryKeysTable.addKey(new TertiaryKey(SECONDARY_ALIAS, PACKAGE_NAME, newKey));

        assertThat(result).isNotEqualTo(-1);
        TertiaryKey keyInDb = mTertiaryKeysTable.getKey(SECONDARY_ALIAS, PACKAGE_NAME).get();
        assertThat(keyInDb.getWrappedKeyBytes()).isEqualTo(newKey);
    }

    /**
     * Tests that keys do not replace older keys with the same package name but a different alias.
     */
    @Test
    public void addKey_havingSamePackageNameButDifferentAlias_doesNotReplaceOldKey()
            throws Exception {
        String alias2 = "karl";
        TertiaryKey key1 = generateTertiaryKey(SECONDARY_ALIAS, PACKAGE_NAME);
        TertiaryKey key2 = generateTertiaryKey(alias2, PACKAGE_NAME);

        long primaryKey1 = mTertiaryKeysTable.addKey(key1);
        long primaryKey2 = mTertiaryKeysTable.addKey(key2);

        assertThat(primaryKey1).isNotEqualTo(primaryKey2);
        assertThat(mTertiaryKeysTable.getKey(SECONDARY_ALIAS, PACKAGE_NAME).isPresent()).isTrue();
        assertTertiaryKeysEqual(
                mTertiaryKeysTable.getKey(SECONDARY_ALIAS, PACKAGE_NAME).get(), key1);
        assertThat(mTertiaryKeysTable.getKey(alias2, PACKAGE_NAME).isPresent()).isTrue();
        assertTertiaryKeysEqual(mTertiaryKeysTable.getKey(alias2, PACKAGE_NAME).get(), key2);
    }

    /**
     * Tests that {@link TertiaryKeysTable#getKey(String, String)} returns an empty {@link Optional}
     * for a missing key.
     */
    @Test
    public void getKey_forMissingKey_returnsEmptyOptional() throws Exception {
        Optional<TertiaryKey> key = mTertiaryKeysTable.getKey(SECONDARY_ALIAS, PACKAGE_NAME);

        assertThat(key.isPresent()).isFalse();
    }

    /**
     * Tests that {@link TertiaryKeysTable#getAllKeys(String)} returns an empty map when no keys
     * with the secondary alias exist.
     */
    @Test
    public void getAllKeys_withNoKeysForAlias_returnsEmptyMap() throws Exception {
        assertThat(mTertiaryKeysTable.getAllKeys(SECONDARY_ALIAS)).isEmpty();
    }

    /**
     * Tests that {@link TertiaryKeysTable#getAllKeys(String)} returns all keys corresponding to the
     * provided secondary alias.
     */
    @Test
    public void getAllKeys_withMatchingKeys_returnsAllKeysWrappedWithSecondary() throws Exception {
        TertiaryKey key1 = generateTertiaryKey(SECONDARY_ALIAS, PACKAGE_NAME);
        mTertiaryKeysTable.addKey(key1);
        String package2 = "generic.package.two";
        TertiaryKey key2 = generateTertiaryKey(SECONDARY_ALIAS, package2);
        mTertiaryKeysTable.addKey(key2);
        String package3 = "generic.package.three";
        TertiaryKey key3 = generateTertiaryKey(SECONDARY_ALIAS, package3);
        mTertiaryKeysTable.addKey(key3);

        Map<String, TertiaryKey> keysByPackageName = mTertiaryKeysTable.getAllKeys(SECONDARY_ALIAS);

        assertThat(keysByPackageName).hasSize(3);
        assertThat(keysByPackageName).containsKey(PACKAGE_NAME);
        assertTertiaryKeysEqual(keysByPackageName.get(PACKAGE_NAME), key1);
        assertThat(keysByPackageName).containsKey(package2);
        assertTertiaryKeysEqual(keysByPackageName.get(package2), key2);
        assertThat(keysByPackageName).containsKey(package3);
        assertTertiaryKeysEqual(keysByPackageName.get(package3), key3);
    }

    /**
     * Tests that {@link TertiaryKeysTable#getAllKeys(String)} does not return any keys wrapped with
     * another alias.
     */
    @Test
    public void getAllKeys_withMatchingKeys_doesNotReturnKeysWrappedWithOtherAlias()
            throws Exception {
        mTertiaryKeysTable.addKey(generateTertiaryKey(SECONDARY_ALIAS, PACKAGE_NAME));
        mTertiaryKeysTable.addKey(generateTertiaryKey("somekey", "generic.package.two"));

        Map<String, TertiaryKey> keysByPackageName = mTertiaryKeysTable.getAllKeys(SECONDARY_ALIAS);

        assertThat(keysByPackageName).hasSize(1);
        assertThat(keysByPackageName).containsKey(PACKAGE_NAME);
    }

    private void assertTertiaryKeysEqual(TertiaryKey a, TertiaryKey b) {
        assertThat(a.getSecondaryKeyAlias()).isEqualTo(b.getSecondaryKeyAlias());
        assertThat(a.getPackageName()).isEqualTo(b.getPackageName());
        assertThat(a.getWrappedKeyBytes()).isEqualTo(b.getWrappedKeyBytes());
    }

    private TertiaryKey generateTertiaryKey(String alias, String packageName) {
        return new TertiaryKey(alias, packageName, generateRandomKey());
    }

    private byte[] generateRandomKey() {
        return CryptoTestUtils.generateRandomBytes(KEY_SIZE_BYTES);
    }
}
