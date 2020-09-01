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
import android.security.keystore.recovery.RecoveryController;

import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey.Status;
import com.android.server.backup.testing.CryptoTestUtils;
import com.android.server.testing.shadows.ShadowInternalRecoveryServiceException;
import com.android.server.testing.shadows.ShadowRecoveryController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import javax.crypto.SecretKey;

/** Tests for {@link RecoverableKeyStoreSecondaryKey}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
@Config(shadows = {ShadowRecoveryController.class, ShadowInternalRecoveryServiceException.class})
public class RecoverableKeyStoreSecondaryKeyTest {
    private static final String TEST_ALIAS = "test";
    private static final int NONEXISTENT_STATUS_CODE = 42;

    private RecoverableKeyStoreSecondaryKey mSecondaryKey;
    private SecretKey mGeneratedSecretKey;
    private Context mContext;

    /** Instantiate a {@link RecoverableKeyStoreSecondaryKey} to use in tests. */
    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.application;
        mGeneratedSecretKey = CryptoTestUtils.generateAesKey();
        mSecondaryKey = new RecoverableKeyStoreSecondaryKey(TEST_ALIAS, mGeneratedSecretKey);
    }

    /** Reset the {@link ShadowRecoveryController}. */
    @After
    public void tearDown() throws Exception {
        ShadowRecoveryController.reset();
    }

    /**
     * Checks that {@link RecoverableKeyStoreSecondaryKey#getAlias()} returns the value supplied in
     * the constructor.
     */
    @Test
    public void getAlias() {
        String alias = mSecondaryKey.getAlias();

        assertThat(alias).isEqualTo(TEST_ALIAS);
    }

    /**
     * Checks that {@link RecoverableKeyStoreSecondaryKey#getSecretKey()} returns the value supplied
     * in the constructor.
     */
    @Test
    public void getSecretKey() {
        SecretKey secretKey = mSecondaryKey.getSecretKey();

        assertThat(secretKey).isEqualTo(mGeneratedSecretKey);
    }

    /**
     * Checks that passing a secret key that is null to the constructor throws an exception.
     */
    @Test
    public void constructor_withNullSecretKey_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new RecoverableKeyStoreSecondaryKey(TEST_ALIAS, null));
    }

    /**
     * Checks that passing an alias that is null to the constructor throws an exception.
     */
    @Test
    public void constructor_withNullAlias_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new RecoverableKeyStoreSecondaryKey(null, mGeneratedSecretKey));
    }

    /** Checks that the synced status is returned correctly. */
    @Test
    public void getStatus_whenSynced_returnsSynced() throws Exception {
        setStatus(RecoveryController.RECOVERY_STATUS_SYNCED);

        int status = mSecondaryKey.getStatus(mContext);

        assertThat(status).isEqualTo(Status.SYNCED);
    }

    /** Checks that the in progress sync status is returned correctly. */
    @Test
    public void getStatus_whenNotSynced_returnsNotSynced() throws Exception {
        setStatus(RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);

        int status = mSecondaryKey.getStatus(mContext);

        assertThat(status).isEqualTo(Status.NOT_SYNCED);
    }

    /** Checks that the failure status is returned correctly. */
    @Test
    public void getStatus_onPermanentFailure_returnsDestroyed() throws Exception {
        setStatus(RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);

        int status = mSecondaryKey.getStatus(mContext);

        assertThat(status).isEqualTo(Status.DESTROYED);
    }

    /** Checks that an unknown status results in {@code NOT_SYNCED} being returned. */
    @Test
    public void getStatus_forUnknownStatusCode_returnsNotSynced() throws Exception {
        setStatus(NONEXISTENT_STATUS_CODE);

        int status = mSecondaryKey.getStatus(mContext);

        assertThat(status).isEqualTo(Status.NOT_SYNCED);
    }

    /** Checks that an internal error results in {@code NOT_SYNCED} being returned. */
    @Test
    public void getStatus_onInternalError_returnsNotSynced() throws Exception {
        ShadowRecoveryController.setThrowsInternalError(true);

        int status = mSecondaryKey.getStatus(mContext);

        assertThat(status).isEqualTo(Status.NOT_SYNCED);
    }

    private void setStatus(int status) throws Exception {
        ShadowRecoveryController.setRecoveryStatus(TEST_ALIAS, status);
    }
}
