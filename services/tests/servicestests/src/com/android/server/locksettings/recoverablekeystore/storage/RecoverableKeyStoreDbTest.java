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

package com.android.server.locksettings.recoverablekeystore.storage;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.security.keystore.recovery.RecoveryController;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.TestData;
import com.android.server.locksettings.recoverablekeystore.WrappedKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyStoreDbTest {
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";

    private static final String TEST_ROOT_CERT_ALIAS = "trusted_root";
    private static final String TEST_ROOT_CERT_ALIAS2 = "another_trusted_root";

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;

    private static final byte[] SERVER_PARAMS =
            new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2};

    private static final byte[] SERVER_PARAMS2 =
            new byte[]{1, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4};

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void insertKey_replacesOldKey() {
        int userId = 12;
        int uid = 10009;
        String alias = "test-alias";

        byte[] nonce = getUtf8Bytes("nonce1");
        byte[] keyMaterial = getUtf8Bytes("keymaterial1");
        byte[] keyMetadata = null;
        int generationId = 1;
        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
        assertArrayEquals(keyMetadata, retrievedKey.getKeyMetadata());
        assertEquals(generationId, retrievedKey.getPlatformKeyGenerationId());

        nonce = getUtf8Bytes("nonce2");
        keyMaterial = getUtf8Bytes("keymaterial2");
        keyMetadata = getUtf8Bytes("keymetadata2");
        generationId = 2;
        wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
        assertArrayEquals(keyMetadata, retrievedKey.getKeyMetadata());
        assertEquals(generationId, retrievedKey.getPlatformKeyGenerationId());
    }

    @Test
    public void insertKey_allowsTwoUidsToHaveSameAlias() {
        int userId = 6;
        String alias = "pcoulton";
        WrappedKey key1 = new WrappedKey(
                getUtf8Bytes("nonce1"),
                getUtf8Bytes("key1"),
                /*metadata=*/ null,
                /*platformKeyGenerationId=*/ 1);
        WrappedKey key2 = new WrappedKey(
                getUtf8Bytes("nonce2"),
                getUtf8Bytes("key2"),
                /*metadata=*/ null,
                /*platformKeyGenerationId=*/ 1);

        mRecoverableKeyStoreDb.insertKey(userId, /*uid=*/ 1, alias, key1);
        mRecoverableKeyStoreDb.insertKey(userId, /*uid=*/ 2, alias, key2);

        assertArrayEquals(
                getUtf8Bytes("nonce1"),
                mRecoverableKeyStoreDb.getKey(1, alias).getNonce());
        assertArrayEquals(
                getUtf8Bytes("nonce2"),
                mRecoverableKeyStoreDb.getKey(2, alias).getNonce());
    }

    @Test
    public void removeKey_removesAKey() {
        int userId = 6;
        int uid = 60001;
        String alias = "rupertbates";
        WrappedKey key = new WrappedKey(
                getUtf8Bytes("nonce1"),
                getUtf8Bytes("key1"),
                /*metadata=*/ null,
                /*platformKeyGenerationId=*/ 1);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, key);

        assertTrue(mRecoverableKeyStoreDb.removeKey(uid, alias));

        assertNull(mRecoverableKeyStoreDb.getKey(uid, alias));
    }

    @Test
    public void getKey_returnsNullIfNoKey() {
        WrappedKey key = mRecoverableKeyStoreDb.getKey(
                /*userId=*/ 1, /*alias=*/ "hello");

        assertNull(key);
    }

    @Test
    public void getKey_returnsInsertedKey() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        int status = 120;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        byte[] keyMetadata = getUtf8Bytes("keymetametametadata");

        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId, 120);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);

        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
        assertArrayEquals(keyMetadata, retrievedKey.getKeyMetadata());
        assertEquals(generationId, retrievedKey.getPlatformKeyGenerationId());
        assertEquals(status,retrievedKey.getRecoveryStatus());
    }

    @Test
    public void getAllKeys_getsKeysWithUserIdAndGenerationId() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;

        String alias1 = "alias1";
        byte[] nonce1 = getUtf8Bytes("nonce1");
        byte[] keyMaterial1 = getUtf8Bytes("keymaterial1");
        byte[] keyMetadata1 = getUtf8Bytes("keyallmetadata1");
        WrappedKey wrappedKey1 = new WrappedKey(nonce1, keyMaterial1, keyMetadata1, generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias1, wrappedKey1);

        String alias2 = "alias2";
        byte[] nonce2 = getUtf8Bytes("nonce2");
        byte[] keyMaterial2 = getUtf8Bytes("keymaterial2");
        byte[] keyMetadata2 = null;
        WrappedKey wrappedKey2 = new WrappedKey(nonce2, keyMaterial2, keyMetadata2, generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias2, wrappedKey2);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(userId, uid, generationId);
        assertEquals(2, keys.size());

        assertTrue(keys.containsKey(alias1));
        WrappedKey retrievedKey1 = keys.get(alias1);
        assertArrayEquals(nonce1, retrievedKey1.getNonce());
        assertArrayEquals(keyMaterial1, retrievedKey1.getKeyMaterial());
        assertArrayEquals(keyMetadata1, retrievedKey1.getKeyMetadata());
        assertEquals(generationId, retrievedKey1.getPlatformKeyGenerationId());

        assertTrue(keys.containsKey(alias2));
        WrappedKey retrievedKey2 = keys.get(alias2);
        assertArrayEquals(nonce2, retrievedKey2.getNonce());
        assertArrayEquals(keyMaterial2, retrievedKey2.getKeyMaterial());
        assertArrayEquals(keyMetadata2, retrievedKey2.getKeyMetadata());
        assertEquals(generationId, retrievedKey2.getPlatformKeyGenerationId());
    }

    @Test
    public void getAllKeys_doesNotReturnKeysWithBadGenerationId() {
        int userId = 12;
        int uid = 6000;
        WrappedKey wrappedKey = new WrappedKey(
                getUtf8Bytes("nonce"),
                getUtf8Bytes("keymaterial"),
                /*metadata=*/ null,
                /*platformKeyGenerationId=*/ 5);
        mRecoverableKeyStoreDb.insertKey(
                userId, uid, /*alias=*/ "test", wrappedKey);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(
                userId, uid, /*generationId=*/ 7);

        assertTrue(keys.isEmpty());
    }

    @Test
    public void getAllKeys_doesNotReturnKeysWithBadUserId() {
        int generationId = 12;
        int uid = 10009;
        WrappedKey wrappedKey = new WrappedKey(
                getUtf8Bytes("nonce"), getUtf8Bytes("keymaterial"), /*metadata=*/ null,
                generationId);
        mRecoverableKeyStoreDb.insertKey(
                /*userId=*/ 1, uid, /*alias=*/ "test", wrappedKey);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(
                /*userId=*/ 2, uid, generationId);

        assertTrue(keys.isEmpty());
    }

    @Test
    public void getPlatformKeyGenerationId_returnsGenerationId() {
        int userId = 42;
        int generationId = 24;
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, generationId);

        assertEquals(generationId, mRecoverableKeyStoreDb.getPlatformKeyGenerationId(userId));
    }

    @Test
    public void getPlatformKeyGenerationId_returnsMinusOneIfNoEntry() {
        assertEquals(-1, mRecoverableKeyStoreDb.getPlatformKeyGenerationId(42));
    }

    @Test
    public void setPlatformKeyGenerationId_replacesOldEntry() {
        int userId = 42;
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, 1);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, 2);

        assertEquals(2, mRecoverableKeyStoreDb.getPlatformKeyGenerationId(userId));
    }

    @Test
    public void getUserSerialNumbers_returnsSerialNumbers() {
        int userId = 42;
        int userId2 = 44;
        Long serialNumber = 24L;
        Long serialNumber2 = 25L;
        mRecoverableKeyStoreDb.setUserSerialNumber(userId, serialNumber);
        mRecoverableKeyStoreDb.setUserSerialNumber(userId2, serialNumber2);

        assertEquals(2, mRecoverableKeyStoreDb.getUserSerialNumbers().size());
        assertEquals(serialNumber, mRecoverableKeyStoreDb.getUserSerialNumbers().get(userId));
        assertEquals(serialNumber2, mRecoverableKeyStoreDb.getUserSerialNumbers().get(userId2));
    }

    @Test
    public void getUserSerialNumbers_returnsMinusOneIfNoEntry() {
        int userId = 42;
        int generationId = 24;
        Long serialNumber = -1L;
        // Don't set serial number
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, generationId);

        assertEquals(1, mRecoverableKeyStoreDb.getUserSerialNumbers().size());
        assertEquals(serialNumber, mRecoverableKeyStoreDb.getUserSerialNumbers().get(userId));
    }

    @Test
    public void setUserSerialNumbers_keepsPlatformKeyGenerationId() {
        int userId = 42;
        int generationId = 110;
        Long serialNumber = 10L;
        int badGuessCounter = 3;

        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, generationId);
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(userId, badGuessCounter);
        mRecoverableKeyStoreDb.setUserSerialNumber(userId, serialNumber);

        assertEquals(badGuessCounter, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(userId));
        assertEquals(generationId, mRecoverableKeyStoreDb.getPlatformKeyGenerationId(userId));
    }

    @Test
    public void getRemoteBadGuessCounter_returnsZeroAsDefaultValue() {
        assertEquals(0, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(42));
    }

    @Test
    public void getRemoteBadGuessCounter_returnsStoredValue() {
        int userId = 42;
        int userId2 = 44;
        int badGuessCounter = 3;
        int badGuessCounter2 = 4;

        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(userId, badGuessCounter);
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(userId2, badGuessCounter2);

        assertEquals(badGuessCounter, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(userId));
        assertEquals(badGuessCounter2, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(userId2));
    }

    @Test
    public void getBadRemoteGuessCounter_returnsStoredValue() {
        int userId = 42;
        int userId2 = 44;
        int badGuessCounter = 3;
        int badGuessCounter2 = 4;

        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(userId, badGuessCounter);
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(userId2, badGuessCounter2);

        assertEquals(badGuessCounter, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(userId));
        assertEquals(badGuessCounter2, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(userId2));
    }

    @Test
    public void setPlatformKeyGenerationId_keepsUserSerialNumber() {
        int userId = 42;
        int generationId = 110;
        Long serialNumber = 10L;
        int badGuessCounter = 3;

        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, generationId);
        mRecoverableKeyStoreDb.setUserSerialNumber(userId, serialNumber);
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(userId, badGuessCounter);

        assertEquals(serialNumber, mRecoverableKeyStoreDb.getUserSerialNumbers().get(userId));
        assertEquals(generationId, mRecoverableKeyStoreDb.getPlatformKeyGenerationId(userId));
        assertEquals(badGuessCounter, mRecoverableKeyStoreDb.getBadRemoteGuessCounter(userId));
    }

    @Test
    public void setPlatformKeyGenerationId_invalidatesExistingKeysForUser() {
        int userId = 42;
        int generationId = 110;
        int uid = 1009;
        int status = 120;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        byte[] keyMetadata = null;

        WrappedKey wrappedKey =
                new WrappedKey(nonce, keyMaterial, keyMetadata, generationId, status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus()).isEqualTo(status);

        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, generationId + 1);

        retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus())
                .isEqualTo(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
    }


    @Test
    public void removeUserFromAllTables_removesData() throws Exception {
        int userId = 12;
        int generationId = 24;
        int[] types = new int[]{1};
        int uid = 10009;
        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS, 1234L);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, generationId);
        mRecoverableKeyStoreDb.setActiveRootOfTrust(userId, uid, "root");
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types);

        mRecoverableKeyStoreDb.removeUserFromAllTables(userId);

        // RootOfTrust
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isNull();
        // UserMetadata
        assertThat(mRecoverableKeyStoreDb.getPlatformKeyGenerationId(userId)).isEqualTo(-1);
        // RecoveryServiceMetadata
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEmpty();
    }

    @Test
    public void setRecoveryStatus_withSingleKey() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        int status = 120;
        int status2 = 121;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        byte[] keyMetadata = null;

        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId,
                status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus()).isEqualTo(status);

        mRecoverableKeyStoreDb.setRecoveryStatus(uid, alias, status2);

        retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus()).isEqualTo(status2);
    }

    @Test
    public void getStatusForAllKeys_with3Keys() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        int status = 120;
        int status2 = 121;
        String alias = "test";
        String alias2 = "test2";
        String alias3 = "test3";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        byte[] keyMetadata = null;

        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId,
                status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias2, wrappedKey);
        WrappedKey wrappedKey2 = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId,
                status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias3, wrappedKey2);
        WrappedKey wrappedKeyWithDefaultStatus = new WrappedKey(nonce, keyMaterial, keyMetadata,
                generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKeyWithDefaultStatus);

        Map<String, Integer> statuses = mRecoverableKeyStoreDb.getStatusForAllKeys(uid);
        assertThat(statuses).hasSize(3);
        assertThat(statuses).containsEntry(alias,
                RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);
        assertThat(statuses).containsEntry(alias2, status);
        assertThat(statuses).containsEntry(alias3, status);

        int updates = mRecoverableKeyStoreDb.setRecoveryStatus(uid, alias, status2);
        assertThat(updates).isEqualTo(1);
        updates = mRecoverableKeyStoreDb.setRecoveryStatus(uid, alias3, status2);
        assertThat(updates).isEqualTo(1);
        statuses = mRecoverableKeyStoreDb.getStatusForAllKeys(uid);

        assertThat(statuses).hasSize(3);
        assertThat(statuses).containsEntry(alias, status2); // updated from default
        assertThat(statuses).containsEntry(alias2, status);
        assertThat(statuses).containsEntry(alias3, status2); // updated
    }

    @Test
    public void setRecoveryStatus_withEmptyDatabase() throws Exception{
        int uid = 1009;
        String alias = "test";
        int status = 120;
        int updates = mRecoverableKeyStoreDb.setRecoveryStatus(uid, alias, status);
        assertThat(updates).isEqualTo(0); // database was empty
    }


    @Test
    public void getStatusForAllKeys_withEmptyDatabase() {
        int uid = 1009;
        Map<String, Integer> statuses = mRecoverableKeyStoreDb.getStatusForAllKeys(uid);
        assertThat(statuses).hasSize(0);
    }

    @Test
    public void testInvalidateKeysForUser_withSingleKey() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        int status = 120;
        int status2 = 121;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        byte[] keyMetadata = null;

        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId,
                status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus()).isEqualTo(status);

        mRecoverableKeyStoreDb.setRecoveryStatus(uid, alias, status2);
        mRecoverableKeyStoreDb.invalidateKeysForUser(userId);

        retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus())
                .isEqualTo(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
    }

    @Test
    public void testInvalidateKeysForUserIdOnCustomScreenLock() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        int status = 120;
        int status2 = 121;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        byte[] keyMetadata = null;

        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, keyMetadata, generationId,
                status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus()).isEqualTo(status);

        mRecoverableKeyStoreDb.setRecoveryStatus(uid, alias, status2);
        mRecoverableKeyStoreDb.invalidateKeysForUserIdOnCustomScreenLock(userId);

        retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertThat(retrievedKey.getRecoveryStatus())
            .isEqualTo(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
    }

    @Test
    public void setRecoveryServicePublicKey_replaceOldKey() throws Exception {
        int userId = 12;
        int uid = 10009;
        PublicKey pubkey1 = genRandomPublicKey();
        PublicKey pubkey2 = genRandomPublicKey();
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey1);
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey2);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey2);
    }

    @Test
    public void getRecoveryServicePublicKey_returnsNullIfNoKey() throws Exception {
        int userId = 12;
        int uid = 10009;
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isNull();

        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isNull();
    }

    @Test
    public void getRecoveryServicePublicKey_returnsInsertedKey() throws Exception {
        int userId = 12;
        int uid = 10009;
        PublicKey pubkey = genRandomPublicKey();
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey);
    }

    @Test
    public void setRecoveryServiceCertPath_replaceOldValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(userId, uid, TEST_ROOT_CERT_ALIAS,
                TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(userId, uid, TEST_ROOT_CERT_ALIAS,
                TestData.CERT_PATH_2);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(TestData.CERT_PATH_2);
    }

    @Test
    public void setRecoveryServiceCertPath_updateValuesForCorrectRootCert() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(userId, uid, TEST_ROOT_CERT_ALIAS,
                TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(userId, uid, TEST_ROOT_CERT_ALIAS2,
                TestData.CERT_PATH_1);

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(TestData.CERT_PATH_1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS2)).isEqualTo(TestData.CERT_PATH_1);

        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(userId, uid, TEST_ROOT_CERT_ALIAS2,
                TestData.CERT_PATH_2);

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(TestData.CERT_PATH_1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS2)).isEqualTo(TestData.CERT_PATH_2);
    }

    @Test
    public void getRecoveryServiceCertPath_returnsNullIfNoValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isNull();
    }

    @Test
    public void getRecoveryServiceCertPath_returnsInsertedValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(userId, uid, TEST_ROOT_CERT_ALIAS,
                TestData.CERT_PATH_1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(TestData.CERT_PATH_1);
    }

    @Test
    public void setRecoveryServiceCertSerial_replaceOldValue() throws Exception {
        int userId = 12;
        int uid = 10009;

        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid, TEST_ROOT_CERT_ALIAS, 1L);
        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid, TEST_ROOT_CERT_ALIAS, 3L);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(3L);
    }

    @Test
    public void setRecoveryServiceCertSerial_updateValuesForCorrectRootCert() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid, TEST_ROOT_CERT_ALIAS, 1L);
        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid, TEST_ROOT_CERT_ALIAS2, 1L);

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(1L);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS2)).isEqualTo(1L);

        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid, TEST_ROOT_CERT_ALIAS2, 3L);

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(1L);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS2)).isEqualTo(3L);
    }

    @Test
    public void getRecoveryServiceCertSerial_returnsNullIfNoValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isNull();
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS2)).isNull();
    }

    @Test
    public void getRecoveryServiceCertSerial_returnsInsertedValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS, 1234L);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TEST_ROOT_CERT_ALIAS)).isEqualTo(1234L);
    }

    @Test
    public void getRecoveryAgents_returnsUidIfSet() throws Exception {
        int userId = 12;
        int uid = 190992;
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, genRandomPublicKey());

        assertThat(mRecoverableKeyStoreDb.getRecoveryAgents(userId)).contains(uid);
    }

    @Test
    public void getRecoveryAgents_returnsEmptyListIfThereAreNoAgents() throws Exception {
        int userId = 12;
        assertThat(mRecoverableKeyStoreDb.getRecoveryAgents(userId)).isEmpty();
        assertThat(mRecoverableKeyStoreDb.getRecoveryAgents(userId)).isNotNull();
    }

    @Test
    public void getRecoveryAgents_withTwoAgents() throws Exception {
        int userId = 12;
        int uid1 = 190992;
        int uid2 = 190993;
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid1, genRandomPublicKey());
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid2, genRandomPublicKey());
        List<Integer> agents = mRecoverableKeyStoreDb.getRecoveryAgents(userId);

        assertThat(agents).hasSize(2);
        assertThat(agents).contains(uid1);
        assertThat(agents).contains(uid2);
    }

    @Test
    public void setActiveRootOfTrust_emptyDefaultValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        assertThat(mRecoverableKeyStoreDb.getActiveRootOfTrust(userId, uid)).isEqualTo(null);
    }

    @Test
    public void setActiveRootOfTrust_updateValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setActiveRootOfTrust(userId, uid, "root");
        assertThat(mRecoverableKeyStoreDb.getActiveRootOfTrust(userId, uid)).isEqualTo("root");

        mRecoverableKeyStoreDb.setActiveRootOfTrust(userId, uid, "root2");
        assertThat(mRecoverableKeyStoreDb.getActiveRootOfTrust(userId, uid)).isEqualTo("root2");
    }

    @Test
    public void setRecoverySecretTypes_emptyDefaultValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(
                new int[]{}); // default
    }

    @Test
    public void setRecoverySecretTypes_updateValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        int[] types1 = new int[]{1};
        int[] types2 = new int[]{2};

        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types1);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(types1);
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types2);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(types2);
    }

    @Test
    public void setRecoverySecretTypes_withMultiElementArrays() throws Exception {
        int userId = 12;
        int uid = 10009;
        int[] types1 = new int[]{11, 2000};
        int[] types2 = new int[]{1, 2, 3};
        int[] types3 = new int[]{};

        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types1);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(
                types1);
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types2);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(
                types2);
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types3);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(
                types3);
    }

    @Test
    public void setRecoverySecretTypes_withDifferentUid() throws Exception {
        int userId = 12;
        int uid1 = 10011;
        int uid2 = 10012;
        int[] types1 = new int[]{1};
        int[] types2 = new int[]{2};

        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid1, types1);
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid2, types2);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid1)).isEqualTo(
                types1);
        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid2)).isEqualTo(
                types2);
    }

    @Test
    public void setRecoveryServiceMetadataMethods() throws Exception {
        int userId = 12;
        int uid = 10009;

        PublicKey pubkey1 = genRandomPublicKey();
        int[] types1 = new int[]{1};

        PublicKey pubkey2 = genRandomPublicKey();
        int[] types2 = new int[]{2};

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey1);
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types1);
        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS);

        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(
                types1);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(
                SERVER_PARAMS);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey1);

        // Check that the methods don't interfere with each other.
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey2);
        mRecoverableKeyStoreDb.setRecoverySecretTypes(userId, uid, types2);
        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS2);

        assertThat(mRecoverableKeyStoreDb.getRecoverySecretTypes(userId, uid)).isEqualTo(
                types2);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(
                SERVER_PARAMS2);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey2);
    }

    @Test
    public void setServerParams_replaceOldValue() throws Exception {
        int userId = 12;
        int uid = 10009;

        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS);
        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS2);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(
                SERVER_PARAMS2);
    }

    @Test
    public void getServerParams_returnsNullIfNoValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isNull();

        PublicKey pubkey = genRandomPublicKey();
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isNull();
    }

    @Test
    public void getServerParams_returnsInsertedValue() throws Exception {
        int userId = 12;
        int uid = 10009;
        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(SERVER_PARAMS);
    }

    @Test
    public void setCounterId_defaultValueAndTwoUpdates() throws Exception {
        int userId = 12;
        int uid = 10009;
        long value1 = 111L;
        long value2 = 222L;
        assertThat(mRecoverableKeyStoreDb.getCounterId(userId, uid)).isNull();

        mRecoverableKeyStoreDb.setCounterId(userId, uid, value1);
        assertThat(mRecoverableKeyStoreDb.getCounterId(userId, uid)).isEqualTo(
                value1);

        mRecoverableKeyStoreDb.setCounterId(userId, uid, value2);
        assertThat(mRecoverableKeyStoreDb.getCounterId(userId, uid)).isEqualTo(
                value2);
    }

    @Test
    public void setSnapshotVersion_defaultValueAndTwoUpdates() throws Exception {
        int userId = 12;
        int uid = 10009;
        long value1 = 111L;
        long value2 = 222L;
        assertThat(mRecoverableKeyStoreDb.getSnapshotVersion(userId, uid)).isNull();
        mRecoverableKeyStoreDb.setSnapshotVersion(userId, uid, value1);
        assertThat(mRecoverableKeyStoreDb.getSnapshotVersion(userId, uid)).isEqualTo(
                value1);
        mRecoverableKeyStoreDb.setSnapshotVersion(userId, uid, value2);
        assertThat(mRecoverableKeyStoreDb.getSnapshotVersion(userId, uid)).isEqualTo(
                value2);
    }

    @Test
    public void setShouldCreateSnapshot_defaultValueAndTwoUpdates() throws Exception {
        int userId = 12;
        int uid = 10009;
        boolean value1 = true;
        boolean value2 = false;
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isEqualTo(false);
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, value1);
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isEqualTo(value1);
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, value2);
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isEqualTo(
                value2);
    }

    @Test
    public void setRecoveryServiceMetadataEntry_allowsAUserToHaveTwoUids() throws Exception {
        int userId = 12;
        int uid1 = 10009;
        int uid2 = 20009;
        PublicKey pubkey = genRandomPublicKey();
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid1, pubkey);
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid2, pubkey);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid1)).isEqualTo(
                pubkey);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid2)).isEqualTo(
                pubkey);
    }

    @Test
    public void setRecoveryServiceMetadataEntry_allowsTwoUsersToHaveTheSameUid() throws Exception {
        int userId1 = 12;
        int userId2 = 23;
        int uid = 10009;
        PublicKey pubkey = genRandomPublicKey();
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId1, uid, pubkey);
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId2, uid, pubkey);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId1, uid)).isEqualTo(
                pubkey);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId2, uid)).isEqualTo(
                pubkey);
    }

    @Test
    public void setRecoveryServiceMetadataEntry_updatesColumnsSeparately() throws Exception {
        int userId = 12;
        int uid = 10009;
        PublicKey pubkey1 = genRandomPublicKey();
        PublicKey pubkey2 = genRandomPublicKey();

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey1);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isNull();

        mRecoverableKeyStoreDb.setServerParams(userId, uid, SERVER_PARAMS);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey1);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(SERVER_PARAMS);

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(userId, uid, pubkey2);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isEqualTo(
                pubkey2);
        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(SERVER_PARAMS);
    }

    private static byte[] getUtf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static PublicKey genRandomPublicKey() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        return keyPairGenerator.generateKeyPair().getPublic();
    }
}
