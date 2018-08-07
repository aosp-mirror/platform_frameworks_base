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

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySessionStorageTest {

    private static final String TEST_SESSION_ID = "peter";
    private static final int TEST_USER_ID = 696;
    private static final byte[] TEST_LSKF_HASH = getUtf8Bytes("lskf");
    private static final byte[] TEST_KEY_CLAIMANT = getUtf8Bytes("0000111122223333");
    private static final byte[] TEST_VAULT_PARAMS = getUtf8Bytes("vault params vault params");

    @Test
    public void size_isZeroForEmpty() {
        assertEquals(0, new RecoverySessionStorage().size());
    }

    @Test
    public void size_incrementsAfterAdd() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        storage.add(TEST_USER_ID, new RecoverySessionStorage.Entry(
                TEST_SESSION_ID, lskfHashFixture(), keyClaimantFixture(), vaultParamsFixture()));

        assertEquals(1, storage.size());
    }

    @Test
    public void size_decrementsAfterRemove() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        storage.add(TEST_USER_ID, new RecoverySessionStorage.Entry(
                TEST_SESSION_ID, lskfHashFixture(), keyClaimantFixture(), vaultParamsFixture()));
        storage.remove(TEST_USER_ID);

        assertEquals(0, storage.size());
    }

    @Test
    public void remove_overwritesLskfHashMemory() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        RecoverySessionStorage.Entry entry = new RecoverySessionStorage.Entry(
                TEST_SESSION_ID, lskfHashFixture(), keyClaimantFixture(), vaultParamsFixture());
        storage.add(TEST_USER_ID, entry);

        storage.remove(TEST_USER_ID);

        assertZeroedOut(entry.getLskfHash());
    }

    @Test
    public void remove_overwritesKeyClaimantMemory() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        RecoverySessionStorage.Entry entry = new RecoverySessionStorage.Entry(
                TEST_SESSION_ID, lskfHashFixture(), keyClaimantFixture(), vaultParamsFixture());
        storage.add(TEST_USER_ID, entry);

        storage.remove(TEST_USER_ID);

        assertZeroedOut(entry.getKeyClaimant());
    }

    @Test
    public void remove_deletesSpecificSession() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        storage.add(TEST_USER_ID, new RecoverySessionStorage.Entry(
                TEST_SESSION_ID,
                lskfHashFixture(),
                keyClaimantFixture(),
                vaultParamsFixture()));
        storage.add(TEST_USER_ID, new RecoverySessionStorage.Entry(
                "some other session",
                lskfHashFixture(),
                keyClaimantFixture(),
                vaultParamsFixture()));

        storage.remove(TEST_USER_ID, TEST_SESSION_ID);

        assertNull(storage.get(TEST_USER_ID, TEST_SESSION_ID));
    }

    @Test
    public void remove_doesNotDeleteOtherSessions() {
        String otherSessionId = "some other session";
        RecoverySessionStorage storage = new RecoverySessionStorage();
        storage.add(TEST_USER_ID, new RecoverySessionStorage.Entry(
                TEST_SESSION_ID,
                lskfHashFixture(),
                keyClaimantFixture(),
                vaultParamsFixture()));
        storage.add(TEST_USER_ID, new RecoverySessionStorage.Entry(
                otherSessionId,
                lskfHashFixture(),
                keyClaimantFixture(),
                vaultParamsFixture()));

        storage.remove(TEST_USER_ID, TEST_SESSION_ID);

        assertNotNull(storage.get(TEST_USER_ID, otherSessionId));
    }

    @Test
    public void destroy_overwritesLskfHashMemory() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        RecoverySessionStorage.Entry entry = new RecoverySessionStorage.Entry(
                TEST_SESSION_ID, lskfHashFixture(), keyClaimantFixture(), vaultParamsFixture());
        storage.add(TEST_USER_ID, entry);

        storage.destroy();

        assertZeroedOut(entry.getLskfHash());
    }

    @Test
    public void destroy_overwritesKeyClaimantMemory() {
        RecoverySessionStorage storage = new RecoverySessionStorage();
        RecoverySessionStorage.Entry entry = new RecoverySessionStorage.Entry(
                TEST_SESSION_ID, lskfHashFixture(), keyClaimantFixture(), vaultParamsFixture());
        storage.add(TEST_USER_ID, entry);

        storage.destroy();

        assertZeroedOut(entry.getKeyClaimant());
    }

    private static void assertZeroedOut(byte[] bytes) {
        for (byte b : bytes) {
            if (b != (byte) 0) {
                fail("Bytes were not all zeroed out.");
            }
        }
    }

    private static byte[] lskfHashFixture() {
        return Arrays.copyOf(TEST_LSKF_HASH, TEST_LSKF_HASH.length);
    }

    private static byte[] keyClaimantFixture() {
        return Arrays.copyOf(TEST_KEY_CLAIMANT, TEST_KEY_CLAIMANT.length);
    }

    private static byte[] vaultParamsFixture() {
        return Arrays.copyOf(TEST_VAULT_PARAMS, TEST_VAULT_PARAMS.length);
    }

    private static byte[] getUtf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
