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

package com.android.server.backup.encryption.keys;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.RuntimeEnvironment.application;

import android.security.keystore.recovery.RecoveryController;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;
import com.android.server.testing.shadows.ShadowRecoveryController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.SecureRandom;

import javax.crypto.SecretKey;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowRecoveryController.class)
public class TertiaryKeyManagerTest {

    private static final String TEST_PACKAGE_1 = "com.example.app1";
    private static final String TEST_PACKAGE_2 = "com.example.app2";

    private SecureRandom mSecureRandom;
    private RecoverableKeyStoreSecondaryKey mSecondaryKey;

    @Mock private TertiaryKeyRotationScheduler mTertiaryKeyRotationScheduler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSecureRandom = new SecureRandom();
        mSecondaryKey =
                new RecoverableKeyStoreSecondaryKeyManager(
                                RecoveryController.getInstance(application), mSecureRandom)
                        .generate();
        ShadowRecoveryController.reset();
    }

    private TertiaryKeyManager createNewManager(String packageName) {
        return new TertiaryKeyManager(
                application,
                mSecureRandom,
                mTertiaryKeyRotationScheduler,
                mSecondaryKey,
                packageName);
    }

    @Test
    public void getKey_noExistingKey_returnsNewKey() throws Exception {
        assertThat(createNewManager(TEST_PACKAGE_1).getKey()).isNotNull();
    }

    @Test
    public void getKey_noExistingKey_recordsIncrementalBackup() throws Exception {
        createNewManager(TEST_PACKAGE_1).getKey();
        verify(mTertiaryKeyRotationScheduler).recordBackup(TEST_PACKAGE_1);
    }

    @Test
    public void getKey_existingKey_returnsExistingKey() throws Exception {
        TertiaryKeyManager manager = createNewManager(TEST_PACKAGE_1);
        SecretKey existingKey = manager.getKey();

        assertThat(manager.getKey()).isEqualTo(existingKey);
    }

    @Test
    public void getKey_existingKey_recordsBackupButNotRotation() throws Exception {
        createNewManager(TEST_PACKAGE_1).getKey();
        reset(mTertiaryKeyRotationScheduler);

        createNewManager(TEST_PACKAGE_1).getKey();

        verify(mTertiaryKeyRotationScheduler).recordBackup(TEST_PACKAGE_1);
        verify(mTertiaryKeyRotationScheduler, never()).recordKeyRotation(any());
    }

    @Test
    public void getKey_existingKeyButRotationRequired_returnsNewKey() throws Exception {
        SecretKey firstKey = createNewManager(TEST_PACKAGE_1).getKey();
        when(mTertiaryKeyRotationScheduler.isKeyRotationDue(TEST_PACKAGE_1)).thenReturn(true);

        SecretKey secondKey = createNewManager(TEST_PACKAGE_1).getKey();

        assertThat(secondKey).isNotEqualTo(firstKey);
    }

    @Test
    public void getKey_existingKeyButRotationRequired_recordsKeyRotationAndBackup()
            throws Exception {
        when(mTertiaryKeyRotationScheduler.isKeyRotationDue(TEST_PACKAGE_1)).thenReturn(true);
        createNewManager(TEST_PACKAGE_1).getKey();

        InOrder inOrder = inOrder(mTertiaryKeyRotationScheduler);
        inOrder.verify(mTertiaryKeyRotationScheduler).recordKeyRotation(TEST_PACKAGE_1);
        inOrder.verify(mTertiaryKeyRotationScheduler).recordBackup(TEST_PACKAGE_1);
    }

    @Test
    public void getKey_twoApps_returnsDifferentKeys() throws Exception {
        TertiaryKeyManager firstManager = createNewManager(TEST_PACKAGE_1);
        TertiaryKeyManager secondManager = createNewManager(TEST_PACKAGE_2);
        SecretKey firstKey = firstManager.getKey();

        assertThat(secondManager.getKey()).isNotEqualTo(firstKey);
    }

    @Test
    public void getWrappedKey_noExistingKey_returnsWrappedNewKey() throws Exception {
        TertiaryKeyManager manager = createNewManager(TEST_PACKAGE_1);
        SecretKey unwrappedKey = manager.getKey();
        WrappedKeyProto.WrappedKey wrappedKey = manager.getWrappedKey();

        SecretKey expectedUnwrappedKey =
                KeyWrapUtils.unwrap(mSecondaryKey.getSecretKey(), wrappedKey);
        assertThat(unwrappedKey).isEqualTo(expectedUnwrappedKey);
    }

    @Test
    public void getWrappedKey_existingKey_returnsWrappedExistingKey() throws Exception {
        TertiaryKeyManager manager = createNewManager(TEST_PACKAGE_1);
        WrappedKeyProto.WrappedKey wrappedKey = manager.getWrappedKey();
        SecretKey unwrappedKey = manager.getKey();

        SecretKey expectedUnwrappedKey =
                KeyWrapUtils.unwrap(mSecondaryKey.getSecretKey(), wrappedKey);
        assertThat(unwrappedKey).isEqualTo(expectedUnwrappedKey);
    }

    @Test
    public void wasKeyRotated_noExistingKey_returnsTrue() throws Exception {
        TertiaryKeyManager manager = createNewManager(TEST_PACKAGE_1);
        assertThat(manager.wasKeyRotated()).isTrue();
    }

    @Test
    public void wasKeyRotated_existingKey_returnsFalse() throws Exception {
        createNewManager(TEST_PACKAGE_1).getKey();
        assertThat(createNewManager(TEST_PACKAGE_1).wasKeyRotated()).isFalse();
    }
}
