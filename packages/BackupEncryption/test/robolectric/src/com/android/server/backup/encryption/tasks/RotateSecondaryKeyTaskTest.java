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

import static com.android.server.backup.testing.CryptoTestUtils.generateAesKey;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertFalse;

import android.app.Application;
import android.platform.test.annotations.Presubmit;
import android.security.keystore.recovery.RecoveryController;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.keys.KeyWrapUtils;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyStore;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;
import com.android.server.testing.fakes.FakeCryptoBackupServer;
import com.android.server.testing.shadows.ShadowRecoveryController;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Presubmit
@Config(shadows = {ShadowRecoveryController.class, ShadowRecoveryController.class})
public class RotateSecondaryKeyTaskTest {
    private static final String APP_1 = "app1";
    private static final String APP_2 = "app2";
    private static final String APP_3 = "app3";

    private static final String CURRENT_SECONDARY_KEY_ALIAS =
            "recoverablekey.alias/d524796bd07de3c2225c63d434eff698";
    private static final String NEXT_SECONDARY_KEY_ALIAS =
            "recoverablekey.alias/6c6d198a7f12e662b6bc45f4849db170";

    private Application mApplication;
    private RotateSecondaryKeyTask mTask;
    private RecoveryController mRecoveryController;
    private FakeCryptoBackupServer mBackupServer;
    private CryptoSettings mCryptoSettings;
    private Map<String, SecretKey> mTertiaryKeysByPackageName;
    private RecoverableKeyStoreSecondaryKeyManager mRecoverableSecondaryKeyManager;

    @Before
    public void setUp() throws Exception {
        mApplication = ApplicationProvider.getApplicationContext();

        mTertiaryKeysByPackageName = new HashMap<>();
        mTertiaryKeysByPackageName.put(APP_1, generateAesKey());
        mTertiaryKeysByPackageName.put(APP_2, generateAesKey());
        mTertiaryKeysByPackageName.put(APP_3, generateAesKey());

        mRecoveryController = RecoveryController.getInstance(mApplication);
        mRecoverableSecondaryKeyManager =
                new RecoverableKeyStoreSecondaryKeyManager(
                        RecoveryController.getInstance(mApplication), new SecureRandom());
        mBackupServer = new FakeCryptoBackupServer();
        mCryptoSettings = CryptoSettings.getInstanceForTesting(mApplication);
        addNextSecondaryKeyToRecoveryController();
        mCryptoSettings.setNextSecondaryAlias(NEXT_SECONDARY_KEY_ALIAS);

        mTask =
                new RotateSecondaryKeyTask(
                        mApplication,
                        mRecoverableSecondaryKeyManager,
                        mBackupServer,
                        mCryptoSettings,
                        mRecoveryController);

        ShadowRecoveryController.reset();
    }

    @Test
    public void run_failsIfThereIsNoActiveSecondaryKey() throws Exception {
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertFalse(mCryptoSettings.getActiveSecondaryKeyAlias().isPresent());
    }

    @Test
    public void run_failsIfActiveSecondaryIsNotInRecoveryController() throws Exception {
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        // Have to add it first as otherwise CryptoSettings throws an exception when trying to set
        // it
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get())
                .isEqualTo(CURRENT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_doesNothingIfFlagIsDisabled() throws Exception {
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());
        addWrappedTertiaries();

        mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get())
                .isEqualTo(CURRENT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_setsActiveSecondary() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());
        addWrappedTertiaries();

        mTask.run();

        assertThat(mBackupServer.getActiveSecondaryKeyAlias().get())
                .isEqualTo(NEXT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_rewrapsExistingTertiaryKeys() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());
        addWrappedTertiaries();

        mTask.run();

