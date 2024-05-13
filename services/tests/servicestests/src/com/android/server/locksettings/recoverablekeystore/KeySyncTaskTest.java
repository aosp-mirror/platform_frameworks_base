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
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.FileUtils;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.RecoveryController;
import android.security.keystore.recovery.TrustedRootCertificates;
import android.security.keystore.recovery.WrappedApplicationKey;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;
import com.android.security.SecureBox;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeySyncTaskTest {

    private static final String SNAPSHOT_TOP_LEVEL_DIRECTORY = "recoverablekeystore";

    private static final String KEY_ALGORITHM = "AES";
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String TEST_ROOT_CERT_ALIAS = "trusted_root";
    private static final String WRAPPING_KEY_ALIAS = "KeySyncTaskTest/WrappingKey";
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int TEST_USER_ID = 1000;
    private static final int TEST_USER_ID_2 = 1002;
    private static final int TEST_RECOVERY_AGENT_UID = 10009;
    private static final int TEST_RECOVERY_AGENT_UID2 = 10010;
    private static final byte[] TEST_VAULT_HANDLE =
            new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final String TEST_APP_KEY_ALIAS = "rcleaver";
    private static final byte[] TEST_APP_KEY_METADATA_NULL = null;
    private static final byte[] TEST_APP_KEY_METADATA_NON_NULL =
            "mdata".getBytes(StandardCharsets.UTF_8);
    private static final int TEST_GENERATION_ID = 2;
    private static final int TEST_CREDENTIAL_TYPE = CREDENTIAL_TYPE_PATTERN;
    private static final String TEST_CREDENTIAL = "pas123";
    private static final byte[] THM_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 THM_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);

    @Mock private PlatformKeyManager mPlatformKeyManager;
    @Mock private RecoverySnapshotListenersStorage mSnapshotListenersStorage;
    @Spy private TestOnlyInsecureCertificateHelper mTestOnlyInsecureCertificateHelper;
    @Spy private MockScrypt mMockScrypt;

    private RecoverySnapshotStorage mRecoverySnapshotStorage;
    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;
    private SecretKey mWrappingKey;
    private PlatformEncryptionKey mEncryptKey;

    private KeySyncTask mKeySyncTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);

        mRecoverableKeyStoreDb.setRecoverySecretTypes(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                new int[] {TYPE_LOCKSCREEN});
        mRecoverableKeyStoreDb.setRecoverySecretTypes(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2,
                new int[] {TYPE_LOCKSCREEN});

        mRecoverableKeyStoreDb.setActiveRootOfTrust(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                TEST_ROOT_CERT_ALIAS);
        mRecoverableKeyStoreDb.setActiveRootOfTrust(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2,
                TEST_ROOT_CERT_ALIAS);
        mRecoverySnapshotStorage = new RecoverySnapshotStorage(context.getFilesDir());

        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                TEST_CREDENTIAL_TYPE,
                TEST_CREDENTIAL.getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);

        mWrappingKey = generateAndroidKeyStoreKey();
        mEncryptKey = new PlatformEncryptionKey(TEST_GENERATION_ID, mWrappingKey);
        when(mPlatformKeyManager.getDecryptKey(TEST_USER_ID)).thenReturn(
                new PlatformDecryptionKey(TEST_GENERATION_ID, mWrappingKey));
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();

        File file = new File(InstrumentationRegistry.getTargetContext().getFilesDir(),
                SNAPSHOT_TOP_LEVEL_DIRECTORY);
        FileUtils.deleteContentsAndDir(file);
    }

    @Test
    public void hashCredentialsBySaltedSha256_returnsSameHashForSameCredentialsAndSalt() {
        String credentials = "password1234";
        byte[] salt = randomBytes(16);

        assertArrayEquals(
                KeySyncTask.hashCredentialsBySaltedSha256(salt, credentials.getBytes()),
                KeySyncTask.hashCredentialsBySaltedSha256(salt, credentials.getBytes()));
    }

    @Test
    public void hashCredentialsBySaltedSha256_returnsDifferentHashForDifferentCredentials() {
        byte[] salt = randomBytes(16);

        assertFalse(
                Arrays.equals(
                    KeySyncTask.hashCredentialsBySaltedSha256(salt, "password1234".getBytes()),
                    KeySyncTask.hashCredentialsBySaltedSha256(salt, "password12345".getBytes())));
    }

    @Test
    public void hashCredentialsBySaltedSha256_returnsDifferentHashForDifferentSalt() {
        String credentials = "wowmuch";

        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentialsBySaltedSha256(randomBytes(64),
                                credentials.getBytes()),
                        KeySyncTask.hashCredentialsBySaltedSha256(randomBytes(64),
                                credentials.getBytes())));
    }

    @Test
    public void hashCredentialsBySaltedSha256_returnsDifferentHashEvenIfConcatIsSame() {
        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentialsBySaltedSha256(utf8Bytes("123"),
                                "4567".getBytes()),
                        KeySyncTask.hashCredentialsBySaltedSha256(utf8Bytes("1234"),
                                "567".getBytes())));
    }

    @Test
    public void getUiFormat_returnsPinIfPin() {
        assertEquals(UI_FORMAT_PIN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PIN));
    }

    @Test
    public void getUiFormat_returnsPasswordIfPassword() {
        assertEquals(UI_FORMAT_PASSWORD,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD));
    }

    @Test
    public void getUiFormat_returnsPatternIfPattern() {
        assertEquals(UI_FORMAT_PATTERN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PATTERN));

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
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey, TEST_APP_KEY_METADATA_NULL));
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);

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
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey, TEST_APP_KEY_METADATA_NULL));
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);

        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_useScryptToHashPasswordInTestMode() throws Exception {
        String password = TrustedRootCertificates.INSECURE_PASSWORD_PREFIX + "";  // The shortest
        String appKeyAlias = TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX + "alias";
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PASSWORD,
                /*credential=*/ password.getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        mRecoverableKeyStoreDb.setActiveRootOfTrust(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS,
                TestData.getInsecureCertPathForEndpoint1());
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, appKeyAlias);

        setExpectedScryptArgument(password.getBytes());

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PASSWORD);
        verify(mMockScrypt).scrypt(any(), any(),
                eq(KeySyncTask.SCRYPT_PARAM_N), eq(KeySyncTask.SCRYPT_PARAM_R),
                eq(KeySyncTask.SCRYPT_PARAM_P), eq(KeySyncTask.SCRYPT_PARAM_OUTLEN_BYTES));
        KeyDerivationParams keyDerivationParams =
                keyChainSnapshot.getKeyChainProtectionParams().get(0).getKeyDerivationParams();
        assertThat(keyDerivationParams.getAlgorithm()).isEqualTo(
                KeyDerivationParams.ALGORITHM_SCRYPT);
        assertThat(keyDerivationParams.getMemoryDifficulty()).isEqualTo(KeySyncTask.SCRYPT_PARAM_N);
    }

    @Test
    public void run_zeroizedCredential() throws Exception {
        String password = TrustedRootCertificates.INSECURE_PASSWORD_PREFIX + "123";
        String appKeyAlias = TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX + "alias";
        byte[] zeroizedCredential = password.getBytes();
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PASSWORD,
                /*credential=*/ zeroizedCredential,
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        mRecoverableKeyStoreDb.setActiveRootOfTrust(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS,
                TestData.getInsecureCertPathForEndpoint1());
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, appKeyAlias);

        // Need to check array value during method call since it is modified later.
        setExpectedScryptArgument(password.getBytes());

        Arrays.fill(zeroizedCredential, (byte) 0);
        mKeySyncTask.run();

        verify(mMockScrypt).scrypt(any(), any(),
                eq(KeySyncTask.SCRYPT_PARAM_N), eq(KeySyncTask.SCRYPT_PARAM_R),
                eq(KeySyncTask.SCRYPT_PARAM_P), eq(KeySyncTask.SCRYPT_PARAM_OUTLEN_BYTES));
    }

    @Test
    public void run_useSha256ToHashPatternInProdMode() throws Exception {
        String pattern = "123456";
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PATTERN,
                /*credential=*/ pattern.getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PATTERN);
        verify(mMockScrypt, never()).scrypt(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
        KeyDerivationParams keyDerivationParams =
                keyChainSnapshot.getKeyChainProtectionParams().get(0).getKeyDerivationParams();
        assertThat(keyDerivationParams.getAlgorithm()).isEqualTo(
                KeyDerivationParams.ALGORITHM_SHA256);
    }

    @Test
    public void run_useScryptToHashPasswordInProdMode() throws Exception {
        String shortPassword = "abc";
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PASSWORD,
                /*credential=*/ shortPassword.getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);

        setExpectedScryptArgument(shortPassword.getBytes());

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PASSWORD);
        verify(mMockScrypt).scrypt(any(), any(),
                eq(KeySyncTask.SCRYPT_PARAM_N), eq(KeySyncTask.SCRYPT_PARAM_R),
                eq(KeySyncTask.SCRYPT_PARAM_P), eq(KeySyncTask.SCRYPT_PARAM_OUTLEN_BYTES));
        KeyDerivationParams keyDerivationParams =
                keyChainSnapshot.getKeyChainProtectionParams().get(0).getKeyDerivationParams();
        assertThat(keyDerivationParams.getAlgorithm()).isEqualTo(
                KeyDerivationParams.ALGORITHM_SCRYPT);
    }

    @Test
    public void run_stillCreatesSnapshotIfNoRecoveryAgentPendingIntentRegistered()
            throws Exception {
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);

        mKeySyncTask.run();

        assertNotNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));
    }

    @Test
    public void run_InTestModeWithWhitelistedCredentials() throws Exception {
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);

        // Enter test mode with allowlisted credentials
        when(mTestOnlyInsecureCertificateHelper.isTestOnlyCertificateAlias(any())).thenReturn(true);
        when(mTestOnlyInsecureCertificateHelper.doesCredentialSupportInsecureMode(anyInt(), any()))
                .thenReturn(true);
        mKeySyncTask.run();

        verify(mTestOnlyInsecureCertificateHelper)
                .getDefaultCertificateAliasIfEmpty(eq(TEST_ROOT_CERT_ALIAS));

        // run allowlist checks
        verify(mTestOnlyInsecureCertificateHelper)
                .doesCredentialSupportInsecureMode(anyInt(), any());
        verify(mTestOnlyInsecureCertificateHelper)
                .keepOnlyWhitelistedInsecureKeys(any());

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertNotNull(keyChainSnapshot); // created snapshot
        List<WrappedApplicationKey> applicationKeys = keyChainSnapshot.getWrappedApplicationKeys();
        assertThat(applicationKeys).hasSize(0); // non allowlisted key is not included
        verify(mMockScrypt, never()).scrypt(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void run_InTestModeWithNonWhitelistedCredentials() throws Exception {
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);

        // Enter test mode with non allowlisted credentials
        when(mTestOnlyInsecureCertificateHelper.isTestOnlyCertificateAlias(any())).thenReturn(true);
        when(mTestOnlyInsecureCertificateHelper.doesCredentialSupportInsecureMode(anyInt(), any()))
                .thenReturn(false);
        mKeySyncTask.run();

        assertNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID)); // not created
        verify(mTestOnlyInsecureCertificateHelper)
                .getDefaultCertificateAliasIfEmpty(eq(TEST_ROOT_CERT_ALIAS));
        verify(mTestOnlyInsecureCertificateHelper)
                .doesCredentialSupportInsecureMode(anyInt(), any());
        verify(mMockScrypt, never()).scrypt(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void run_doesNotFilterCredentialsAndAliasesInProd() throws Exception {
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);

        mKeySyncTask.run();
        assertNotNull(mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID));

        verify(mTestOnlyInsecureCertificateHelper)
                .getDefaultCertificateAliasIfEmpty(eq(TEST_ROOT_CERT_ALIAS));
        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .isTestOnlyCertificateAlias(eq(TEST_ROOT_CERT_ALIAS));

        // no allowlists check
        verify(mTestOnlyInsecureCertificateHelper, never())
                .doesCredentialSupportInsecureMode(anyInt(), any());
        verify(mTestOnlyInsecureCertificateHelper, never())
                .keepOnlyWhitelistedInsecureKeys(any());
    }

    @Test
    public void run_replacesNullActiveRootAliasWithDefaultValue() throws Exception {
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setActiveRootOfTrust(TEST_USER_ID, TEST_RECOVERY_AGENT_UID,
                /*alias=*/ null);

        when(mTestOnlyInsecureCertificateHelper.getDefaultCertificateAliasIfEmpty(null))
                .thenReturn(TEST_ROOT_CERT_ALIAS); // override default.
        mKeySyncTask.run();

        verify(mTestOnlyInsecureCertificateHelper).getDefaultCertificateAliasIfEmpty(null);
    }

    @Test
    public void run_sendsEncryptedKeysIfAvailableToSync_withRawPublicKey() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS,
                TestData.getInsecureCertPathForEndpoint1());

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
        byte[] lockScreenHash = KeySyncTask.hashCredentialsBySaltedSha256(
                keyDerivationParams.getSalt(),
                TEST_CREDENTIAL.getBytes());
        Long counterId = mRecoverableKeyStoreDb.getCounterId(TEST_USER_ID, TEST_RECOVERY_AGENT_UID);
        assertThat(counterId).isNotNull();
        byte[] recoveryKey = decryptThmEncryptedKey(
                lockScreenHash,
                keyChainSnapshot.getEncryptedRecoveryKeyBlob(),
                /*vaultParams=*/ KeySyncUtils.packVaultParams(
                        TestData.getInsecureCertPathForEndpoint1().getCertificates().get(0)
                                .getPublicKey(),
                        counterId,
                        /*maxAttempts=*/ 10,
                        TEST_VAULT_HANDLE));
        List<WrappedApplicationKey> applicationKeys = keyChainSnapshot.getWrappedApplicationKeys();
        assertThat(applicationKeys).hasSize(1);
        assertThat(keyChainSnapshot.getCounterId()).isEqualTo(counterId);
        assertThat(keyChainSnapshot.getMaxAttempts()).isEqualTo(10);
        assertThat(keyChainSnapshot.getTrustedHardwareCertPath())
                .isEqualTo(TestData.getInsecureCertPathForEndpoint1());
        assertThat(keyChainSnapshot.getServerParams()).isEqualTo(TEST_VAULT_HANDLE);
        WrappedApplicationKey keyData = applicationKeys.get(0);
        assertEquals(TEST_APP_KEY_ALIAS, keyData.getAlias());
        assertThat(keyData.getAlias()).isEqualTo(keyData.getAlias());
        byte[] appKey = KeySyncUtils.decryptApplicationKey(
                recoveryKey, keyData.getEncryptedKeyMaterial(), TEST_APP_KEY_METADATA_NULL);
        assertThat(appKey).isEqualTo(applicationKey.getEncoded());
    }

    @Test
    public void run_sendsEncryptedKeysIfAvailableToSync_withCertPath_withNullKeyMetadata()
            throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS,
                TEST_APP_KEY_METADATA_NULL);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        List<WrappedApplicationKey> applicationKeys = keyChainSnapshot.getWrappedApplicationKeys();
        assertThat(applicationKeys).hasSize(1);
        WrappedApplicationKey keyData = applicationKeys.get(0);
        assertThat(keyData.getAlias()).isEqualTo(TEST_APP_KEY_ALIAS);
        assertThat(keyData.getMetadata()).isEqualTo(TEST_APP_KEY_METADATA_NULL);
        assertThat(keyChainSnapshot.getTrustedHardwareCertPath())
                .isEqualTo(TestData.CERT_PATH_1);
    }

    @Test
    public void run_sendsEncryptedKeysIfAvailableToSync_withCertPath_withNonNullKeyMetadata()
            throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setServerParams(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_VAULT_HANDLE);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS,
                TEST_APP_KEY_METADATA_NON_NULL);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        List<WrappedApplicationKey> applicationKeys = keyChainSnapshot.getWrappedApplicationKeys();
        assertThat(applicationKeys).hasSize(1);
        WrappedApplicationKey keyData = applicationKeys.get(0);
        assertThat(keyData.getAlias()).isEqualTo(TEST_APP_KEY_ALIAS);
        assertThat(keyData.getMetadata()).isEqualTo(TEST_APP_KEY_METADATA_NON_NULL);
        assertThat(keyChainSnapshot.getTrustedHardwareCertPath())
                .isEqualTo(TestData.CERT_PATH_1);
    }

    @Test
    public void run_setsCorrectSnapshotVersion() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
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
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
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
        String password = "password";
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PASSWORD,
                password.getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);

        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        setExpectedScryptArgument(password.getBytes());

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PASSWORD);
        verify(mMockScrypt).scrypt(any(), any(),
                eq(KeySyncTask.SCRYPT_PARAM_N), eq(KeySyncTask.SCRYPT_PARAM_R),
                eq(KeySyncTask.SCRYPT_PARAM_P), eq(KeySyncTask.SCRYPT_PARAM_OUTLEN_BYTES));
    }

    @Test
    public void run_setsCorrectTypeForPin() throws Exception {
        String pin = "1234";
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PIN,
                /*credential=*/ pin.getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);

        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        setExpectedScryptArgument(pin.getBytes());

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        // Password with only digits is changed to pin.
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PIN);
        verify(mMockScrypt).scrypt(any(), any(),
                eq(KeySyncTask.SCRYPT_PARAM_N), eq(KeySyncTask.SCRYPT_PARAM_R),
                eq(KeySyncTask.SCRYPT_PARAM_P), eq(KeySyncTask.SCRYPT_PARAM_OUTLEN_BYTES));
    }

    @Test
    public void run_setsCorrectTypeForPattern() throws Exception {
        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                mRecoverySnapshotStorage,
                mSnapshotListenersStorage,
                TEST_USER_ID,
                CREDENTIAL_TYPE_PATTERN,
                "12345".getBytes(),
                /*credentialUpdated=*/ false,
                mPlatformKeyManager,
                mTestOnlyInsecureCertificateHelper,
                mMockScrypt);

        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

        mKeySyncTask.run();

        KeyChainSnapshot keyChainSnapshot = mRecoverySnapshotStorage.get(TEST_RECOVERY_AGENT_UID);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams()).hasSize(1);
        assertThat(keyChainSnapshot.getKeyChainProtectionParams().get(0).getLockScreenUiFormat()).
                isEqualTo(UI_FORMAT_PATTERN);
        verify(mMockScrypt, never()).scrypt(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void run_sendsEncryptedKeysWithTwoRegisteredAgents() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
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

        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
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
    public void run_notifiesNonregisteredAgent() throws Exception {
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        mRecoverableKeyStoreDb.setRecoveryServiceCertPath(
                TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_ROOT_CERT_ALIAS, TestData.CERT_PATH_1);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID)).thenReturn(true);
        when(mSnapshotListenersStorage.hasListener(TEST_RECOVERY_AGENT_UID2)).thenReturn(false);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);
        addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID2, TEST_APP_KEY_ALIAS);
        mKeySyncTask.run();

        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID);
        verify(mSnapshotListenersStorage).recoverySnapshotAvailable(TEST_RECOVERY_AGENT_UID2);
    }

    @Test
    public void run_unlock_keepsRemoteLskfVerificationCounter() throws Exception {
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(TEST_USER_ID, 5);
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(TEST_USER_ID_2, 4);
        mKeySyncTask = new KeySyncTask(
          mRecoverableKeyStoreDb,
          mRecoverySnapshotStorage,
          mSnapshotListenersStorage,
          TEST_USER_ID,
          CREDENTIAL_TYPE_PIN,
          "12345".getBytes(),
          /*credentialUpdated=*/ false,
          mPlatformKeyManager,
          mTestOnlyInsecureCertificateHelper,
          mMockScrypt);
        mKeySyncTask.run();

        assertThat(mRecoverableKeyStoreDb.getBadRemoteGuessCounter(TEST_USER_ID)).isEqualTo(5);
        assertThat(mRecoverableKeyStoreDb.getBadRemoteGuessCounter(TEST_USER_ID_2)).isEqualTo(4);
    }

    @Test
    public void run_secretChange_resetsRemoteLskfVerificationCounter() throws Exception {
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(TEST_USER_ID, 5);
        mRecoverableKeyStoreDb.setBadRemoteGuessCounter(TEST_USER_ID_2, 4);
        mKeySyncTask = new KeySyncTask(
          mRecoverableKeyStoreDb,
          mRecoverySnapshotStorage,
          mSnapshotListenersStorage,
          TEST_USER_ID,
          CREDENTIAL_TYPE_PIN,
          "12345".getBytes(),
          /*credentialUpdated=*/ true,
          mPlatformKeyManager,
          mTestOnlyInsecureCertificateHelper,
          mMockScrypt);
        mKeySyncTask.run();

        assertThat(mRecoverableKeyStoreDb.getBadRemoteGuessCounter(TEST_USER_ID)).isEqualTo(0);
        assertThat(mRecoverableKeyStoreDb.getBadRemoteGuessCounter(TEST_USER_ID_2)).isEqualTo(4);
    }

    @Test
    public void run_customLockScreen_RecoveryStatusFailure() throws Exception {
      mKeySyncTask = new KeySyncTask(
          mRecoverableKeyStoreDb,
          mRecoverySnapshotStorage,
          mSnapshotListenersStorage,
          TEST_USER_ID,
          /*credentialType=*/ 5, // Some invalid credential type value
          "12345".getBytes(),
          /*credentialUpdated=*/ false,
          mPlatformKeyManager,
          mTestOnlyInsecureCertificateHelper,
          mMockScrypt);

      addApplicationKey(TEST_USER_ID, TEST_RECOVERY_AGENT_UID, TEST_APP_KEY_ALIAS);

      int status =
          mRecoverableKeyStoreDb
              .getStatusForAllKeys(TEST_RECOVERY_AGENT_UID)
              .get(TEST_APP_KEY_ALIAS);
      assertEquals(RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS, status);

      mKeySyncTask.run();

      status = mRecoverableKeyStoreDb
          .getStatusForAllKeys(TEST_RECOVERY_AGENT_UID)
          .get(TEST_APP_KEY_ALIAS);
      assertEquals(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE, status);
      verify(mMockScrypt, never()).scrypt(any(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    private SecretKey addApplicationKey(int userId, int recoveryAgentUid, String alias)
            throws Exception{
        return addApplicationKey(userId, recoveryAgentUid, alias, TEST_APP_KEY_METADATA_NULL);
    }

    private SecretKey addApplicationKey(int userId, int recoveryAgentUid, String alias,
            byte[] metadata) throws Exception {
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
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey, metadata));
        return applicationKey;
    }

    private byte[] decryptThmEncryptedKey(
            byte[] lockScreenHash, byte[] encryptedKey, byte[] vaultParams) throws Exception {
        byte[] locallyEncryptedKey = SecureBox.decrypt(
                TestData.getInsecurePrivateKeyForEndpoint1(),
                /*sharedSecret=*/ KeySyncUtils.calculateThmKfHash(lockScreenHash),
                /*header=*/ ArrayUtils.concat(THM_ENCRYPTED_RECOVERY_KEY_HEADER, vaultParams),
                encryptedKey
        );
        return KeySyncUtils.decryptRecoveryKey(lockScreenHash, locallyEncryptedKey);
    }

    private SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }

    private SecretKey generateAndroidKeyStoreKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KEY_ALGORITHM,
                ANDROID_KEY_STORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return keyGenerator.generateKey();
    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new Random().nextBytes(bytes);
        return bytes;
    }

    private void setExpectedScryptArgument(byte[] credentials) {
        doAnswer(invocation -> {
            assertThat((byte[]) invocation.getArguments()[0]).isEqualTo(credentials);
            return invocation.callRealMethod();
        }).when(mMockScrypt).scrypt(any(), any(),
                eq(KeySyncTask.SCRYPT_PARAM_N), eq(KeySyncTask.SCRYPT_PARAM_R),
                eq(KeySyncTask.SCRYPT_PARAM_P), eq(KeySyncTask.SCRYPT_PARAM_OUTLEN_BYTES));

    }
}
