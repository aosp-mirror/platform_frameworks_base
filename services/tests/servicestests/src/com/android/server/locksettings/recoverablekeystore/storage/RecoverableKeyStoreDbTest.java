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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
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
        String alias = "test";
        WrappedKey oldWrappedKey = new WrappedKey(
                getUtf8Bytes("nonce1"), getUtf8Bytes("keymaterial1"));
        mRecoverableKeyStoreDb.insertKey(
                userId, alias, oldWrappedKey, /*generationId=*/ 1);
        byte[] nonce = getUtf8Bytes("nonce2");
        byte[] keyMaterial = getUtf8Bytes("keymaterial2");
        WrappedKey newWrappedKey = new WrappedKey(nonce, keyMaterial);

        mRecoverableKeyStoreDb.insertKey(
                userId, alias, newWrappedKey, /*generationId=*/ 2);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(userId, alias);
        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
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
        int generationId = 6;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial);
        mRecoverableKeyStoreDb.insertKey(userId, alias, wrappedKey, generationId);

        WrappedKey retrievedKey = mRecoverableKeyStoreDb.getKey(userId, alias);

        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
    }

    @Test
    public void getAllKeys_getsKeysWithUserIdAndGenerationId() {
        int userId = 12;
        int generationId = 6;
        String alias = "test";
        byte[] nonce = getUtf8Bytes("nonce");
        byte[] keyMaterial = getUtf8Bytes("keymaterial");
        WrappedKey wrappedKey = new WrappedKey(nonce, keyMaterial);
        mRecoverableKeyStoreDb.insertKey(userId, alias, wrappedKey, generationId);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(userId, generationId);

        assertEquals(1, keys.size());
        assertTrue(keys.containsKey(alias));
        WrappedKey retrievedKey = keys.get(alias);
        assertArrayEquals(nonce, retrievedKey.getNonce());
        assertArrayEquals(keyMaterial, retrievedKey.getKeyMaterial());
    }

    @Test
    public void getAllKeys_doesNotReturnKeysWithBadGenerationId() {
        int userId = 12;
        WrappedKey wrappedKey = new WrappedKey(
                getUtf8Bytes("nonce"), getUtf8Bytes("keymaterial"));
        mRecoverableKeyStoreDb.insertKey(
                userId, /*alias=*/ "test", wrappedKey, /*generationId=*/ 5);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(
                userId, /*generationId=*/ 7);

        assertTrue(keys.isEmpty());
    }

    @Test
    public void getAllKeys_doesNotReturnKeysWithBadUserId() {
        int generationId = 12;
        WrappedKey wrappedKey = new WrappedKey(
                getUtf8Bytes("nonce"), getUtf8Bytes("keymaterial"));
        mRecoverableKeyStoreDb.insertKey(
                /*userId=*/ 1, /*alias=*/ "test", wrappedKey, generationId);

        Map<String, WrappedKey> keys = mRecoverableKeyStoreDb.getAllKeys(
                /*userId=*/ 2, generationId);

        assertTrue(keys.isEmpty());
    }

    private static byte[] getUtf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
