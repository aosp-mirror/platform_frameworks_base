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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.RemoteException;
import android.security.GateKeeper;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.service.gatekeeper.IGateKeeperService;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.List;

import javax.crypto.KeyGenerator;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PlatformKeyManagerTest {

    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int MIN_GENERATION_ID = 1000000;
    private static final int PRIMARY_USER_ID_FIXTURE = 0;
    private static final int USER_ID_FIXTURE = 42;
    private static final long USER_SID = 4200L;
    private static final String KEY_ALGORITHM = "AES";
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String TESTING_KEYSTORE_KEY_ALIAS = "testing-key-store-key-alias";

    private static final String ENCRYPTION_KEY_ALIAS_1 =
             "com.android.server.locksettings.recoverablekeystore/platform/42/1000000/encrypt";
    private static final String DECRYPTION_KEY_ALIAS_1 =
             "com.android.server.locksettings.recoverablekeystore/platform/42/1000000/decrypt";
    private static final String DECRYPTION_KEY_FOR_ALIAS_PRIMARY_USER_1 =
             "com.android.server.locksettings.recoverablekeystore/platform/0/1000000/decrypt";
    private static final String ENCRYPTION_KEY_ALIAS_2 =
             "com.android.server.locksettings.recoverablekeystore/platform/42/1000001/encrypt";
    private static final String DECRYPTION_KEY_ALIAS_2 =
             "com.android.server.locksettings.recoverablekeystore/platform/42/1000001/decrypt";

    @Mock private Context mContext;
    @Mock private KeyStoreProxy mKeyStoreProxy;
    @Mock private KeyguardManager mKeyguardManager;
    @Mock private IGateKeeperService mGateKeeperService;

    @Captor private ArgumentCaptor<KeyStore.ProtectionParameter> mProtectionParameterCaptor;
    @Captor private ArgumentCaptor<KeyStore.Entry> mEntryArgumentCaptor;

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;

    private PlatformKeyManager mPlatformKeyManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);
        mPlatformKeyManager = new PlatformKeyManagerTestable(
                mContext, mKeyStoreProxy, mRecoverableKeyStoreDb, mGateKeeperService);

        when(mContext.getSystemService(anyString())).thenReturn(mKeyguardManager);
        when(mContext.getSystemServiceName(any())).thenReturn("test");
        when(mKeyguardManager.isDeviceSecure(USER_ID_FIXTURE)).thenReturn(true);
        when(mGateKeeperService.getSecureUserId(USER_ID_FIXTURE)).thenReturn(USER_SID);
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void init_createsEncryptKeyWithCorrectAlias() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).setEntry(
                eq(ENCRYPTION_KEY_ALIAS_1),
                any(),
                any());
    }

    @Test
    public void init_createsEncryptKeyWithCorrectPurposes() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(KeyProperties.PURPOSE_ENCRYPT, getEncryptKeyProtection().getPurposes());
    }

    @Test
    public void init_createsEncryptKeyWithCorrectPaddings() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertArrayEquals(
                new String[] { KeyProperties.ENCRYPTION_PADDING_NONE },
                getEncryptKeyProtection().getEncryptionPaddings());
    }

    @Test
    public void init_createsEncryptKeyWithCorrectBlockModes() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertArrayEquals(
                new String[] { KeyProperties.BLOCK_MODE_GCM },
                getEncryptKeyProtection().getBlockModes());
    }

    @Test
    public void init_createsEncryptKeyWithoutAuthenticationRequired() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertFalse(getEncryptKeyProtection().isUserAuthenticationRequired());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectAlias() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).setEntry(
                eq(DECRYPTION_KEY_ALIAS_1),
                any(),
                any());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectPurposes() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(KeyProperties.PURPOSE_DECRYPT, getDecryptKeyProtection().getPurposes());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectPaddings() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertArrayEquals(
                new String[] { KeyProperties.ENCRYPTION_PADDING_NONE },
                getDecryptKeyProtection().getEncryptionPaddings());
    }

    @Test
    public void init_createsDecryptKeyWithCorrectBlockModes() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertArrayEquals(
                new String[] { KeyProperties.BLOCK_MODE_GCM },
                getDecryptKeyProtection().getBlockModes());
    }

    @Test
    public void init_primaryUser_createsDecryptKeyWithUnlockedDeviceRequired() throws Exception {
        mPlatformKeyManager.init(PRIMARY_USER_ID_FIXTURE);

        assertTrue(getDecryptKeyProtectionForPrimaryUser().isUnlockedDeviceRequired());
    }

    @Test
    public void init_primaryUser_createsDecryptKeyWithoutAuthenticationRequired() throws Exception {
        mPlatformKeyManager.init(PRIMARY_USER_ID_FIXTURE);

        assertFalse(getDecryptKeyProtectionForPrimaryUser().isUserAuthenticationRequired());
    }

    @Test
    public void init_secondaryUser_createsDecryptKeyWithoutUnlockedDeviceRequired()
            throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertFalse(getDecryptKeyProtection().isUnlockedDeviceRequired());
    }

    @Test
    public void init_secondaryUserUser_createsDecryptKeyWithAuthenticationRequired()
            throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertTrue(getDecryptKeyProtection().isUserAuthenticationRequired());
    }

    @Test
    public void init_createsDecryptKeyBoundToTheUsersAuthentication() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(
                USER_SID,
                getDecryptKeyProtection().getBoundToSpecificSecureUserId());
    }

    @Test
    public void init_doesNotCreateDecryptKeyIfNoSid() throws Exception {
        when(mGateKeeperService.getSecureUserId(USER_ID_FIXTURE))
                .thenReturn(GateKeeper.INVALID_SECURE_USER_ID);

        mPlatformKeyManager.init(USER_ID_FIXTURE);

        verify(mKeyStoreProxy, never()).setEntry(
                eq(DECRYPTION_KEY_ALIAS_1),
                any(),
                any());
    }

    @Test
    public void init_doesNotCreateDecryptKeyOnGateKeeperException() throws Exception {
        when(mGateKeeperService.getSecureUserId(USER_ID_FIXTURE)).thenThrow(new RemoteException());

        expectThrows(RemoteException.class, () -> mPlatformKeyManager.init(USER_ID_FIXTURE));

        verify(mKeyStoreProxy, never()).setEntry(
                eq(DECRYPTION_KEY_ALIAS_1),
                any(),
                any());
    }

    @Test
    public void init_createsBothKeysWithSameMaterial() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        verify(mKeyStoreProxy, times(2)).setEntry(any(), mEntryArgumentCaptor.capture(), any());
        List<KeyStore.Entry> entries = mEntryArgumentCaptor.getAllValues();
        assertArrayEquals(
                ((KeyStore.SecretKeyEntry) entries.get(0)).getSecretKey().getEncoded(),
                ((KeyStore.SecretKeyEntry) entries.get(1)).getSecretKey().getEncoded());
    }

    @Test
    public void init_savesGenerationIdToDatabase() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(MIN_GENERATION_ID,
                mRecoverableKeyStoreDb.getPlatformKeyGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void init_setsGenerationId() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(MIN_GENERATION_ID, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void init_incrementsGenerationIdIfKeyIsUnavailable() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(MIN_GENERATION_ID + 1, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void init_doesNotIncrementGenerationIdIfKeyAvailable() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);

        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(MIN_GENERATION_ID, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void getGenerationId_returnsMinusOneIfNotInitialized() throws Exception {
        assertEquals(-1, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void getDecryptKey_getsDecryptKeyWithCorrectAlias() throws Exception {
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy.getKey(
                eq(DECRYPTION_KEY_ALIAS_1),
                any())).thenReturn(generateAndroidKeyStoreKey());

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).getKey(
                eq(DECRYPTION_KEY_ALIAS_1),
                any());
    }

    @Test
    public void getDecryptKey_generatesNewKeyIfOldDecryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true); // new version
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);
        when(mKeyStoreProxy.getKey(
                eq(DECRYPTION_KEY_ALIAS_2),
                any())).thenReturn(generateAndroidKeyStoreKey());

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq(DECRYPTION_KEY_ALIAS_1));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(DECRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getDecryptKey_generatesNewKeyIfOldEncryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq(ENCRYPTION_KEY_ALIAS_1));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(DECRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfOldOneIsInvalid() throws Exception {
        doThrow(new UnrecoverableKeyException()).when(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_1),
                any());

        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_1),
                any());
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getDecryptKey_generatesNewKeyIfOldOneIsInvalid() throws Exception {
        doThrow(new UnrecoverableKeyException()).when(mKeyStoreProxy).getKey(
                eq(DECRYPTION_KEY_ALIAS_1),
                any());
        when(mKeyStoreProxy.getKey(
                eq(DECRYPTION_KEY_ALIAS_2),
                any())).thenReturn(generateAndroidKeyStoreKey());

        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq(DECRYPTION_KEY_ALIAS_1));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(DECRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfDecryptKeyIsUnrecoverable() throws Exception {
        doThrow(new UnrecoverableKeyException()).when(mKeyStoreProxy).getKey(
                eq(DECRYPTION_KEY_ALIAS_1),
                any());
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true); // new version
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfOldDecryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true); // new version
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq(ENCRYPTION_KEY_ALIAS_1));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfOldEncryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_2)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_2)).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq(ENCRYPTION_KEY_ALIAS_1));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_2),
                any());
    }

    @Test
    public void getEncryptKey_getsEncryptKeyWithCorrectAlias() throws Exception {
        when(mKeyStoreProxy
                .containsAlias(ENCRYPTION_KEY_ALIAS_1)).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias(DECRYPTION_KEY_ALIAS_1)).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).getKey(
                eq(ENCRYPTION_KEY_ALIAS_1),
                any());
    }

    @Test
    public void regenerate_incrementsTheGenerationId() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        assertEquals(MIN_GENERATION_ID + 1, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void regenerate_deletesOldKeysFromKeystore() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).deleteEntry(
                eq(ENCRYPTION_KEY_ALIAS_1));
        verify(mKeyStoreProxy).deleteEntry(
                eq(DECRYPTION_KEY_ALIAS_1));

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        // Removes second generation keys.
        verify(mKeyStoreProxy).deleteEntry(
                eq(ENCRYPTION_KEY_ALIAS_2));
        verify(mKeyStoreProxy).deleteEntry(
                eq(DECRYPTION_KEY_ALIAS_2));
    }

    @Test
    public void regenerate_generatesANewEncryptKeyWithTheCorrectAlias() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).setEntry(
                eq(ENCRYPTION_KEY_ALIAS_2),
                any(),
                any());
    }

    @Test
    public void regenerate_generatesANewDecryptKeyWithTheCorrectAlias() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).setEntry(
                eq(DECRYPTION_KEY_ALIAS_2),
                any(),
                any());
    }

    private KeyProtection getEncryptKeyProtection() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq(ENCRYPTION_KEY_ALIAS_1),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }

    private KeyProtection getDecryptKeyProtection() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq(DECRYPTION_KEY_ALIAS_1),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }

    private KeyProtection getDecryptKeyProtectionForPrimaryUser() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq(DECRYPTION_KEY_FOR_ALIAS_PRIMARY_USER_1),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }

    private AndroidKeyStoreSecretKey generateAndroidKeyStoreKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KEY_ALGORITHM,
                ANDROID_KEY_STORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(TESTING_KEYSTORE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return (AndroidKeyStoreSecretKey) keyGenerator.generateKey();
    }

    class PlatformKeyManagerTestable extends PlatformKeyManager {
        private IGateKeeperService mGateKeeperService;

        PlatformKeyManagerTestable(
                Context context,
                KeyStoreProxy keyStoreProxy,
                RecoverableKeyStoreDb database,
                IGateKeeperService gateKeeperService) {
            super(context, keyStoreProxy, database);
            mGateKeeperService = gateKeeperService;
        }

        @Override
        IGateKeeperService getGateKeeperService() {
            return mGateKeeperService;
        }
    }
}
