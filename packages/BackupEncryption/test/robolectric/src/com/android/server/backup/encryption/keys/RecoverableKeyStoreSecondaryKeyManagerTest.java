/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.RecoveryController;

import com.android.server.testing.shadows.ShadowInternalRecoveryServiceException;
import com.android.server.testing.shadows.ShadowRecoveryController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.SecureRandom;
import java.util.Optional;

/** Tests for {@link RecoverableKeyStoreSecondaryKeyManager}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
@Config(shadows = {ShadowRecoveryController.class, ShadowInternalRecoveryServiceException.class})
public class RecoverableKeyStoreSecondaryKeyManagerTest {
    private static final String BACKUP_KEY_ALIAS_PREFIX =
            "com.android.server.backup/recoverablekeystore/";
    private static final int BITS_PER_BYTE = 8;
    private static final int BACKUP_KEY_SUFFIX_LENGTH_BYTES = 128 / BITS_PER_BYTE;
    private static final int HEX_PER_BYTE = 2;
    private static final int BACKUP_KEY_ALIAS_LENGTH =
            BACKUP_KEY_ALIAS_PREFIX.length() + BACKUP_KEY_SUFFIX_LENGTH_BYTES * HEX_PER_BYTE;
    private static final String NONEXISTENT_KEY_ALIAS = "NONEXISTENT_KEY_ALIAS";

    private RecoverableKeyStoreSecondaryKeyManager mRecoverableKeyStoreSecondaryKeyManager;
    private Context mContext;

    /** Create a new {@link RecoverableKeyStoreSecondaryKeyManager} to use in tests. */
    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.application;

        mRecoverableKeyStoreSecondaryKeyManager =
                new RecoverableKeyStoreSecondaryKeyManager(
                        RecoveryController.getInstance(mContext), new SecureRandom());
    }

    /** Reset the {@link ShadowRecoveryController}. */
    @After
    public void tearDown() throws Exception {
        ShadowRecoveryController.reset();
    }

    /** The generated key should always have the prefix {@code BACKUP_KEY_ALIAS_PREFIX}. */
    @Test
    public void generate_generatesKeyWithExpectedPrefix() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mRecoverableKeyStoreSecondaryKeyManager.generate();

        assertThat(key.getAlias()).startsWith(BACKUP_KEY_ALIAS_PREFIX);
    }

    /** The generated key should always have length {@code BACKUP_KEY_ALIAS_LENGTH}. */
    @Test
    public void generate_generatesKeyWithExpectedLength() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mRecoverableKeyStoreSecondaryKeyManager.generate();

        assertThat(key.getAlias()).hasLength(BACKUP_KEY_ALIAS_LENGTH);
    }

    /** Ensure that hidden API exceptions are rethrown when generating keys. */
    @Test
    public void generate_encounteringHiddenApiException_rethrowsException() {
        ShadowRecoveryController.setThrowsInternalError(true);

        assertThrows(
                InternalRecoveryServiceException.class,
                mRecoverableKeyStoreSecondaryKeyManager::generate);
    }

    /** Ensure that retrieved keys correspond to those generated earlier. */
    @Test
    public void get_getsKeyGeneratedByController() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mRecoverableKeyStoreSecondaryKeyManager.generate();

        Optional<RecoverableKeyStoreSecondaryKey> retrievedKey =
                mRecoverableKeyStoreSecondaryKeyManager.get(key.getAlias());

        assertThat(retrievedKey.isPresent()).isTrue();
        assertThat(retrievedKey.get().getAlias()).isEqualTo(key.getAlias());
        assertThat(retrievedKey.get().getSecretKey()).isEqualTo(key.getSecretKey());
    }

    /**
     * Ensure that a call to {@link RecoverableKeyStoreSecondaryKeyManager#get(java.lang.String)}
     * for nonexistent aliases returns an emtpy {@link Optional}.
     */
    @Test
    public void get_forNonExistentKey_returnsEmptyOptional() throws Exception {
        Optional<RecoverableKeyStoreSecondaryKey> retrievedKey =
                mRecoverableKeyStoreSecondaryKeyManager.get(NONEXISTENT_KEY_ALIAS);

        assertThat(retrievedKey.isPresent()).isFalse();
    }

    /**
     * Ensure that exceptions occurring during {@link
     * RecoverableKeyStoreSecondaryKeyManager#get(java.lang.String)} are not rethrown.
     */
    @Test
    public void get_encounteringInternalException_doesNotPropagateException() throws Exception {
        ShadowRecoveryController.setThrowsInternalError(true);

        // Should not throw exception
        mRecoverableKeyStoreSecondaryKeyManager.get(NONEXISTENT_KEY_ALIAS);
    }

    /** Ensure that keys are correctly removed from the store. */
    @Test
    public void remove_removesKeyFromRecoverableStore() throws Exception {
        RecoverableKeyStoreSecondaryKey key = mRecoverableKeyStoreSecondaryKeyManager.generate();

        mRecoverableKeyStoreSecondaryKeyManager.remove(key.getAlias());

        assertThat(RecoveryController.getInstance(mContext).getAliases())
                .doesNotContain(key.getAlias());
    }
}