        Map<String, WrappedKeyProto.WrappedKey> rewrappedKeys =
                mBackupServer.getAllTertiaryKeys(NEXT_SECONDARY_KEY_ALIAS);
        SecretKey secondaryKey = (SecretKey) mRecoveryController.getKey(NEXT_SECONDARY_KEY_ALIAS);
        for (String packageName : mTertiaryKeysByPackageName.keySet()) {
            WrappedKeyProto.WrappedKey rewrappedKey = rewrappedKeys.get(packageName);
            assertThat(KeyWrapUtils.unwrap(secondaryKey, rewrappedKey))
                    .isEqualTo(mTertiaryKeysByPackageName.get(packageName));
        }
    }

    @Test
    public void run_persistsRewrappedKeysToDisk() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());
        addWrappedTertiaries();

        mTask.run();

        RecoverableKeyStoreSecondaryKey secondaryKey = getRecoverableKey(NEXT_SECONDARY_KEY_ALIAS);
        Map<String, SecretKey> keys =
                TertiaryKeyStore.newInstance(mApplication, secondaryKey).getAll();
        for (String packageName : mTertiaryKeysByPackageName.keySet()) {
            SecretKey tertiaryKey = mTertiaryKeysByPackageName.get(packageName);
            SecretKey newlyWrappedKey = keys.get(packageName);
            assertThat(tertiaryKey.getEncoded()).isEqualTo(newlyWrappedKey.getEncoded());
        }
    }

    @Test
    public void run_stillSetsActiveSecondaryIfNoTertiaries() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mBackupServer.getActiveSecondaryKeyAlias().get())
                .isEqualTo(NEXT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_setsActiveSecondaryKeyAliasInSettings() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get())
                .isEqualTo(NEXT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_removesNextSecondaryKeyAliasInSettings() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertFalse(mCryptoSettings.getNextSecondaryKeyAlias().isPresent());
    }

    @Test
    public void run_deletesOldKeyFromRecoverableKeyStoreLoader() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNCED);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mRecoveryController.getKey(CURRENT_SECONDARY_KEY_ALIAS)).isNull();
    }

    @Test
    public void run_doesNotRotateIfNoNextAlias() throws Exception {
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());
        mCryptoSettings.removeNextSecondaryKeyAlias();

        mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get())
                .isEqualTo(CURRENT_SECONDARY_KEY_ALIAS);
        assertFalse(mCryptoSettings.getNextSecondaryKeyAlias().isPresent());
    }

    @Test
    public void run_doesNotRotateIfKeyIsNotSyncedYet() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get())
                .isEqualTo(CURRENT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_doesNotClearNextKeyIfSyncIsJustPending() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().get())
                .isEqualTo(NEXT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_doesNotRotateIfPermanentFailure() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get())
                .isEqualTo(CURRENT_SECONDARY_KEY_ALIAS);
    }

    @Test
    public void run_removesNextKeyIfPermanentFailure() throws Exception {
        addNextSecondaryKeyToRecoveryController();
        setNextKeyRecoveryStatus(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
        addCurrentSecondaryKeyToRecoveryController();
        mCryptoSettings.setActiveSecondaryKeyAlias(CURRENT_SECONDARY_KEY_ALIAS);
        mBackupServer.setActiveSecondaryKeyAlias(
                CURRENT_SECONDARY_KEY_ALIAS, Collections.emptyMap());

        mTask.run();

        assertFalse(mCryptoSettings.getNextSecondaryKeyAlias().isPresent());
    }

    private void setNextKeyRecoveryStatus(int status) throws Exception {
        mRecoveryController.setRecoveryStatus(NEXT_SECONDARY_KEY_ALIAS, status);
    }

    private void addCurrentSecondaryKeyToRecoveryController() throws Exception {
        mRecoveryController.generateKey(CURRENT_SECONDARY_KEY_ALIAS);
    }

    private void addNextSecondaryKeyToRecoveryController() throws Exception {
        mRecoveryController.generateKey(NEXT_SECONDARY_KEY_ALIAS);
    }

    private void addWrappedTertiaries() throws Exception {
        TertiaryKeyStore tertiaryKeyStore =
                TertiaryKeyStore.newInstance(
                        mApplication, getRecoverableKey(CURRENT_SECONDARY_KEY_ALIAS));

        for (String packageName : mTertiaryKeysByPackageName.keySet()) {
            tertiaryKeyStore.save(packageName, mTertiaryKeysByPackageName.get(packageName));
        }
    }

    private RecoverableKeyStoreSecondaryKey getRecoverableKey(String alias) throws Exception {
        return mRecoverableSecondaryKeyManager.get(alias).get();
    }
}
