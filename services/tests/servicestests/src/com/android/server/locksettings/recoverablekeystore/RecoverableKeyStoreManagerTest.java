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
import static android.security.keystore.recovery.RecoveryController.ERROR_BAD_CERTIFICATE_FORMAT;
import static android.security.keystore.recovery.RecoveryController.ERROR_DOWNGRADE_CERTIFICATE;
import static android.security.keystore.recovery.RecoveryController.ERROR_INVALID_CERTIFICATE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.RecoveryCertPath;
import android.security.keystore.recovery.TrustedRootCertificates;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.locksettings.recoverablekeystore.storage.ApplicationKeyStorage;
import com.android.server.locksettings.recoverablekeystore.storage.CleanupManager;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySessionStorage;
import com.android.server.locksettings.recoverablekeystore.storage.RecoverySnapshotStorage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverableKeyStoreManagerTest {
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";

    private static final String ROOT_CERTIFICATE_ALIAS = "";
    private static final String DEFAULT_ROOT_CERT_ALIAS =
            TrustedRootCertificates.GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS;
    private static final String INSECURE_CERTIFICATE_ALIAS =
            TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS;
    private static final String TEST_SESSION_ID = "karlin";
    private static final byte[] TEST_PUBLIC_KEY = TestData.CERT_1_PUBLIC_KEY.getEncoded();
    private static final byte[] TEST_SALT = getUtf8Bytes("salt");
    private static final byte[] TEST_SECRET = getUtf8Bytes("password1234");
    private static final byte[] TEST_VAULT_CHALLENGE = getUtf8Bytes("vault_challenge");
    private static final byte[] TEST_VAULT_PARAMS = new byte[] {
        // backend_key
        (byte) 0x04, (byte) 0x8e, (byte) 0x0c, (byte) 0x11, (byte) 0x4a, (byte) 0x79, (byte) 0x20,
        (byte) 0x7c, (byte) 0x00, (byte) 0x4c, (byte) 0xd7, (byte) 0xe9, (byte) 0x06, (byte) 0xe2,
        (byte) 0x58, (byte) 0x21, (byte) 0x45, (byte) 0xfa, (byte) 0x24, (byte) 0xcb, (byte) 0x07,
        (byte) 0x66, (byte) 0xde, (byte) 0xfd, (byte) 0xf1, (byte) 0x83, (byte) 0xb4, (byte) 0x26,
        (byte) 0x55, (byte) 0x98, (byte) 0xcb, (byte) 0xa9, (byte) 0xd5, (byte) 0x55, (byte) 0xad,
        (byte) 0x65, (byte) 0xc5, (byte) 0xff, (byte) 0x5c, (byte) 0xfb, (byte) 0x1c, (byte) 0x4e,
        (byte) 0x34, (byte) 0x98, (byte) 0x7e, (byte) 0x4f, (byte) 0x96, (byte) 0xa2, (byte) 0xa3,
        (byte) 0x7e, (byte) 0xf4, (byte) 0x46, (byte) 0x52, (byte) 0x04, (byte) 0xba, (byte) 0x2a,
        (byte) 0xb9, (byte) 0x47, (byte) 0xbb, (byte) 0xc2, (byte) 0x1e, (byte) 0xdd, (byte) 0x15,
        (byte) 0x1a, (byte) 0xc0,
        // counter_id
        (byte) 0x31, (byte) 0x32, (byte) 0x33, (byte) 0x34, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        (byte) 0x00,
        // device_parameter
        (byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        (byte) 0x0,
        // max_attempts
        (byte) 0x0a, (byte) 0x00, (byte) 0x00, (byte) 0x00};
    private static final int TEST_GENERATION_ID = 2;
    private static final int TEST_USER_ID = 10009;
    private static final int KEY_CLAIMANT_LENGTH_BYTES = 16;
    private static final byte[] RECOVERY_RESPONSE_HEADER =
            "V1 reencrypted_recovery_key".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_ALIAS = "nick";
    private static final String TEST_ALIAS2 = "bob";
    private static final int APPLICATION_KEY_SIZE_BYTES = 32;
    private static final int GENERATION_ID = 1;
    private static final byte[] NONCE = getUtf8Bytes("nonce");
    private static final byte[] KEY_MATERIAL = getUtf8Bytes("keymaterial");
    private static final byte[] KEY_METADATA_NULL = null;
    private static final byte[] KEY_METADATA_NON_NULL = getUtf8Bytes("keymetametadata");
    private static final String KEY_ALGORITHM = "AES";
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String WRAPPING_KEY_ALIAS = "RecoverableKeyStoreManagerTest/WrappingKey";
    private static final String TEST_DEFAULT_ROOT_CERT_ALIAS = "";
    private static final KeyChainProtectionParams TEST_PROTECTION_PARAMS =
    new KeyChainProtectionParams.Builder()
            .setUserSecretType(TYPE_LOCKSCREEN)
            .setLockScreenUiFormat(UI_FORMAT_PASSWORD)
            .setKeyDerivationParams(KeyDerivationParams.createSha256Params(TEST_SALT))
            .setSecret(TEST_SECRET)
            .build();

    @Mock private Context mMockContext;
    @Mock private RecoverySnapshotListenersStorage mMockListenersStorage;
    @Mock private KeyguardManager mKeyguardManager;
    @Mock private PlatformKeyManager mPlatformKeyManager;
    @Mock private ApplicationKeyStorage mApplicationKeyStorage;
    @Mock private CleanupManager mCleanupManager;
    @Mock private ScheduledExecutorService mExecutorService;
    @Spy private TestOnlyInsecureCertificateHelper mTestOnlyInsecureCertificateHelper;

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;
    private RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    private RecoverySessionStorage mRecoverySessionStorage;
    private RecoverySnapshotStorage mRecoverySnapshotStorage;
    private PlatformEncryptionKey mPlatformEncryptionKey;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);

        mRecoverySessionStorage = new RecoverySessionStorage();

        when(mMockContext.getSystemService(anyString())).thenReturn(mKeyguardManager);
        when(mMockContext.getSystemServiceName(any())).thenReturn("test");
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mKeyguardManager.isDeviceSecure(TEST_USER_ID)).thenReturn(true);

        mPlatformEncryptionKey =
                new PlatformEncryptionKey(TEST_GENERATION_ID, generateAndroidKeyStoreKey());
        when(mPlatformKeyManager.getEncryptKey(anyInt())).thenReturn(mPlatformEncryptionKey);

        mRecoverableKeyStoreManager = new RecoverableKeyStoreManager(
                mMockContext,
                mRecoverableKeyStoreDb,
                mRecoverySessionStorage,
                mExecutorService,
                mRecoverySnapshotStorage,
                mMockListenersStorage,
                mPlatformKeyManager,
                mApplicationKeyStorage,
                mTestOnlyInsecureCertificateHelper,
                mCleanupManager);
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void importKey_storesTheKey() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        byte[] keyMaterial = randomBytes(APPLICATION_KEY_SIZE_BYTES);

        mRecoverableKeyStoreManager.importKey(TEST_ALIAS, keyMaterial);

        assertThat(mRecoverableKeyStoreDb.getKey(uid, TEST_ALIAS)).isNotNull();
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void importKey_throwsIfInvalidLength() throws Exception {
        byte[] keyMaterial = randomBytes(APPLICATION_KEY_SIZE_BYTES - 1);
        try {
            mRecoverableKeyStoreManager.importKey(TEST_ALIAS, keyMaterial);
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).contains("not contain 256 bits");
        }
    }

    @Test
    public void importKey_throwsIfNullKey() throws Exception {
        try {
            mRecoverableKeyStoreManager.importKey(TEST_ALIAS, /*keyBytes=*/ null);
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("is null");
        }
    }

    @Test
    public void importKeyWithMetadata_nullMetadata_storesTheKey() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        byte[] keyMaterial = randomBytes(APPLICATION_KEY_SIZE_BYTES);

        mRecoverableKeyStoreManager.importKeyWithMetadata(
                TEST_ALIAS, keyMaterial, KEY_METADATA_NULL);

        assertThat(mRecoverableKeyStoreDb.getKey(uid, TEST_ALIAS)).isNotNull();
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void importKeyWithMetadata_nonNullMetadata_storesTheKey() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        byte[] keyMaterial = randomBytes(APPLICATION_KEY_SIZE_BYTES);

        mRecoverableKeyStoreManager.importKeyWithMetadata(
                TEST_ALIAS, keyMaterial, KEY_METADATA_NON_NULL);

        assertThat(mRecoverableKeyStoreDb.getKey(uid, TEST_ALIAS)).isNotNull();
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void importKeyWithMetadata_throwsIfInvalidLength() throws Exception {
        byte[] keyMaterial = randomBytes(APPLICATION_KEY_SIZE_BYTES - 1);
        try {
            mRecoverableKeyStoreManager.importKeyWithMetadata(
                    TEST_ALIAS, keyMaterial, KEY_METADATA_NON_NULL);
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).contains("not contain 256 bits");
        }
    }

    @Test
    public void importKeyWithMetadata_throwsIfNullKey() throws Exception {
        try {
            mRecoverableKeyStoreManager.importKeyWithMetadata(
                    TEST_ALIAS, /*keyBytes=*/ null, KEY_METADATA_NON_NULL);
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("is null");
        }
    }

    @Test
    public void generateKeyWithMetadata_nullMetadata_storesTheKey() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        mRecoverableKeyStoreManager.generateKeyWithMetadata(TEST_ALIAS, KEY_METADATA_NULL);

        assertThat(mRecoverableKeyStoreDb.getKey(uid, TEST_ALIAS)).isNotNull();
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void generateKeyWithMetadata_nonNullMetadata_storesTheKey() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        mRecoverableKeyStoreManager.generateKeyWithMetadata(TEST_ALIAS, KEY_METADATA_NON_NULL);

        assertThat(mRecoverableKeyStoreDb.getKey(uid, TEST_ALIAS)).isNotNull();
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void removeKey_removesAKey() throws Exception {
        int uid = Binder.getCallingUid();
        mRecoverableKeyStoreManager.generateKey(TEST_ALIAS);

        mRecoverableKeyStoreManager.removeKey(TEST_ALIAS);

        assertThat(mRecoverableKeyStoreDb.getKey(uid, TEST_ALIAS)).isNull();
    }

    @Test
    public void removeKey_updatesShouldCreateSnapshot() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mRecoverableKeyStoreManager.generateKey(TEST_ALIAS);
        // Pretend that key was synced
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.removeKey(TEST_ALIAS);

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void removeKey_failureDoesNotUpdateShouldCreateSnapshot() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);
        // Key did not exist
        mRecoverableKeyStoreManager.removeKey(TEST_ALIAS);

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void initRecoveryService_succeedsWithCertFile() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getDefaultCertificateAliasIfEmpty(ROOT_CERTIFICATE_ALIAS);

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                DEFAULT_ROOT_CERT_ALIAS)).isEqualTo(TestData.CERT_PATH_1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                DEFAULT_ROOT_CERT_ALIAS)).isEqualTo(certSerial);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isNull();
    }

    @Test
    public void initRecoveryService_updatesShouldCreatesnapshotOnCertUpdate() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial + 1));

        // Since there were no recoverable keys, new snapshot will not be created.
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial + 2));

        // Since there were a recoverable key, new serial number triggers snapshot creation
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void initRecoveryService_triesToFilterRootAlias() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getDefaultCertificateAliasIfEmpty(eq(ROOT_CERTIFICATE_ALIAS));

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getRootCertificate(eq(DEFAULT_ROOT_CERT_ALIAS));

        String activeRootAlias = mRecoverableKeyStoreDb.getActiveRootOfTrust(userId, uid);
        assertThat(activeRootAlias).isEqualTo(DEFAULT_ROOT_CERT_ALIAS);

    }

    @Test
    public void initRecoveryService_usesProdCertificateForEmptyRootAlias() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryService(/*rootCertificateAlias=*/ "",
                TestData.getCertXmlWithSerial(certSerial));

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getDefaultCertificateAliasIfEmpty(eq(""));

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getRootCertificate(eq(DEFAULT_ROOT_CERT_ALIAS));

        String activeRootAlias = mRecoverableKeyStoreDb.getActiveRootOfTrust(userId, uid);
        assertThat(activeRootAlias).isEqualTo(DEFAULT_ROOT_CERT_ALIAS);
    }

    @Test
    public void initRecoveryService_usesProdCertificateForNullRootAlias() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryService(/*rootCertificateAlias=*/ null,
                TestData.getCertXmlWithSerial(certSerial));

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getDefaultCertificateAliasIfEmpty(null);

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getRootCertificate(eq(DEFAULT_ROOT_CERT_ALIAS));

        String activeRootAlias = mRecoverableKeyStoreDb.getActiveRootOfTrust(userId, uid);
        assertThat(activeRootAlias).isEqualTo(DEFAULT_ROOT_CERT_ALIAS);
    }

    @Test
    public void initRecoveryService_regeneratesCounterId() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;

        Long counterId0 = mRecoverableKeyStoreDb.getCounterId(userId, uid);
        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));
        Long counterId1 = mRecoverableKeyStoreDb.getCounterId(userId, uid);
        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial + 1));
        Long counterId2 = mRecoverableKeyStoreDb.getCounterId(userId, uid);

        assertThat(!counterId1.equals(counterId0) || !counterId2.equals(counterId1)).isTrue();
    }

    @Test
    public void initRecoveryService_throwsIfInvalidCert() throws Exception {
        byte[] modifiedCertXml = TestData.getCertXml();
        modifiedCertXml[modifiedCertXml.length - 50] ^= 1;  // Flip a bit in the certificate
        try {
            mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                    modifiedCertXml);
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(ERROR_INVALID_CERTIFICATE);
        }
    }

    @Test
    public void initRecoveryService_updatesWithLargerSerial() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));
        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial + 1));

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                DEFAULT_ROOT_CERT_ALIAS)).isEqualTo(certSerial + 1);
        // There were no keys.
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void initRecoveryService_throwsExceptionOnSmallerSerial() throws Exception {
        long certSerial = 1000L;

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));
        try {
            mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                    TestData.getCertXmlWithSerial(certSerial - 1));
            fail();
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(ERROR_DOWNGRADE_CERTIFICATE);
        }
    }

    @Ignore("Causing breakages so ignoring to resolve, b/231667368")
    @Test
    public void initRecoveryService_alwaysUpdatesCertsWhenTestRootCertIsUsed() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        int certSerial = 3333;

        String testRootCertAlias = TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS;

        mRecoverableKeyStoreManager.initRecoveryService(testRootCertAlias,
                TestData.getInsecureCertXmlBytesWithEndpoint1(certSerial));
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                testRootCertAlias)).isEqualTo(certSerial);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                testRootCertAlias)).isEqualTo(TestData.getInsecureCertPathForEndpoint1());

        mRecoverableKeyStoreManager.initRecoveryService(testRootCertAlias,
                TestData.getInsecureCertXmlBytesWithEndpoint2(certSerial - 1));
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                testRootCertAlias)).isEqualTo(certSerial - 1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                testRootCertAlias)).isEqualTo(TestData.getInsecureCertPathForEndpoint2());
    }

    @Ignore("Causing breakages so ignoring to resolve, b/231667368")
    @Test
    public void initRecoveryService_updatesCertsIndependentlyForDifferentRoots() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(1111L));
        mRecoverableKeyStoreManager.initRecoveryService(
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS,
                TestData.getInsecureCertXmlBytesWithEndpoint1(2222));

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                ROOT_CERTIFICATE_ALIAS)).isEqualTo(1111L);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS)).isEqualTo(2222L);

        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                ROOT_CERTIFICATE_ALIAS)).isEqualTo(TestData.CERT_PATH_1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS)).isEqualTo(
                        TestData.getInsecureCertPathForEndpoint1());
    }

    @Test
    public void initRecoveryService_ignoresTheSameSerial() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long certSerial = 1000L;

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.initRecoveryService(ROOT_CERTIFICATE_ALIAS,
                TestData.getCertXmlWithSerial(certSerial));

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void initRecoveryService_throwsIfRawPublicKey() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        try {
            mRecoverableKeyStoreManager
                    .initRecoveryService(ROOT_CERTIFICATE_ALIAS, TEST_PUBLIC_KEY);
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(ERROR_BAD_CERTIFICATE_FORMAT);
        }

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                DEFAULT_ROOT_CERT_ALIAS)).isNull();
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertSerial(userId, uid,
                DEFAULT_ROOT_CERT_ALIAS)).isNull();
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isNull();
    }

    @Test
    public void initRecoveryService_throwsIfUnknownRootCertAlias() throws Exception {
        try {
            mRecoverableKeyStoreManager.initRecoveryService(
                    "unknown-root-cert-alias", TestData.getCertXml());
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(ERROR_INVALID_CERTIFICATE);
        }
    }

    @Test
    public void initRecoveryServiceWithSigFile_succeeds() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                ROOT_CERTIFICATE_ALIAS, TestData.getCertXml(), TestData.getSigXml());

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
        assertThat(mRecoverableKeyStoreDb.getRecoveryServiceCertPath(userId, uid,
                DEFAULT_ROOT_CERT_ALIAS)).isEqualTo(TestData.CERT_PATH_1);
        assertThat(mRecoverableKeyStoreDb.getRecoveryServicePublicKey(userId, uid)).isNull();
    }

    @Test
    public void initRecoveryServiceWithSigFile_usesProdCertificateForNullRootAlias()
            throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);

        mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                /*rootCertificateAlias=*/null, TestData.getCertXml(), TestData.getSigXml());

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getDefaultCertificateAliasIfEmpty(null);

        verify(mTestOnlyInsecureCertificateHelper, atLeast(1))
                .getRootCertificate(eq(DEFAULT_ROOT_CERT_ALIAS));
    }

    @Test
    public void initRecoveryServiceWithSigFile_throwsIfNullCertFile() throws Exception {
        try {
            mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                    ROOT_CERTIFICATE_ALIAS, /*recoveryServiceCertFile=*/ null,
                    TestData.getSigXml());
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("is null");
        }
    }

    @Test
    public void initRecoveryServiceWithSigFile_throwsIfNullSigFile() throws Exception {
        try {
            mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                    ROOT_CERTIFICATE_ALIAS, TestData.getCertXml(),
                    /*recoveryServiceSigFile=*/ null);
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("is null");
        }
    }

    @Test
    public void initRecoveryServiceWithSigFile_throwsIfWrongSigFileFormat() throws Exception {
        try {
            mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                    ROOT_CERTIFICATE_ALIAS, TestData.getCertXml(),
                    getUtf8Bytes("wrong-sig-file-format"));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(ERROR_BAD_CERTIFICATE_FORMAT);
        }
    }

    @Test
    public void initRecoveryServiceWithSigFile_throwsIfTestAliasUsedWithProdCert()
            throws Exception {
        try {
        mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                INSECURE_CERTIFICATE_ALIAS, TestData.getCertXml(), TestData.getSigXml());
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(ERROR_INVALID_CERTIFICATE);
        }
    }

    @Test
    public void initRecoveryServiceWithSigFile_throwsIfInvalidFileSignature() throws Exception {
        byte[] modifiedCertXml = TestData.getCertXml();
        modifiedCertXml[modifiedCertXml.length - 1] = 0;  // Change the last new line char to a zero
        try {
            mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(
                    ROOT_CERTIFICATE_ALIAS, modifiedCertXml, TestData.getSigXml());
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).contains("is invalid");
        }
    }

    @Test
    public void startRecoverySession_checksPermissionFirst() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        verify(mMockContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq(Manifest.permission.RECOVER_KEYSTORE), any());
    }

    @Test
    public void startRecoverySessionWithCertPath_storesTheSessionInfo() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                TEST_SESSION_ID,
                TEST_DEFAULT_ROOT_CERT_ALIAS,
                RecoveryCertPath.createRecoveryCertPath(TestData.CERT_PATH_1),
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        assertEquals(1, mRecoverySessionStorage.size());
        RecoverySessionStorage.Entry entry =
                mRecoverySessionStorage.get(Binder.getCallingUid(), TEST_SESSION_ID);
        assertArrayEquals(TEST_SECRET, entry.getLskfHash());
        assertEquals(KEY_CLAIMANT_LENGTH_BYTES, entry.getKeyClaimant().length);
    }

    @Test
    public void startRecoverySessionWithCertPath_checksPermissionFirst() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                TEST_SESSION_ID,
                TEST_DEFAULT_ROOT_CERT_ALIAS,
                RecoveryCertPath.createRecoveryCertPath(TestData.CERT_PATH_1),
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        verify(mMockContext, times(2))
                .enforceCallingOrSelfPermission(
                        eq(Manifest.permission.RECOVER_KEYSTORE), any());
    }

    @Test
    public void startRecoverySession_storesTheSessionInfo() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        assertEquals(1, mRecoverySessionStorage.size());
        RecoverySessionStorage.Entry entry =
                mRecoverySessionStorage.get(Binder.getCallingUid(), TEST_SESSION_ID);
        assertArrayEquals(TEST_SECRET, entry.getLskfHash());
        assertEquals(KEY_CLAIMANT_LENGTH_BYTES, entry.getKeyClaimant().length);
    }

    @Test
    public void closeSession_closesASession() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        mRecoverableKeyStoreManager.closeSession(TEST_SESSION_ID);

        assertEquals(0, mRecoverySessionStorage.size());
    }

    @Test
    public void closeSession_doesNotCloseUnrelatedSessions() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        mRecoverableKeyStoreManager.closeSession("some random session");

        assertEquals(1, mRecoverySessionStorage.size());
    }

    @Test
    public void closeSession_throwsIfNullSession() throws Exception {
        try {
            mRecoverableKeyStoreManager.closeSession(/*sessionId=*/ null);
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("invalid");
        }
    }

    @Test
    public void startRecoverySession_throwsIfBadNumberOfSecrets() throws Exception {
        try {
            mRecoverableKeyStoreManager.startRecoverySession(
                    TEST_SESSION_ID,
                    TEST_PUBLIC_KEY,
                    TEST_VAULT_PARAMS,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of());
            fail("should have thrown");
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage()).startsWith(
                    "Only a single KeyChainProtectionParams is supported");
        }
    }

    @Test
    public void startRecoverySession_throwsIfPublicKeysMismatch() throws Exception {
        byte[] vaultParams = TEST_VAULT_PARAMS.clone();
        vaultParams[1] ^= (byte) 1;  // Flip 1 bit

        try {
            mRecoverableKeyStoreManager.startRecoverySession(
                    TEST_SESSION_ID,
                    TEST_PUBLIC_KEY,
                    vaultParams,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of(TEST_PROTECTION_PARAMS));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).contains("do not match");
        }
    }

    @Test
    public void startRecoverySessionWithCertPath_throwsIfBadNumberOfSecrets() throws Exception {
        try {
            mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                    TEST_SESSION_ID,
                    TEST_DEFAULT_ROOT_CERT_ALIAS,
                    RecoveryCertPath.createRecoveryCertPath(TestData.CERT_PATH_1),
                    TEST_VAULT_PARAMS,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of());
            fail("should have thrown");
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage()).startsWith(
                    "Only a single KeyChainProtectionParams is supported");
        }
    }

    @Test
    public void startRecoverySessionWithCertPath_throwsIfPublicKeysMismatch() throws Exception {
        byte[] vaultParams = TEST_VAULT_PARAMS.clone();
        vaultParams[1] ^= (byte) 1;  // Flip 1 bit
        try {
            mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                    TEST_SESSION_ID,
                    TEST_DEFAULT_ROOT_CERT_ALIAS,
                    RecoveryCertPath.createRecoveryCertPath(TestData.CERT_PATH_1),
                    vaultParams,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of(TEST_PROTECTION_PARAMS));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).contains("do not match");
        }
    }

    @Test
    public void startRecoverySessionWithCertPath_throwsIfEmptyCertPath() throws Exception {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        CertPath emptyCertPath = certFactory.generateCertPath(new ArrayList<X509Certificate>());
        try {
            mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                    TEST_SESSION_ID,
                    TEST_DEFAULT_ROOT_CERT_ALIAS,
                    RecoveryCertPath.createRecoveryCertPath(emptyCertPath),
                    TEST_VAULT_PARAMS,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of(TEST_PROTECTION_PARAMS));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).contains("empty");
        }
    }

    @Test
    public void startRecoverySessionWithCertPath_throwsIfInvalidCertPath() throws Exception {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        CertPath shortCertPath = certFactory.generateCertPath(
                TestData.CERT_PATH_1.getCertificates()
                        .subList(0, TestData.CERT_PATH_1.getCertificates().size() - 1));
        try {
            mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                    TEST_SESSION_ID,
                    TEST_DEFAULT_ROOT_CERT_ALIAS,
                    RecoveryCertPath.createRecoveryCertPath(shortCertPath),
                    TEST_VAULT_PARAMS,
                    TEST_VAULT_CHALLENGE,
                    ImmutableList.of(TEST_PROTECTION_PARAMS));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            // expected
        }
    }

    @Test
    public void recoverKeyChainSnapshot_throwsIfNoSessionIsPresent() throws Exception {
        try {
            WrappedApplicationKey applicationKey = new WrappedApplicationKey.Builder()
                .setAlias(TEST_ALIAS)
                .setEncryptedKeyMaterial(randomBytes(32))
                .build();
            mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                    TEST_SESSION_ID,
                    /*recoveryKeyBlob=*/ randomBytes(32),
                    /*applicationKeys=*/ ImmutableList.of(applicationKey));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            // expected
        }
    }

    @Test
    public void recoverKeyChainSnapshot_throwsIfRecoveryClaimCannotBeDecrypted() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));

        try {
            mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                    TEST_SESSION_ID,
                    /*encryptedRecoveryKey=*/ randomBytes(60),
                    /*applicationKeys=*/ ImmutableList.of());
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).startsWith("Failed to decrypt recovery key");
        }
    }

    @Test
    public void recoverKeyChainSnapshot_throwsIfFailedToDecryptAllApplicationKeys()
            throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));
        byte[] keyClaimant = mRecoverySessionStorage.get(Binder.getCallingUid(), TEST_SESSION_ID)
                .getKeyClaimant();
        SecretKey recoveryKey = randomRecoveryKey();
        byte[] encryptedClaimResponse = encryptClaimResponse(
                keyClaimant, TEST_SECRET, TEST_VAULT_PARAMS, recoveryKey);
        WrappedApplicationKey badApplicationKey = new WrappedApplicationKey.Builder()
                .setAlias(TEST_ALIAS)
                .setEncryptedKeyMaterial(
                            encryptedApplicationKey(randomRecoveryKey(), randomBytes(32)))
                .build();
        try {
            mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                    TEST_SESSION_ID,
                    /*encryptedRecoveryKey=*/ encryptedClaimResponse,
                    /*applicationKeys=*/ ImmutableList.of(badApplicationKey));
            fail("should have thrown");
        } catch (ServiceSpecificException e) {
            assertThat(e.getMessage()).startsWith("Failed to recover any of the application keys");
        }
    }

    @Test
    public void recoverKeyChainSnapshot_doesNotThrowIfNoApplicationKeysToBeDecrypted()
            throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));
        byte[] keyClaimant = mRecoverySessionStorage.get(Binder.getCallingUid(), TEST_SESSION_ID)
                .getKeyClaimant();
        SecretKey recoveryKey = randomRecoveryKey();
        byte[] encryptedClaimResponse = encryptClaimResponse(
                keyClaimant, TEST_SECRET, TEST_VAULT_PARAMS, recoveryKey);

        mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                TEST_SESSION_ID,
                /*encryptedRecoveryKey=*/ encryptedClaimResponse,
                /*applicationKeys=*/ ImmutableList.of());
    }

    @Test
    public void recoverKeyChainSnapshot_returnsDecryptedKeys() throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));
        byte[] keyClaimant = mRecoverySessionStorage.get(Binder.getCallingUid(), TEST_SESSION_ID)
                .getKeyClaimant();
        SecretKey recoveryKey = randomRecoveryKey();
        byte[] encryptedClaimResponse = encryptClaimResponse(
                keyClaimant, TEST_SECRET, TEST_VAULT_PARAMS, recoveryKey);
        byte[] applicationKeyBytes = randomBytes(32);
        WrappedApplicationKey applicationKey = new WrappedApplicationKey.Builder()
                    .setAlias(TEST_ALIAS)
                    .setEncryptedKeyMaterial(
                            encryptedApplicationKey(recoveryKey, applicationKeyBytes))
                    .build();

        Map<String, String> recoveredKeys = mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                TEST_SESSION_ID,
                encryptedClaimResponse,
                ImmutableList.of(applicationKey));

        assertThat(recoveredKeys).hasSize(1);
        assertThat(recoveredKeys).containsKey(TEST_ALIAS);
    }

    @Test
    public void recoverKeyChainSnapshot_worksOnOtherApplicationKeysIfOneDecryptionFails()
            throws Exception {
        mRecoverableKeyStoreManager.startRecoverySession(
                TEST_SESSION_ID,
                TEST_PUBLIC_KEY,
                TEST_VAULT_PARAMS,
                TEST_VAULT_CHALLENGE,
                ImmutableList.of(TEST_PROTECTION_PARAMS));
        byte[] keyClaimant = mRecoverySessionStorage.get(Binder.getCallingUid(), TEST_SESSION_ID)
                .getKeyClaimant();
        SecretKey recoveryKey = randomRecoveryKey();
        byte[] encryptedClaimResponse = encryptClaimResponse(
                keyClaimant, TEST_SECRET, TEST_VAULT_PARAMS, recoveryKey);

        byte[] applicationKeyBytes1 = randomBytes(32);
        byte[] applicationKeyBytes2 = randomBytes(32);
        WrappedApplicationKey applicationKey1 = new WrappedApplicationKey.Builder()
                    .setAlias(TEST_ALIAS)
                     // Use a different recovery key here, so the decryption will fail
                    .setEncryptedKeyMaterial(
                            encryptedApplicationKey(randomRecoveryKey(), applicationKeyBytes1))
                    .build();
        WrappedApplicationKey applicationKey2 = new WrappedApplicationKey.Builder()
                    .setAlias(TEST_ALIAS2)
                    .setEncryptedKeyMaterial(
                            encryptedApplicationKey(recoveryKey, applicationKeyBytes2))
                    .build();

        Map<String, String> recoveredKeys = mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                TEST_SESSION_ID,
                encryptedClaimResponse,
                ImmutableList.of(applicationKey1, applicationKey2));

        assertThat(recoveredKeys).hasSize(1);
        assertThat(recoveredKeys).containsKey(TEST_ALIAS2);
    }

    @Test
    public void setSnapshotCreatedPendingIntent() throws Exception {
        int uid = Binder.getCallingUid();
        PendingIntent intent = PendingIntent.getBroadcast(
                InstrumentationRegistry.getTargetContext(), /*requestCode=*/1,
                new Intent()
                        .setPackage(InstrumentationRegistry.getTargetContext().getPackageName()),
                /*flags=*/ PendingIntent.FLAG_MUTABLE);
        mRecoverableKeyStoreManager.setSnapshotCreatedPendingIntent(intent);
        verify(mMockListenersStorage).setSnapshotListener(eq(uid), any(PendingIntent.class));
    }

    @Test
    public void setServerParams_updatesServerParams() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        byte[] serverParams = new byte[] { 1 };

        mRecoverableKeyStoreManager.setServerParams(serverParams);

        assertThat(mRecoverableKeyStoreDb.getServerParams(userId, uid)).isEqualTo(serverParams);
    }

    @Test
    public void setServerParams_doesNotSetSnapshotPendingIfInitializing() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        byte[] serverParams = new byte[] { 1 };

        mRecoverableKeyStoreManager.setServerParams(serverParams);

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void setServerParams_doesNotSetSnapshotPendingIfSettingSameValue() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        byte[] serverParams = new byte[] { 1 };

        mRecoverableKeyStoreManager.setServerParams(serverParams);

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.setServerParams(serverParams);

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void setServerParams_setsSnapshotPendingIfUpdatingValue() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        mRecoverableKeyStoreManager.setServerParams(new byte[] { 1 });

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.setServerParams(new byte[] { 2 });

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void setRecoverySecretTypes_updatesSecretTypes() throws Exception {
        int[] types1 = new int[]{11, 2000};
        int[] types2 = new int[]{1, 2, 3};
        int[] types3 = new int[]{};

        mRecoverableKeyStoreManager.setRecoverySecretTypes(types1);
        assertThat(mRecoverableKeyStoreManager.getRecoverySecretTypes()).isEqualTo(
                types1);

        mRecoverableKeyStoreManager.setRecoverySecretTypes(types2);
        assertThat(mRecoverableKeyStoreManager.getRecoverySecretTypes()).isEqualTo(
                types2);

        mRecoverableKeyStoreManager.setRecoverySecretTypes(types3);
        assertThat(mRecoverableKeyStoreManager.getRecoverySecretTypes()).isEqualTo(
                types3);
    }

    @Test
    public void setRecoverySecretTypes_doesNotSetSnapshotPendingIfIniting() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        int[] secretTypes = new int[] { 101 };

        mRecoverableKeyStoreManager.setRecoverySecretTypes(secretTypes);

        // There were no keys.
        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void setRecoverySecretTypes_doesNotSetSnapshotPendingIfSettingSameValue()
            throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        int[] secretTypes = new int[] { 101 };

        mRecoverableKeyStoreManager.setRecoverySecretTypes(secretTypes);

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.setRecoverySecretTypes(secretTypes);

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();
    }

    @Test
    public void setRecoverySecretTypes_setsSnapshotPendingIfUpdatingValue() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();

        mRecoverableKeyStoreManager.setRecoverySecretTypes(new int[] { 101 });

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isFalse();

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.setRecoverySecretTypes(new int[] { 102 });

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void setRecoverySecretTypes_throwsIfNullTypes() throws Exception {
        try {
            mRecoverableKeyStoreManager.setRecoverySecretTypes(/*types=*/ null);
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("is null");
        }
    }

    @Test
    public void setRecoverySecretTypes_updatesShouldCreateSnapshot() throws Exception {
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        mRecoverableKeyStoreManager.setRecoverySecretTypes(new int[] { 1 });

        generateKeyAndSimulateSync(userId, uid, 10);

        mRecoverableKeyStoreManager.setRecoverySecretTypes(new int[] { 2 });

        assertThat(mRecoverableKeyStoreDb.getShouldCreateSnapshot(userId, uid)).isTrue();
    }

    @Test
    public void setRecoveryStatus() throws Exception {
        int userId = UserHandle.getCallingUserId();
        int uid = Binder.getCallingUid();
        int status = 100;
        int status2 = 200;
        String alias = "key1";
        byte[] keyMetadata = null;

        WrappedKey wrappedKey = new WrappedKey(NONCE, KEY_MATERIAL, keyMetadata, GENERATION_ID,
                status);
        mRecoverableKeyStoreDb.insertKey(userId, uid, alias, wrappedKey);
        Map<String, Integer> statuses =
                mRecoverableKeyStoreManager.getRecoveryStatus();
        assertThat(statuses).hasSize(1);
        assertThat(statuses).containsEntry(alias, status);

        mRecoverableKeyStoreManager.setRecoveryStatus(alias, status2);
        statuses = mRecoverableKeyStoreManager.getRecoveryStatus();
        assertThat(statuses).hasSize(1);
        assertThat(statuses).containsEntry(alias, status2); // updated
    }

    @Test
    public void setRecoveryStatus_throwsIfNullAlias() throws Exception {
        try {
            mRecoverableKeyStoreManager.setRecoveryStatus(/*alias=*/ null, /*status=*/ 100);
            fail("should have thrown");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("is null");
        }
    }

    @Test
    public void lockScreenSecretAvailable_syncsKeysForUser() throws Exception {
        mRecoverableKeyStoreManager.lockScreenSecretAvailable(
                LockPatternUtils.CREDENTIAL_TYPE_PATTERN, "password".getBytes(), 11);

        verify(mExecutorService).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    public void lockScreenSecretChanged_syncsKeysForUser() throws Exception {
        mRecoverableKeyStoreManager.lockScreenSecretChanged(
                LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                "password".getBytes(),
                11);

        verify(mExecutorService).schedule(any(Runnable.class), anyLong(), any());
    }

    private static byte[] encryptedApplicationKey(
            SecretKey recoveryKey, byte[] applicationKey) throws Exception {
        return KeySyncUtils.encryptKeysWithRecoveryKey(recoveryKey, ImmutableMap.of(
                TEST_ALIAS,
                Pair.create(new SecretKeySpec(applicationKey, "AES"), /*metadata=*/ null)
        )).get(TEST_ALIAS);
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
                /*header=*/ ArrayUtils.concat(RECOVERY_RESPONSE_HEADER, vaultParams),
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

    private void generateKeyAndSimulateSync(int userId, int uid, int snapshotVersion)
            throws Exception{
        mRecoverableKeyStoreManager.generateKeyWithMetadata(TEST_ALIAS, KEY_METADATA_NULL);
        // Simulate key sync.
        mRecoverableKeyStoreDb.setSnapshotVersion(userId, uid, snapshotVersion);
        mRecoverableKeyStoreDb.setShouldCreateSnapshot(userId, uid, false);
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
}
