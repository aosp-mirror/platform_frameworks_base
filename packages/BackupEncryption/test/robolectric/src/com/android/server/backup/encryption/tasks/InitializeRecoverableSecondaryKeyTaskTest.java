/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.tasks;

import static android.security.keystore.recovery.RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.Application;
import android.security.keystore.recovery.RecoveryController;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.testing.fakes.FakeCryptoBackupServer;
import com.android.server.testing.shadows.ShadowRecoveryController;

import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(shadows = {ShadowRecoveryController.class})
@RunWith(RobolectricTestRunner.class)
public class InitializeRecoverableSecondaryKeyTaskTest {
    @Mock private CryptoSettings mMockCryptoSettings;

    private Application mApplication;
    private InitializeRecoverableSecondaryKeyTask mTask;
    private CryptoSettings mCryptoSettings;
    private FakeCryptoBackupServer mFakeCryptoBackupServer;
    private RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        ShadowRecoveryController.reset();

        mApplication = ApplicationProvider.getApplicationContext();
        mFakeCryptoBackupServer = new FakeCryptoBackupServer();
        mCryptoSettings = CryptoSettings.getInstanceForTesting(mApplication);
        mSecondaryKeyManager =
                new RecoverableKeyStoreSecondaryKeyManager(
                        RecoveryController.getInstance(mApplication), new SecureRandom());

        mTask =
                new InitializeRecoverableSecondaryKeyTask(
                        mApplication, mCryptoSettings, mSecondaryKeyManager, mFakeCryptoBackupServer);
    }

    @Test
    public void testRun_generatesNewKeyInRecoveryController() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mTask.run();

        assertThat(RecoveryController.getInstance(mApplication).getAliases())
                .contains(key.getAlias());
    }

    @Test
    public void testRun_setsAliasOnServer() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mTask.run();

        assertThat(mFakeCryptoBackupServer.getActiveSecondaryKeyAlias().get())
                .isEqualTo(key.getAlias());
    }

    @Test
    public void testRun_setsAliasInSettings() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get()).isEqualTo(key.getAlias());
    }

    @Test
    public void testRun_initializesSettings() throws Exception {
        mTask.run();

        assertThat(mCryptoSettings.getIsInitialized()).isTrue();
    }

    @Test
    public void testRun_initializeSettingsFails_throws() throws Exception {
        useMockCryptoSettings();
        doThrow(IllegalArgumentException.class)
                .when(mMockCryptoSettings)
                .initializeWithKeyAlias(any());


        assertThrows(IllegalArgumentException.class, () -> mTask.run());
    }

    @Test
    public void testRun_doesNotGenerateANewKeyIfOneIsAvailable() throws Exception {
        RecoverableKeyStoreSecondaryKey key1 = mTask.run();
        RecoverableKeyStoreSecondaryKey key2 = mTask.run();

        assertThat(key1.getAlias()).isEqualTo(key2.getAlias());
        assertThat(key2.getSecretKey()).isEqualTo(key2.getSecretKey());
    }

    @Test
    public void testRun_existingKeyButDestroyed_throws() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mTask.run();
        ShadowRecoveryController.setRecoveryStatus(
                key.getAlias(), RECOVERY_STATUS_PERMANENT_FAILURE);

        assertThrows(InvalidKeyException.class, () -> mTask.run());
    }

    @Test
    public void testRun_settingsInitializedButNotSecondaryKeyAlias_throws() {
        useMockCryptoSettings();
        when(mMockCryptoSettings.getIsInitialized()).thenReturn(true);
        when(mMockCryptoSettings.getActiveSecondaryKeyAlias()).thenReturn(Optional.empty());

        assertThrows(InvalidKeyException.class, () -> mTask.run());
    }

    @Test
    public void testRun_keyAliasSetButNotInStore_throws() {
        useMockCryptoSettings();
        when(mMockCryptoSettings.getIsInitialized()).thenReturn(true);
        when(mMockCryptoSettings.getActiveSecondaryKeyAlias())
                .thenReturn(Optional.of("missingAlias"));

        assertThrows(InvalidKeyException.class, () -> mTask.run());
    }

    private void useMockCryptoSettings() {
        mTask =
                new InitializeRecoverableSecondaryKeyTask(
                        mApplication,
                        mMockCryptoSettings,
                        mSecondaryKeyManager,
                        mFakeCryptoBackupServer);
    }
}
