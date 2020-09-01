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

package com.android.server.backup.encryption;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.app.Application;
import android.security.keystore.recovery.RecoveryController;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.testing.shadows.ShadowRecoveryController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Optional;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowRecoveryController.class)
public class CryptoSettingsTest {

    private static final String TEST_KEY_ALIAS =
            "com.android.server.backup.encryption/keystore/08120c326b928ff34c73b9c58581da63";

    private CryptoSettings mCryptoSettings;
    private Application mApplication;

    @Before
    public void setUp() {
        ShadowRecoveryController.reset();

        mApplication = ApplicationProvider.getApplicationContext();
        mCryptoSettings = CryptoSettings.getInstanceForTesting(mApplication);
    }

    @Test
    public void getActiveSecondaryAlias_isInitiallyAbsent() {
        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().isPresent()).isFalse();
    }

    @Test
    public void getActiveSecondaryAlias_returnsAliasIfKeyIsInRecoveryController() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);
        mCryptoSettings.setActiveSecondaryKeyAlias(TEST_KEY_ALIAS);
        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get()).isEqualTo(TEST_KEY_ALIAS);
    }

    @Test
    public void getNextSecondaryAlias_isInitiallyAbsent() {
        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().isPresent()).isFalse();
    }

    @Test
    public void getNextSecondaryAlias_returnsAliasIfKeyIsInRecoveryController() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);
        mCryptoSettings.setNextSecondaryAlias(TEST_KEY_ALIAS);
        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().get()).isEqualTo(TEST_KEY_ALIAS);
    }

    @Test
    public void isInitialized_isInitiallyFalse() {
        assertThat(mCryptoSettings.getIsInitialized()).isFalse();
    }

    @Test
    public void setActiveSecondaryAlias_throwsIfKeyIsNotInRecoveryController() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mCryptoSettings.setActiveSecondaryKeyAlias(TEST_KEY_ALIAS));
    }

    @Test
    public void setNextSecondaryAlias_inRecoveryController_setsAlias() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);

        mCryptoSettings.setNextSecondaryAlias(TEST_KEY_ALIAS);

        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().get()).isEqualTo(TEST_KEY_ALIAS);
    }

    @Test
    public void setNextSecondaryAlias_throwsIfKeyIsNotInRecoveryController() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mCryptoSettings.setNextSecondaryAlias(TEST_KEY_ALIAS));
    }

    @Test
    public void removeNextSecondaryAlias_removesIt() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);
        mCryptoSettings.setNextSecondaryAlias(TEST_KEY_ALIAS);

        mCryptoSettings.removeNextSecondaryKeyAlias();

        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().isPresent()).isFalse();
    }

    @Test
    public void initializeWithKeyAlias_setsAsInitialized() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);
        mCryptoSettings.initializeWithKeyAlias(TEST_KEY_ALIAS);
        assertThat(mCryptoSettings.getIsInitialized()).isTrue();
    }

    @Test
    public void initializeWithKeyAlias_setsActiveAlias() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);
        mCryptoSettings.initializeWithKeyAlias(TEST_KEY_ALIAS);
        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().get()).isEqualTo(TEST_KEY_ALIAS);
    }

    @Test
    public void initializeWithKeyAlias_throwsIfKeyIsNotInRecoveryController() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mCryptoSettings.initializeWithKeyAlias(TEST_KEY_ALIAS));
    }

    @Test
    public void initializeWithKeyAlias_throwsIfAlreadyInitialized() throws Exception {
        setAliasIsInRecoveryController(TEST_KEY_ALIAS);
        mCryptoSettings.initializeWithKeyAlias(TEST_KEY_ALIAS);

        assertThrows(
                IllegalStateException.class,
                () -> mCryptoSettings.initializeWithKeyAlias(TEST_KEY_ALIAS));
    }

    @Test
    public void getSecondaryLastRotated_returnsEmptyInitially() {
        assertThat(mCryptoSettings.getSecondaryLastRotated()).isEqualTo(Optional.empty());
    }

    @Test
    public void getSecondaryLastRotated_returnsTimestampAfterItIsSet() {
        long timestamp = 1000001;

        mCryptoSettings.setSecondaryLastRotated(timestamp);

        assertThat(mCryptoSettings.getSecondaryLastRotated().get()).isEqualTo(timestamp);
    }

    @Test
    public void getAncestralSecondaryKeyVersion_notSet_returnsOptionalAbsent() {
        assertThat(mCryptoSettings.getAncestralSecondaryKeyVersion().isPresent()).isFalse();
    }

    @Test
    public void getAncestralSecondaryKeyVersion_isSet_returnsSetValue() {
        String secondaryKeyVersion = "some_secondary_key";
        mCryptoSettings.setAncestralSecondaryKeyVersion(secondaryKeyVersion);

        assertThat(mCryptoSettings.getAncestralSecondaryKeyVersion().get())
                .isEqualTo(secondaryKeyVersion);
    }

    @Test
    public void getAncestralSecondaryKeyVersion_isSetMultipleTimes_returnsLastSetValue() {
        String secondaryKeyVersion1 = "some_secondary_key";
        String secondaryKeyVersion2 = "another_secondary_key";
        mCryptoSettings.setAncestralSecondaryKeyVersion(secondaryKeyVersion1);
        mCryptoSettings.setAncestralSecondaryKeyVersion(secondaryKeyVersion2);

        assertThat(mCryptoSettings.getAncestralSecondaryKeyVersion().get())
                .isEqualTo(secondaryKeyVersion2);
    }

    @Test
    public void clearAllSettingsForBackup_clearsStateForBackup() throws Exception {
        String key1 = "key1";
        String key2 = "key2";
        String ancestralKey = "ancestral_key";
        setAliasIsInRecoveryController(key1);
        setAliasIsInRecoveryController(key2);
        mCryptoSettings.setActiveSecondaryKeyAlias(key1);
        mCryptoSettings.setNextSecondaryAlias(key2);
        mCryptoSettings.setSecondaryLastRotated(100001);
        mCryptoSettings.setAncestralSecondaryKeyVersion(ancestralKey);

        mCryptoSettings.clearAllSettingsForBackup();

        assertThat(mCryptoSettings.getActiveSecondaryKeyAlias().isPresent()).isFalse();
        assertThat(mCryptoSettings.getNextSecondaryKeyAlias().isPresent()).isFalse();
        assertThat(mCryptoSettings.getSecondaryLastRotated().isPresent()).isFalse();
        assertThat(mCryptoSettings.getAncestralSecondaryKeyVersion().get()).isEqualTo(ancestralKey);
    }

    private void setAliasIsInRecoveryController(String alias) throws Exception {
        RecoveryController recoveryController = RecoveryController.getInstance(mApplication);
        recoveryController.generateKey(alias);
    }
}
