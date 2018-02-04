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

import static android.security.keystore.recovery.KeyChainProtectionParams.TYPE_LOCKSCREEN;

import static android.security.keystore.recovery.KeyChainProtectionParams.UI_FORMAT_PASSWORD;
import static android.security.keystore.recovery.KeyChainProtectionParams.UI_FORMAT_PATTERN;
import static android.security.keystore.recovery.KeyChainProtectionParams.UI_FORMAT_PIN;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeySyncTaskTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String WRAPPING_KEY_ALIAS = "KeySyncTaskTest/WrappingKey";
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int TEST_USER_ID = 1000;
    private static final int TEST_RECOVERY_AGENT_UID = 10009;
    private static final int TEST_RECOVERY_AGENT_UID2 = 10010;
    private static final byte[] TEST_VAULT_HANDLE =
            new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final String TEST_APP_KEY_ALIAS = "rcleaver";
    private static final int TEST_GENERATION_ID = 2;
    private static final int TEST_CREDENTIAL_TYPE = CREDENTIAL_TYPE_PASSWORD;
    private static final String TEST_CREDENTIAL = "password1234";
    private static final byte[] THM_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 THM_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);

    @Mock private PlatformKeyManager mPlatformKeyManager;
    @Mock private RecoverySnapshotListenersStorage mSnapshotListenersStorage;

    private RecoverySnapshotStorage mRecoverySnapshotStorage;
    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;
    private KeyPair mKeyPair;
    private AndroidKeyStoreSecretKey mWrappingKey;
    private PlatformEncryptionKey mEncryptKey;

    private KeySyncTask mKeySyncTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);
        mKeyPair = SecureBox.genKeyPair();

        mRecoverableKeyStoreDb.setRecoverySecretTypes(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                new int[] {TYPE_LOCKSCREEN});
        mRecoverableKeyStoreDb.setRecoverySecretTypes(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2,
                new int[] {TYPE_LOCKSCREEN});
        mRecoverySnapshotStorage = new RecoverySnapshotStorage();

        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                TEST_CREDENTIAL_TYPE,
                TEST_CREDENTIAL,
                /*credentialUpdated=*/ false,
                mPlatformKeyManager);

        mWrappingKey = generateAndroidKeyStoreKey();
        mEncryptKey = new PlatformEncryptionKey(TEST_GENERATION_ID, mWrappingKey);
        when(mPlatformKeyManager.getDecryptKey(TEST_USER_ID)).thenReturn(
                new PlatformDecryptionKey(TEST_GENERATION_ID, mWrappingKey));
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void isPin_isTrueForNumericString() {
        assertTrue(KeySyncTask.isPin("3298432574398654376547"));
    }

    @Test
    public void isPin_isFalseForStringContainingLetters() {
        assertFalse(KeySyncTask.isPin("398i54369548654"));
    }

    @Test
    public void isPin_isFalseForStringContainingSymbols() {
        assertFalse(KeySyncTask.isPin("-3987543643"));
    }

    @Test
    public void hashCredentials_returnsSameHashForSameCredentialsAndSalt() {
        String credentials = "password1234";
        byte[] salt = randomBytes(16);

        assertArrayEquals(
                KeySyncTask.hashCredentials(salt, credentials),
                KeySyncTask.hashCredentials(salt, credentials));
    }

    @Test
    public void hashCredentials_returnsDifferentHashForDifferentCredentials() {
        byte[] salt = randomBytes(16);

        assertFalse(
                Arrays.equals(
                    KeySyncTask.hashCredentials(salt, "password1234"),
                    KeySyncTask.hashCredentials(salt, "password12345")));
    }

    @Test
    public void hashCredentials_returnsDifferentHashForDifferentSalt() {
        String credentials = "wowmuch";

        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentials(randomBytes(64), credentials),
                        KeySyncTask.hashCredentials(randomBytes(64), credentials)));
    }

    @Test
    public void hashCredentials_returnsDifferentHashEvenIfConcatIsSame() {
        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentials(utf8Bytes("123"), "4567"),
                        KeySyncTask.hashCredentials(utf8Bytes("1234"), "567")));
    }

    @Test
    public void getUiFormat_returnsPinIfPin() {
        assertEquals(UI_FORMAT_PIN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD, "1234"));
    }

    @Test
    public void getUiFormat_returnsPasswordIfPassword() {
        assertEquals(UI_FORMAT_PASSWORD,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD, "1234a"));
    }

    @Test
    public void getUiFormat_returnsPatternIfPattern() {
        assertEquals(UI_FORMAT_PATTERN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PATTERN, "1234"));

    }

    @Test
    public void run_doesNotSendAnythingIfNoKeysToSync() throws Exception {
        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_doesNotSendAnythingIfSnapshotIsUpToDate() throws Exception {
        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_doesNotSendAnythingIfNoRecoveryAgentSet() throws Exception {
        SecretKey applicationKey = generateKey();
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        mRecoverableKeyStoreDb.insertKey(
                TEST_USER_ID,
                TEST_RECOVERY_AGENT_UID,
                TEST_APP_KEY_ALIAS,
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey));
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);

        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_doesNotSendAnythingIfNoRecoveryAgentPendingIntentRegistered() throws Exception {
        SecretKey applicationKey = generateKey();
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        mRecoverableKeyStoreDb.insertKey(
                TEST_USER_ID,
                TEST_RECOVERY_AGENT_UID,
                TEST_APP_KEY_ALIAS,
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey));
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());

        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_doesNotSendAnythingIfNoDeviceIdIsSet() throws Exception {
        SecretKey applicationKey = generateKey();
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        mRecoverableKeyStoreDb.insertKey(
                TEST_USER_ID,
                TEST_RECOVERY_AGENT_UID,
                TEST_APP_KEY_ALIAS,
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey));
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);

        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_sendsEncryptedKeysIfAvailableToSync_withRawPublicKey() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());

        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        SecretKey applicationKey =
                addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        KeyDerivationParams keyDerivationParams =
                keyChainSnapshot.getKeyChainProtectionParams().get(0).getKeyDerivationParams();
        assertThat(keyDerivationParams.getAlgorithm()).isEqualTo(
                KeyDerivationParams.ALGORITHM_SHA256);
        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        byte[] lockScreenHash = KeySyncTask.hashCredentials(
                keyDerivationParams.getSalt(),
                TEST_CREDENTIAL);
        Long counterId = mRecoverableKeyStoreDb.getCounterId(TEST_USER_ID, TEST_RECOVERY_AGENT_UID);
        counterId = 1L; // TODO: use value from the database.
        assertThat(counterId).isNotNull();
        byte[] recoveryKey = decryptThmEncryptedKey(
                lockScreenHash,
                keyChainSnapshot.getEncryptedRecoveryKeyBlob(),
                /*vaultParams=*/ KeySyncUtils.packVaultParams(
                        mKeyPair.getPublic(),
                        counterId,
                        /*maxAttempts=*/ 10,
                        TEST_VAULT_HANDLE));
        List<WrappedApplicationKey> applicationKeys = keyChainSnapshot.getWrappedApplicationKeys();
        assertThat(applicationKeys).hasSize(1);
        assertThat(keyChainSnapshot.getCounterId()).isEqualTo(counterId);
        assertThat(keyChainSnapshot.getMaxAttempts()).isEqualTo(10);
        assertThat(keyChainSnapshot.getTrustedHardwarePublicKey())
                .isEqualTo(SecureBox.encodePublicKey(mKeyPair.getPublic()));
        assertThat(keyChainSnapshot.getServerParams()).isEqualTo(TEST_VAULT_HANDLE);
        WrappedApplicationKey keyData = applicationKeys.get(0);
        assertEquals(TEST_APP_KEY_ALIAS, keyData.getAlias());
        assertThat(keyData.getAlias()).isEqualTo(keyData.getAlias());
        byte[] appKey = KeySyncUtils.decryptApplicationKey(
                recoveryKey, keyData.getEncryptedKeyMaterial());
        assertThat(appKey).isEqualTo(applicationKey.getEncoded());
    }

    @Test
    public void run_sendsEncryptedKeysIfAvailableToSync_withCertPath() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        List<WrappedApplicationKey> applicationKeys = keyChainSnapshot.getWrappedApplicationKeys();
        assertThat(applicationKeys).hasSize(1);
        assertThat(keyChainSnapshot.getTrustedHardwarePublicKey())
                .isEqualTo(SecureBox.encodePublicKey(
                        TestData.CERT_PATH_1.getCertificates().get(0).getPublicKey()));
    }

    @Test
    public void run_setsCorrectSnapshotVersion() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getSnapshotVersion()).isEqualTo(1); // default value;
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, true);

        mKeySyncTask.run();

        keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getSnapshotVersion()).isEqualTo(2); // Updated
    }

    @Test
    public void run_recreatesMissingSnapshot() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getSnapshotVersion()).isEqualTo(1); // default value;

        mRecoverySnapshotStorage.remove(TEST_RECOVERY_AGENT_UID); // corrupt snapshot.

        mKeySyncTask.run();

        keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getSnapshotVersion()).isEqualTo(1); // Same version
    }

    @Test
    public void run_setsCorrectTypeForPassword() throws Exception {
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PASSWORD,
                "password",
                /*credentialUpdated=*/ false,
                mPlatformKeyManager);

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        SecretKey applicationKey =
                addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PASSWORD);
    }

   @Test
    public void run_setsCorrectTypeForPin() throws Exception {
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PASSWORD,
                /*credential=*/ "1234",
                /*credentialUpdated=*/ false,
                mPlatformKeyManager);

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        SecretKey applicationKey =
                addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        // Password with only digits is changed to pin.
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PIN);
    }

    @Test
    public void run_setsCorrectTypeForPattern() throws Exception {
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PATTERN,
                "12345",
                /*credentialUpdated=*/ false,
                mPlatformKeyManager);

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        SecretKey applicationKey =
                addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PATTERN);
    }

    @Test
    public void run_sendsEncryptedKeysWithTwoRegisteredAgents() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID2)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_APP_KEY_ALIAS);
        mKeySyncTask.run();

        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID2);
    }

    @Test
    public void run_sendsEncryptedKeysOnlyForAgentWhichActiveUserSecretType() throws Exception {
        mRecoverableKeyStoreDb.setRecoverySecretTypes(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                new int[] {TYPE_LOCKSCREEN, 1000});
        // Snapshot will not be created during unlock event.
        mRecoverableKeyStoreDb.setRecoverySecretTypes(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2,
                new int[] {1000});

        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID2)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_APP_KEY_ALIAS);
        mKeySyncTask.run();

        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage, never()).
                recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID2);
    }

    @Test
    public void run_doesNotSendKeyToNonregisteredAgent() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, mKeyPair.getPublic());
        mRecoverableKeyStoreDb.setRecoveryServicePublicKey(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, mKeyPair.getPublic());
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID2)).thenReturn(false);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_APP_KEY_ALIAS);
        mKeySyncTask.run();

        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage, never()).
                recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID2);
    }

    private SecretKey addApplicationKey(int userId, int recoveryAgentUid, String alias)
            throws Exception{
        SecretKey applicationKey = generateKey();
        mRecoverableKeyStoreDb.setServerParams(
                userId, recoveryAgentUid, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(userId, TEST_GENERATION_ID);

        // Newly added key is not synced.
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, recoveryAgentUid, true);

        mRecoverableKeyStoreDb.insertKey(
                userId,
                recoveryAgentUid,
                alias,
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey));
        return applicationKey;
    }

    private byte[] decryptThmEncryptedKey(
            byte[] lockScreenHash, byte[] encryptedKey, byte[] vaultParams) throws Exception {
        byte[] locallyEncryptedKey = SecureBox.decrypt(
                mKeyPair.getPrivate(),
                /*sharedSecret=*/ KeySyncUtils.calculateThmKfHash(lockScreenHash),
                /*header=*/ KeySyncUtils.concat(THM_ENCRYPTED_RECOVERY_KEY_HEADER, vaultParams),
                encryptedKey
        );
        return KeySyncUtils.decryptRecoveryKey(lockScreenHash, locallyEncryptedKey);
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
                WRAPPING_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return (AndroidKeyStoreSecretKey) keyGenerator.generateKey();
    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new Random().nextBytes(bytes);
        return bytes;
    }
}
