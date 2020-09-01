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

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;
import android.security.keystore.recovery.RecoveryController;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.testing.shadows.ShadowRecoveryController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.SecureRandom;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowRecoveryController.class})
@Presubmit
public class StartSecondaryKeyRotationTaskTest {

    private CryptoSettings mCryptoSettings;
    private RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;
    private StartSecondaryKeyRotationTask mStartSecondaryKeyRotationTask;

    @Before
    public void setUp() throws Exception {
        mSecondaryKeyManager =
                new RecoverableKeyStoreSecondaryKeyManager(
                        RecoveryController.getInstance(RuntimeEnvironment.application),
                        new SecureRandom());
        mCryptoSettings = CryptoSettings.getInstanceForTesting(RuntimeEnvironment.application);
        mStartSecondaryKeyRotationTask =
                new StartSecondaryKeyRotationTask(mCryptoSettings, mSecondaryKeyManager);

        ShadowRecoveryController.reset();
    }

    @Test
    public void run_doesNothingIfNoActiveSecondaryExists() {
        mStartSecondaryKeyRotationTask.run();

        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().isPresent()).isFalse();
    }

    @Test
    public void run_doesNotRemoveExistingNextSecondaryKeyIfItIsAlreadyActive() throws Exception {
        generateAnActiveKey();
        String activeAlias = mCryptoSettings.getActiveSecondaryKeyAlias().get();
        mCryptoSettings.setNextSecondaryAlias(activeAlias);

        mStartSecondaryKeyRotationTask.run();

        assertThat(mSecondaryKeyManager.get(activeAlias).isPresent()).isTrue();
    }

    @Test
    public void run_doesRemoveExistingNextSecondaryKeyIfItIsNotYetActive() throws Exception {
        generateAnActiveKey();
        RecoverableKeyStoreSecondaryKey nextKey = mSecondaryKeyManager.generate();
        String nextAlias = nextKey.getAlias();
        mCryptoSettings.setNextSecondaryAlias(nextAlias);

        mStartSecondaryKeyRotationTask.run();

        assertThat(mSecondaryKeyManager.get(nextAlias).isPresent()).isFalse();
    }

    @Test
    public void run_generatesANewNextSecondaryKey() throws Exception {
        generateAnActiveKey();

        mStartSecondaryKeyRotationTask.run();

        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().isPresent()).isTrue();
    }

    @Test
    public void run_generatesANewKeyThatExistsInKeyStore() throws Exception {
        generateAnActiveKey();

        mStartSecondaryKeyRotationTask.run();

        String nextAlias = mCryptoSettings.getNextSecondaryKeyAlias().get();
        assertThat(mSecondaryKeyManager.get(nextAlias).isPresent()).isTrue();
    }

    private void generateAnActiveKey() throws Exception {
        RecoverableKeyStoreSecondaryKey secondaryKey = mSecondaryKeyManager.generate();
        mCryptoSettings.setActiveSecondaryKeyAlias(secondaryKey.getAlias());
    }
}
