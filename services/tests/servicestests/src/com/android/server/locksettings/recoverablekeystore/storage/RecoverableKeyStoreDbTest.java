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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
import android.security.recoverablekeystore.RecoverableKeyStoreLoader;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.server.locksettings.recoverablekeystore.WrappedKey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyStoreDbTest {
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;

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
        String alias = "test";
        WrappedKey oldWrappedKey = new WrappedKey(
                getUtf8Bytes("nonce1"),
                getUtf8Bytes("keymaterial1"),
                /*platformKeyGenerationId=*/ 1);
        mRecoverableKeyStoreDb.insertKey(
                userId, uid, alias, oldWrappedKey);
        byte[] nonce = getUtf8Bytes("nonce2");
        byte[] keyMaterial = getUtf8Bytes("keymaterial2");
        WrappedKey newWrappedKey = new WrappedKey(
                nonce, keyMaterial, /*platformKeyGenerationId=*/2);

        mRecoverableKeyStoreDb.insertKey(
                userId, uid, alias, newWrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);
        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
        assertEquals(2, retrievedKey.getPlatformKeyGenerationId());
    }

    @Test
    public void insertKey_allowsTwoUidsToHaveSameAlias() {
        int userId = 6;
        String alias = "pcoulton";
        WrappedKey key1 = new WrappedKey(
                getUtf8Bytes("nonce1"),
                getUtf8Bytes("key1"),
                /*platformKeyGenerationId=*/ 1);
        WrappedKey key2 = new WrappedKey(
                getUtf8Bytes("nonce2"),
                getUtf8Bytes("key2"),
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
        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, generationId, 120);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(uid, alias);

        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
        assertEquals(generationId, retrievedKey.getPlatformKeyGenerationId());
        assertEquals(status,retrievedKey.getRecoveryStatus());
    }

    @Test
    public void getAllKeys_getsKeysWithUserIdAndGenerationId() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(userId, generationId);

        assertEquals(1, keys.size());
        assertTrue(keys.containsKey(alias));
        WrappedKey retrievedKey = keys.get(alias);
        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
        assertEquals(generationId, retrievedKey.getPlatformKeyGenerationId());
    }

    @Test
    public void getAllKeys_doesNotReturnKeysWithBadGenerationId() {
        int userId = 12;
        int uid = 6000;
        WrappedKey wrappedKey = new WrappedKey(
                getUtf8Bytes("nonce"),
                getUtf8Bytes("keymaterial"),
                /*platformKeyGenerationId=*/ 5);
        mRecoverableKeyStoreDb.insertKey(
                userId, uid, /*alias=*/ "test", wrappedKey);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(
                userId, /*generationId=*/ 7);

        assertTrue(keys.isEmpty());
    }

    @Test
    public void getAllKeys_doesNotReturnKeysWithBadUserId() {
        int generationId = 12;
        int uid = 10009;
        WrappedKey wrappedKey = new WrappedKey(
                getUtf8Bytes("nonce"), getUtf8Bytes("keymaterial"), generationId);
        mRecoverableKeyStoreDb.insertKey(
                /*userId=*/ 1, uid, /*alias=*/ "test", wrappedKey);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(
                /*userId=*/ 2, generationId);

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
    public void setRecoveryStatus_withSingleKey() {
        int userId = 12;
        int uid = 1009;
        int generationId = 6;
        int status = 120;
        int status2 = 121;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, generationId, status);
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

        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial, generationId, status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias2, wrappedKey);
        WrappedKey wrappedKey2 = new WrappedKey(nonce, keyMaterial, generationId, status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias3, wrappedKey);
        WrappedKey wrappedKeyWithDefaultStatus = new WrappedKey(nonce, keyMaterial, generationId);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKeyWithDefaultStatus);

        Map<String, Integer> statuses = mRecoverableKeyStoreDb.getStatusForAllKeys(uid);
        assertThat(statuses).hasSize(3);
        assertThat(statuses).containsEntry(alias,
                RecoverableKeyStoreLoader.RECOVERY_STATUS_SYNC_IN_PROGRESS);
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

    private static byte[] getUtf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
