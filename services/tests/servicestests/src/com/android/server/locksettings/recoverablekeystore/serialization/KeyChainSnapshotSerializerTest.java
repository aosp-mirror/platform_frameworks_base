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

package com.android.server.locksettings.recoverablekeystore.serialization;

import static com.google.common.truth.Truth.assertThat;

import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.TestData;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.cert.CertPath;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyChainSnapshotSerializerTest {
    private static final int COUNTER_ID = 2134;
    private static final int SNAPSHOT_VERSION = 125;
    private static final int MAX_ATTEMPTS = 21;
    private static final byte[] SERVER_PARAMS = new byte[] { 8, 2, 4 };
    private static final byte[] KEY_BLOB = new byte[] { 124, 53, 53, 53 };
    private static final byte[] PUBLIC_KEY_BLOB = new byte[] { 6, 6, 6, 6, 6, 6, 7 };
    private static final CertPath CERT_PATH = TestData.CERT_PATH_1;
    private static final int SECRET_TYPE = KeyChainProtectionParams.TYPE_LOCKSCREEN;
    private static final int LOCK_SCREEN_UI = KeyChainProtectionParams.UI_FORMAT_PASSWORD;
    private static final byte[] SALT = new byte[] { 5, 4, 3, 2, 1 };
    private static final int MEMORY_DIFFICULTY = 45;
    private static final int ALGORITHM = KeyDerivationParams.ALGORITHM_SCRYPT;
    private static final byte[] SECRET = new byte[] { 1, 2, 3, 4 };

    private static final String TEST_KEY_1_ALIAS = "key1";
    private static final byte[] TEST_KEY_1_BYTES = new byte[] { 66, 77, 88 };

    private static final String TEST_KEY_2_ALIAS = "key2";
    private static final byte[] TEST_KEY_2_BYTES = new byte[] { 99, 33, 11 };

    private static final String TEST_KEY_3_ALIAS = "key3";
    private static final byte[] TEST_KEY_3_BYTES = new byte[] { 2, 8, 100 };

    @Test
    public void roundTrip_persistsCounterId() throws Exception {
        assertThat(roundTrip().getCounterId()).isEqualTo(COUNTER_ID);
    }

    @Test
    public void roundTrip_persistsSnapshotVersion() throws Exception {
        assertThat(roundTrip().getSnapshotVersion()).isEqualTo(SNAPSHOT_VERSION);
    }

    @Test
    public void roundTrip_persistsMaxAttempts() throws Exception {
        assertThat(roundTrip().getMaxAttempts()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    public void roundTrip_persistsRecoveryKey() throws Exception {
        assertThat(roundTrip().getEncryptedRecoveryKeyBlob()).isEqualTo(KEY_BLOB);
    }

    @Test
    public void roundTrip_persistsServerParams() throws Exception {
        assertThat(roundTrip().getServerParams()).isEqualTo(SERVER_PARAMS);
    }

    @Test
    public void roundTrip_persistsCertPath() throws Exception {
        assertThat(roundTrip().getTrustedHardwareCertPath()).isEqualTo(CERT_PATH);
    }

    @Test
    public void roundTrip_persistsBackendPublicKey() throws Exception {
        assertThat(roundTrip().getTrustedHardwarePublicKey()).isEqualTo(PUBLIC_KEY_BLOB);
    }

    @Test
    public void roundTrip_persistsParamsList() throws Exception {
        assertThat(roundTrip().getKeyChainProtectionParams()).hasSize(1);
    }

    @Test
    public void roundTripParams_persistsUserSecretType() throws Exception {
        assertThat(roundTripParams().getUserSecretType()).isEqualTo(SECRET_TYPE);
    }

    @Test
    public void roundTripParams_persistsLockScreenUi() throws Exception {
        assertThat(roundTripParams().getLockScreenUiFormat()).isEqualTo(LOCK_SCREEN_UI);
    }

    @Test
    public void roundTripParams_persistsSalt() throws Exception {
        assertThat(roundTripParams().getKeyDerivationParams().getSalt()).isEqualTo(SALT);
    }

    @Test
    public void roundTripParams_persistsAlgorithm() throws Exception {
        assertThat(roundTripParams().getKeyDerivationParams().getAlgorithm()).isEqualTo(ALGORITHM);
    }

    @Test
    public void roundTripParams_persistsMemoryDifficulty() throws Exception {
        assertThat(roundTripParams().getKeyDerivationParams().getMemoryDifficulty())
                .isEqualTo(MEMORY_DIFFICULTY);
    }

    @Test
    public void roundTripParams_doesNotPersistSecret() throws Exception {
        assertThat(roundTripParams().getSecret()).isEmpty();
    }

    @Test
    public void roundTripKeys_hasCorrectLength() throws Exception {
        assertThat(roundTripKeys()).hasSize(3);
    }

    @Test
    public void roundTripKeys_0_persistsAlias() throws Exception {
        assertThat(roundTripKeys().get(0).getAlias()).isEqualTo(TEST_KEY_1_ALIAS);
    }

    @Test
    public void roundTripKeys_0_persistsKeyBytes() throws Exception {
        assertThat(roundTripKeys().get(0).getEncryptedKeyMaterial()).isEqualTo(TEST_KEY_1_BYTES);
    }

    @Test
    public void roundTripKeys_1_persistsAlias() throws Exception {
        assertThat(roundTripKeys().get(1).getAlias()).isEqualTo(TEST_KEY_2_ALIAS);
    }

    @Test
    public void roundTripKeys_1_persistsKeyBytes() throws Exception {
        assertThat(roundTripKeys().get(1).getEncryptedKeyMaterial()).isEqualTo(TEST_KEY_2_BYTES);
    }

    @Test
    public void roundTripKeys_2_persistsAlias() throws Exception {
        assertThat(roundTripKeys().get(2).getAlias()).isEqualTo(TEST_KEY_3_ALIAS);
    }

    @Test
    public void roundTripKeys_2_persistsKeyBytes() throws Exception {
        assertThat(roundTripKeys().get(2).getEncryptedKeyMaterial()).isEqualTo(TEST_KEY_3_BYTES);
    }

    @Test
    public void serialize_doesNotThrowForNullPublicKey() throws Exception {
        KeyChainSnapshotSerializer.serialize(
                createTestKeyChainSnapshotNoPublicKey(), new ByteArrayOutputStream());
    }

    private static List<WrappedApplicationKey> roundTripKeys() throws Exception {
        return roundTrip().getWrappedApplicationKeys();
    }

    private static KeyChainProtectionParams roundTripParams() throws Exception {
        return roundTrip().getKeyChainProtectionParams().get(0);
    }

    public static KeyChainSnapshot roundTrip() throws Exception {
        KeyChainSnapshot snapshot = createTestKeyChainSnapshot();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        KeyChainSnapshotSerializer.serialize(snapshot, byteArrayOutputStream);
        return KeyChainSnapshotDeserializer.deserialize(
                new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
    }

    private static KeyChainSnapshot createTestKeyChainSnapshot() throws Exception {
        return new KeyChainSnapshot.Builder()
                .setCounterId(COUNTER_ID)
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setServerParams(SERVER_PARAMS)
                .setMaxAttempts(MAX_ATTEMPTS)
                .setEncryptedRecoveryKeyBlob(KEY_BLOB)
                .setKeyChainProtectionParams(createKeyChainProtectionParamsList())
                .setWrappedApplicationKeys(createKeys())
                .setTrustedHardwareCertPath(CERT_PATH)
                .setTrustedHardwarePublicKey(PUBLIC_KEY_BLOB)
                .build();
    }

    private static KeyChainSnapshot createTestKeyChainSnapshotNoPublicKey() throws Exception {
        return new KeyChainSnapshot.Builder()
                .setCounterId(COUNTER_ID)
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setServerParams(SERVER_PARAMS)
                .setMaxAttempts(MAX_ATTEMPTS)
                .setEncryptedRecoveryKeyBlob(KEY_BLOB)
                .setKeyChainProtectionParams(createKeyChainProtectionParamsList())
                .setWrappedApplicationKeys(createKeys())
                .setTrustedHardwareCertPath(CERT_PATH)
                .build();
    }

    private static List<WrappedApplicationKey> createKeys() {
        ArrayList<WrappedApplicationKey> keyList = new ArrayList<>();
        keyList.add(createKey(TEST_KEY_1_ALIAS, TEST_KEY_1_BYTES));
        keyList.add(createKey(TEST_KEY_2_ALIAS, TEST_KEY_2_BYTES));
        keyList.add(createKey(TEST_KEY_3_ALIAS, TEST_KEY_3_BYTES));
        return keyList;
    }

    private static List<KeyChainProtectionParams> createKeyChainProtectionParamsList() {
        KeyDerivationParams keyDerivationParams =
                KeyDerivationParams.createScryptParams(SALT, MEMORY_DIFFICULTY);
        KeyChainProtectionParams keyChainProtectionParams = new KeyChainProtectionParams.Builder()
                .setKeyDerivationParams(keyDerivationParams)
                .setUserSecretType(SECRET_TYPE)
                .setLockScreenUiFormat(LOCK_SCREEN_UI)
                .setSecret(SECRET)
                .build();
        ArrayList<KeyChainProtectionParams> keyChainProtectionParamsList =
                new ArrayList<>(1);
        keyChainProtectionParamsList.add(keyChainProtectionParams);
        return keyChainProtectionParamsList;
    }

    private static WrappedApplicationKey createKey(String alias, byte[] bytes) {
        return new WrappedApplicationKey.Builder()
                .setAlias(alias)
                .setEncryptedKeyMaterial(bytes)
                .build();
    }
}
