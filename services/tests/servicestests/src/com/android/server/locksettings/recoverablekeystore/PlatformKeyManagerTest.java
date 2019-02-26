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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.Context;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;

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

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PlatformKeyManagerTest {

    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS = 15;
    private static final int USER_ID_FIXTURE = 42;

    @Mock private Context mContext;
    @Mock private KeyStoreProxy mKeyStoreProxy;
    @Mock private KeyguardManager mKeyguardManager;

    @Captor private ArgumentCaptor<KeyStore.ProtectionParameter> mProtectionParameterCaptor;
    @Captor private ArgumentCaptor<KeyStore.Entry> mEntryArgumentCaptor;

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;

    private PlatformKeyManager mPlatformKeyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);
        mPlatformKeyManager = new PlatformKeyManager(
                mContext, mKeyStoreProxy, mRecoverableKeyStoreDb);

        when(mContext.getSystemService(anyString())).thenReturn(mKeyguardManager);
        when(mContext.getSystemServiceName(any())).thenReturn("test");
        when(mKeyguardManager.isDeviceSecure(USER_ID_FIXTURE)).thenReturn(true);
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
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
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
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
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
    public void init_createsDecryptKeyWithAuthenticationRequired() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertTrue(getDecryptKeyProtection().isUserAuthenticationRequired());
    }

    @Test
    public void init_createsDecryptKeyWithAuthenticationValidFor15Seconds() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(
                USER_AUTHENTICATION_VALIDITY_DURATION_SECONDS,
                getDecryptKeyProtection().getUserAuthenticationValidityDurationSeconds());
    }

    @Test
    public void init_createsDecryptKeyBoundToTheUsersAuthentication() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(
                USER_ID_FIXTURE,
                getDecryptKeyProtection().getBoundToSpecificSecureUserId());
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

        assertEquals(1,
                mRecoverableKeyStoreDb.getPlatformKeyGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void init_setsGenerationIdTo1() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(1, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void init_incrementsGenerationIdIfKeyIsUnavailable() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(2, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void init_doesNotIncrementGenerationIdIfKeyAvailable() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);

        mPlatformKeyManager.init(USER_ID_FIXTURE);

        assertEquals(1, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void getGenerationId_returnsMinusOneIfNotInitialized() throws Exception {
        assertEquals(-1, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void getDecryptKey_getsDecryptKeyWithCorrectAlias() throws Exception {
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any());
    }

    @Test
    public void getDecryptKey_generatesNewKeyIfOldDecryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true); // new version is available
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/decrypt"),
                any());
    }

    @Test
    public void getDecryptKey_generatesNewKeyIfOldEncryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/decrypt"),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfOldOneIsInvalid() throws Exception {
        doThrow(new UnrecoverableKeyException()).when(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any());

        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any());
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"),
                any());
    }

    @Test
    public void getDecryptKey_generatesNewKeyIfOldOneIsInvalid() throws Exception {
        doThrow(new UnrecoverableKeyException()).when(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any());

        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getDecryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/decrypt"),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfDecryptKeyIsUnrecoverable() throws Exception {
        doThrow(new UnrecoverableKeyException()).when(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any());
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true); // new version is available
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfOldDecryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true); // new version is available
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"),
                any());
    }

    @Test
    public void getEncryptKey_generatesNewKeyIfOldEncryptKeyWasRemoved() throws Exception {
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(false); // was removed
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/2/decrypt")).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).containsAlias(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"));
        // Attempt to get regenerated key.
        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"),
                any());
    }

    @Test
    public void getEncryptKey_getsEncryptKeyWithCorrectAlias() throws Exception {
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/encrypt")).thenReturn(true);
        when(mKeyStoreProxy
                .containsAlias("com.android.server.locksettings.recoverablekeystore/"
                        + "platform/42/1/decrypt")).thenReturn(true);

        mPlatformKeyManager.getEncryptKey(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).getKey(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any());
    }

    @Test
    public void regenerate_incrementsTheGenerationId() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        assertEquals(2, mPlatformKeyManager.getGenerationId(USER_ID_FIXTURE));
    }

    @Test
    public void regenerate_deletesOldKeysFromKeystore() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).deleteEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"));
        verify(mKeyStoreProxy).deleteEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"));

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        // Removes second generation keys.
        verify(mKeyStoreProxy).deleteEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"));
        verify(mKeyStoreProxy).deleteEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/decrypt"));
    }

    @Test
    public void regenerate_generatesANewEncryptKeyWithTheCorrectAlias() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/encrypt"),
                any(),
                any());
    }

    @Test
    public void regenerate_generatesANewDecryptKeyWithTheCorrectAlias() throws Exception {
        mPlatformKeyManager.init(USER_ID_FIXTURE);

        mPlatformKeyManager.regenerate(USER_ID_FIXTURE);

        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/2/decrypt"),
                any(),
                any());
    }

    private KeyProtection getEncryptKeyProtection() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/encrypt"),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }

    private KeyProtection getDecryptKeyProtection() throws Exception {
        verify(mKeyStoreProxy).setEntry(
                eq("com.android.server.locksettings.recoverablekeystore/platform/42/1/decrypt"),
                any(),
                mProtectionParameterCaptor.capture());
        return (KeyProtection) mProtectionParameterCaptor.getValue();
    }
}
