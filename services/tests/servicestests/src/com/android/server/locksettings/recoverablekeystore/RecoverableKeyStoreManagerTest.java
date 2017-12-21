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

import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_LOCKSCREEN;
import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PASSWORD;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.RemoteException;
import android.security.recoverablekeystore.KeyDerivationParameters;
import android.security.recoverablekeystore.KeyEntryRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryMetadata;
import android.security.recoverablekeystore.RecoverableKeyStoreLoader;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySessionStorage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.Random;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyStoreManagerTest {
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";

    private static final String TEST_SESSION_ID = "karlin";
    private static final byte[] TEST_PUBLIC_KEY = new byte[] {
        (byte) 0x30, (byte) 0x59, (byte) 0x30, (byte) 0x13, (byte) 0x06, (byte) 0x07, (byte) 0x2a,
        (byte) 0x86, (byte) 0x48, (byte) 0xce, (byte) 0x3d, (byte) 0x02, (byte) 0x01, (byte) 0x06,
        (byte) 0x08, (byte) 0x2a, (byte) 0x86, (byte) 0x48, (byte) 0xce, (byte) 0x3d, (byte) 0x03,
        (byte) 0x01, (byte) 0x07, (byte) 0x03, (byte) 0x42, (byte) 0x00, (byte) 0x04, (byte) 0xb8,
        (byte) 0x00, (byte) 0x11, (byte) 0x18, (byte) 0x98, (byte) 0x1d, (byte) 0xf0, (byte) 0x6e,
        (byte) 0xb4, (byte) 0x94, (byte) 0xfe, (byte) 0x86, (byte) 0xda, (byte) 0x1c, (byte) 0x07,
        (byte) 0x8d, (byte) 0x01, (byte) 0xb4, (byte) 0x3a, (byte) 0xf6, (byte) 0x8d, (byte) 0xdc,
        (byte) 0x61, (byte) 0xd0, (byte) 0x46, (byte) 0x49, (byte) 0x95, (byte) 0x0f, (byte) 0x10,
        (byte) 0x86, (byte) 0x93, (byte) 0x24, (byte) 0x66, (byte) 0xe0, (byte) 0x3f, (byte) 0xd2,
        (byte) 0xdf, (byte) 0xf3, (byte) 0x79, (byte) 0x20, (byte) 0x1d, (byte) 0x91, (byte) 0x55,
        (byte) 0xb0, (byte) 0xe5, (byte) 0xbd, (byte) 0x7a, (byte) 0x8b, (byte) 0x32, (byte) 0x7d,
        (byte) 0x25, (byte) 0x53, (byte) 0xa2, (byte) 0xfc, (byte) 0xa5, (byte) 0x65, (byte) 0xe1,
        (byte) 0xbd, (byte) 0x21, (byte) 0x44, (byte) 0x7e, (byte) 0x78, (byte) 0x52, (byte) 0xfa};
    private static final byte[] TEST_SALT = getUtf8Bytes("salt");
    private static final byte[] TEST_SECRET = getUtf8Bytes("password1234");
    private static final byte[] TEST_VAULT_CHALLENGE = getUtf8Bytes("vault_challenge");
    private static final byte[] TEST_VAULT_PARAMS = getUtf8Bytes("vault_params");
    private static final int TEST_USER_ID = 10009;
    private static final int KEY_CLAIMANT_LENGTH_BYTES = 16;
    private static final byte[] RECOVERY_RESPONSE_HEADER =
            "V1 reencrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_ALIAS = "nick";

    @Mock private Context mMockContext;

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;
    private RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    private RecoverySessionStorage mRecoverySessionStorage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);
        mRecoverySessionStorage = new RecoverySessionStorage();
        mRecoverableKeyStoreManager = new RecoverableKeyStoreManager(
                mMockContext,
                mRecoverableKeyStoreDb,
                mRecoverySessionStorage,
                Executors.newSingleThreadExecutor());
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void startRecoverySession_checksPermissionFirst() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(new KeyStoreRecoveryMetadata(
                        TYPE_LOCKSCREEN,
                        TYPE_PASSWORD,
                        KeyDerivationParameters.createSHA256Parameters(TEST_SALT),
                        TEST_SECRET)),
                TEST_USER_ID);

        verify(mMockContext, times(1)).enforceCallingOrSelfPermission(
                eq(RecoverableKeyStoreLoader.PERMISSION_RECOVER_KEYSTORE),
                any());
    }

    @Test
    public void startRecoverySession_storesTheSessionInfo() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(new KeyStoreRecoveryMetadata(
                        TYPE_LOCKSCREEN,
                        TYPE_PASSWORD,
                        KeyDerivationParameters.createSHA256Parameters(TEST_SALT),
                        TEST_SECRET)),
                TEST_USER_ID);

        assertEquals(1, mRecoverySessionStorage.size());
        RecoverySessionStorage.Entry entry = mRecoverySessionStorage.get(
                TEST_USER_ID, TEST_SESSION_ID);
        assertArrayEquals(TEST_SECRET, entry.getLskfHash());
        assertEquals(KEY_CLAIMANT_LENGTH_BYTES, entry.getKeyClaimant().length);
    }

    @Test
    public void startRecoverySession_throwsIfBadNumberOfSecrets() throws Exception {
        try {
            mRecoverableKeyStoreManager.startRecoverySession(
                    TEST_SESSION_ID,
                    TEST_PUBLIC_KEY,
                    TEST_VAULT_PARAMS,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of(),
                    TEST_USER_ID);
            fail("should have thrown");
        } catch (RemoteException e) {
            assertEquals("Only a single KeyStoreRecoveryMetadata is supported",
                    e.getMessage());
        }
    }

    @Test
    public void startRecoverySession_throwsIfBadKey() throws Exception {
        try {
            mRecoverableKeyStoreManager.startRecoverySession(
                    TEST_SESSION_ID,
                    getUtf8Bytes("0"),
                    TEST_VAULT_PARAMS,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of(new KeyStoreRecoveryMetadata(
                            TYPE_LOCKSCREEN,
                            TYPE_PASSWORD,
                            KeyDerivationParameters.createSHA256Parameters(TEST_SALT),
                            TEST_SECRET)),
                    TEST_USER_ID);
            fail("should have thrown");
        } catch (RemoteException e) {
            assertEquals("Not a valid X509 key",
                    e.getMessage());
        }
    }

    @Test
    public void recoverKeys_throwsIfNoSessionIsPresent() throws Exception {
        try {
            mRecoverableKeyStoreManager.recoverKeys(
                    TEST_SESSION_ID,
                    /*recoveryKeyBlob=*/ randomBytes(32),
                    /*applicationKeys=*/ ImmutableList.of(
                            new KeyEntryRecoveryData(getUtf8Bytes("alias"), randomBytes(32))
                    ),
                    TEST_USER_ID);
            fail("should have thrown");
        } catch (RemoteException e) {
            assertEquals("User 10009 does not have pending session 'karlin'",
                    e.getMessage());
        }
    }

    @Test
    public void recoverKeys_throwsIfRecoveryClaimCannotBeDecrypted() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(new KeyStoreRecoveryMetadata(
                        TYPE_LOCKSCREEN,
                        TYPE_PASSWORD,
                        KeyDerivationParameters.createSHA256Parameters(TEST_SALT),
                        TEST_SECRET)),
                TEST_USER_ID);

        try {
            mRecoverableKeyStoreManager.recoverKeys(
                    TEST_SESSION_ID,
                    /*encryptedRecoveryKey=*/ randomBytes(60),
                    /*applicationKeys=*/ ImmutableList.of(),
                    /*uid=*/ TEST_USER_ID);
            fail("should have thrown");
        } catch (RemoteException e) {
            assertEquals("Failed to decrypt recovery key", e.getMessage());
        }
    }

    @Test
    public void recoverKeys_throwsIfFailedToDecryptAnApplicationKey() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(new KeyStoreRecoveryMetadata(
                        TYPE_LOCKSCREEN,
                        TYPE_PASSWORD,
                        KeyDerivationParameters.createSHA256Parameters(TEST_SALT),
                        TEST_SECRET)),
                TEST_USER_ID);
        byte[] keyClaimant = mRecoverySessionStorage.get(TEST_USER_ID, TEST_SESSION_ID)
                .getKeyClaimant();
        SecretKey recoveryKey = randomRecoveryKey();
        byte[] encryptedClaimResponse = encryptClaimResponse(
                keyClaimant, TEST_SECRET, TEST_VAULT_PARAMS, recoveryKey);
        KeyEntryRecoveryData badApplicationKey = new KeyEntryRecoveryData(
                TEST_ALIAS.getBytes(StandardCharsets.UTF_8),
                randomBytes(32));

        try {
            mRecoverableKeyStoreManager.recoverKeys(
                    TEST_SESSION_ID,
                    /*encryptedRecoveryKey=*/ encryptedClaimResponse,
                    /*applicationKeys=*/ ImmutableList.of(badApplicationKey),
                    /*uid=*/ TEST_USER_ID);
            fail("should have thrown");
        } catch (RemoteException e) {
            assertEquals("Failed to recover key with alias 'nick'", e.getMessage());
        }
    }

    @Test
    public void recoverKeys_doesNotThrowIfAllIsOk() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(new KeyStoreRecoveryMetadata(
                        TYPE_LOCKSCREEN,
                        TYPE_PASSWORD,
                        KeyDerivationParameters.createSHA256Parameters(TEST_SALT),
                        TEST_SECRET)),
                TEST_USER_ID);
        byte[] keyClaimant = mRecoverySessionStorage.get(TEST_USER_ID, TEST_SESSION_ID)
                .getKeyClaimant();
        SecretKey recoveryKey = randomRecoveryKey();
        byte[] encryptedClaimResponse = encryptClaimResponse(
                keyClaimant, TEST_SECRET, TEST_VAULT_PARAMS, recoveryKey);
        KeyEntryRecoveryData applicationKey = new KeyEntryRecoveryData(
                TEST_ALIAS.getBytes(StandardCharsets.UTF_8),
                randomEncryptedApplicationKey(recoveryKey)
        );

        mRecoverableKeyStoreManager.recoverKeys(
                TEST_SESSION_ID,
                encryptedClaimResponse,
                ImmutableList.of(applicationKey),
                TEST_USER_ID);
    }

    private static byte[] randomEncryptedApplicationKey(SecretKey recoveryKey) throws Exception {
        return KeySyncUtils.encryptKeysWithRecoveryKey(recoveryKey, ImmutableMap.of(
                "alias", new SecretKeySpec(randomBytes(32), "AES")
        )).get("alias");
    }

    private static byte[] encryptClaimResponse(
            byte[] keyClaimant,
            byte[] lskfHash,
            byte[] vaultParams,
            SecretKey recoveryKey) throws Exception {
        byte[] locallyEncryptedRecoveryKey = KeySyncUtils.locallyEncryptRecoveryKey(
                lskfHash, recoveryKey);
        return SecureBox.encrypt(
                /*theirPublicKey=*/ null,
                /*sharedSecret=*/ keyClaimant,
                /*header=*/ KeySyncUtils.concat(RECOVERY_RESPONSE_HEADER, vaultParams),
                /*payload=*/ locallyEncryptedRecoveryKey);
    }

    private static SecretKey randomRecoveryKey() {
        return new SecretKeySpec(randomBytes(32), "AES");
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
