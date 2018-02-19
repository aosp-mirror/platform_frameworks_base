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

package com.android.server.locksettings.recoverablekeystore.storage;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyChainProtectionParams;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PersistentKeyChainSnapshotTest {

    private static final String ALIAS = "some_key";
    private static final String ALIAS2 = "another_key";
    private static final byte[] RECOVERY_KEY_MATERIAL = "recovery_key_data"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_MATERIAL = "app_key_data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PUBLIC_KEY = "public_key_data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACCOUNT = "test_account".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SALT = "salt".getBytes(StandardCharsets.UTF_8);
    private static final int SNAPSHOT_VERSION = 2;
    private static final int MAX_ATTEMPTS = 10;
    private static final long COUNTER_ID = 123456789L;
    private static final byte[] SERVER_PARAMS = "server_params".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZERO_BYTES = new byte[0];
    private static final byte[] ONE_BYTE = new byte[]{(byte) 11};
    private static final byte[] TWO_BYTES = new byte[]{(byte) 222,(byte) 222};

    @Test
    public void testWriteInt() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        writer.writeInt(Integer.MIN_VALUE);
        writer.writeInt(Integer.MAX_VALUE);
        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);
        assertThat(reader.readInt()).isEqualTo(Integer.MIN_VALUE);
        assertThat(reader.readInt()).isEqualTo(Integer.MAX_VALUE);

        assertThrows(
                IOException.class,
                () -> reader.readInt());
    }

    @Test
    public void testWriteLong() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        writer.writeLong(Long.MIN_VALUE);
        writer.writeLong(Long.MAX_VALUE);
        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);
        assertThat(reader.readLong()).isEqualTo(Long.MIN_VALUE);
        assertThat(reader.readLong()).isEqualTo(Long.MAX_VALUE);

        assertThrows(
                IOException.class,
                () -> reader.readLong());
    }

    @Test
    public void testWriteBytes() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        writer.writeBytes(ZERO_BYTES);
        writer.writeBytes(ONE_BYTE);
        writer.writeBytes(TWO_BYTES);
        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);
        assertThat(reader.readBytes()).isEqualTo(ZERO_BYTES);
        assertThat(reader.readBytes()).isEqualTo(ONE_BYTE);
        assertThat(reader.readBytes()).isEqualTo(TWO_BYTES);

        assertThrows(
                IOException.class,
                () -> reader.readBytes());
    }

    @Test
    public void testReadBytes_returnsNullArrayAsEmpty() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        writer.writeBytes(null);
        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);
        assertThat(reader.readBytes()).isEqualTo(new byte[]{}); // null -> empty array
    }

    @Test
    public void testWriteKeyEntry() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        WrappedApplicationKey entry = new WrappedApplicationKey.Builder()
                .setAlias(ALIAS)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .setAccount(ACCOUNT)
                .build();
        writer.writeKeyEntry(entry);

        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);

        WrappedApplicationKey copy = reader.readKeyEntry();
        assertThat(copy.getAlias()).isEqualTo(ALIAS);
        assertThat(copy.getEncryptedKeyMaterial()).isEqualTo(KEY_MATERIAL);
        assertThat(copy.getAccount()).isEqualTo(ACCOUNT);

        assertThrows(
                IOException.class,
                () -> reader.readKeyEntry());
    }

    @Test
    public void testWriteProtectionParams() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();
        KeyDerivationParams derivationParams = KeyDerivationParams.createSha256Params(SALT);
        KeyChainProtectionParams protectionParams =  new KeyChainProtectionParams.Builder()
                .setUserSecretType(1)
                .setLockScreenUiFormat(2)
                .setKeyDerivationParams(derivationParams)
                .build();
        writer.writeProtectionParams(protectionParams);

        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);

        KeyChainProtectionParams copy = reader.readProtectionParams();
        assertThat(copy.getUserSecretType()).isEqualTo(1);
        assertThat(copy.getLockScreenUiFormat()).isEqualTo(2);
        assertThat(copy.getKeyDerivationParams().getSalt()).isEqualTo(SALT);

        assertThrows(
                IOException.class,
                () -> reader.readProtectionParams());
    }

    @Test
    public void testKeyChainSnapshot() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();

        KeyDerivationParams derivationParams = KeyDerivationParams.createSha256Params(SALT);

        ArrayList<KeyChainProtectionParams> protectionParamsList = new ArrayList<>();
        protectionParamsList.add(new KeyChainProtectionParams.Builder()
                .setUserSecretType(1)
                .setLockScreenUiFormat(2)
                .setKeyDerivationParams(derivationParams)
                .build());

        ArrayList<WrappedApplicationKey> appKeysList = new ArrayList<>();
        appKeysList.add(new WrappedApplicationKey.Builder()
                .setAlias(ALIAS)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .setAccount(ACCOUNT)
                .build());

        KeyChainSnapshot snapshot =  new KeyChainSnapshot.Builder()
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setKeyChainProtectionParams(protectionParamsList)
                .setEncryptedRecoveryKeyBlob(KEY_MATERIAL)
                .setWrappedApplicationKeys(appKeysList)
                .setMaxAttempts(MAX_ATTEMPTS)
                .setCounterId(COUNTER_ID)
                .setServerParams(SERVER_PARAMS)
                .setTrustedHardwarePublicKey(PUBLIC_KEY)
                .build();

        writer.writeKeyChainSnapshot(snapshot);

        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);

        KeyChainSnapshot copy = reader.readKeyChainSnapshot();
        assertThat(copy.getSnapshotVersion()).isEqualTo(SNAPSHOT_VERSION);
        assertThat(copy.getKeyChainProtectionParams()).hasSize(2);
        assertThat(copy.getKeyChainProtectionParams().get(0).getUserSecretType()).isEqualTo(1);
        assertThat(copy.getKeyChainProtectionParams().get(1).getUserSecretType()).isEqualTo(2);
        assertThat(copy.getEncryptedRecoveryKeyBlob()).isEqualTo(RECOVERY_KEY_MATERIAL);
        assertThat(copy.getWrappedApplicationKeys()).hasSize(2);
        assertThat(copy.getWrappedApplicationKeys().get(0).getAlias()).isEqualTo(ALIAS);
        assertThat(copy.getWrappedApplicationKeys().get(1).getAlias()).isEqualTo(ALIAS2);
        assertThat(copy.getMaxAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(copy.getCounterId()).isEqualTo(COUNTER_ID);
        assertThat(copy.getServerParams()).isEqualTo(SERVER_PARAMS);
        assertThat(copy.getTrustedHardwarePublicKey()).isEqualTo(PUBLIC_KEY);

        assertThrows(
                IOException.class,
                () -> reader.readKeyChainSnapshot());

        verifyDeserialize(snapshot);
    }

    @Test
    public void testKeyChainSnapshot_withManyKeysAndProtectionParams() throws Exception {
        PersistentKeyChainSnapshot writer = new PersistentKeyChainSnapshot();
        writer.initWriter();

        KeyDerivationParams derivationParams = KeyDerivationParams.createSha256Params(SALT);

        ArrayList<KeyChainProtectionParams> protectionParamsList = new ArrayList<>();
        protectionParamsList.add(new KeyChainProtectionParams.Builder()
                .setUserSecretType(1)
                .setLockScreenUiFormat(2)
                .setKeyDerivationParams(derivationParams)
                .build());
        protectionParamsList.add(new KeyChainProtectionParams.Builder()
                .setUserSecretType(2)
                .setLockScreenUiFormat(3)
                .setKeyDerivationParams(derivationParams)
                .build());
        ArrayList<WrappedApplicationKey> appKeysList = new ArrayList<>();
        appKeysList.add(new WrappedApplicationKey.Builder()
                .setAlias(ALIAS)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .setAccount(ACCOUNT)
                .build());
        appKeysList.add(new WrappedApplicationKey.Builder()
                .setAlias(ALIAS2)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .setAccount(ACCOUNT)
                .build());


        KeyChainSnapshot snapshot =  new KeyChainSnapshot.Builder()
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setKeyChainProtectionParams(protectionParamsList)
                .setEncryptedRecoveryKeyBlob(KEY_MATERIAL)
                .setWrappedApplicationKeys(appKeysList)
                .setMaxAttempts(MAX_ATTEMPTS)
                .setCounterId(COUNTER_ID)
                .setServerParams(SERVER_PARAMS)
                .setTrustedHardwarePublicKey(PUBLIC_KEY)
                .build();

        writer.writeKeyChainSnapshot(snapshot);

        byte[] result = writer.getOutput();

        PersistentKeyChainSnapshot reader = new PersistentKeyChainSnapshot();
        reader.initReader(result);

        KeyChainSnapshot copy = reader.readKeyChainSnapshot();
        assertThat(copy.getSnapshotVersion()).isEqualTo(SNAPSHOT_VERSION);
        assertThat(copy.getKeyChainProtectionParams().get(0).getUserSecretType()).isEqualTo(1);
        assertThat(copy.getEncryptedRecoveryKeyBlob()).isEqualTo(RECOVERY_KEY_MATERIAL);
        assertThat(copy.getWrappedApplicationKeys().get(0).getAlias()).isEqualTo(ALIAS);
        assertThat(copy.getMaxAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(copy.getCounterId()).isEqualTo(COUNTER_ID);
        assertThat(copy.getServerParams()).isEqualTo(SERVER_PARAMS);
        assertThat(copy.getTrustedHardwarePublicKey()).isEqualTo(PUBLIC_KEY);

        assertThrows(
                IOException.class,
                () -> reader.readKeyChainSnapshot());

        verifyDeserialize(snapshot);
    }

    private void verifyDeserialize(KeyChainSnapshot snapshot) throws Exception {
        byte[] serialized = PersistentKeyChainSnapshot.serialize(snapshot);
        KeyChainSnapshot copy = PersistentKeyChainSnapshot.deserialize(serialized);
        assertThat(copy.getSnapshotVersion())
                .isEqualTo(snapshot.getSnapshotVersion());
        assertThat(copy.getKeyChainProtectionParams().size())
                .isEqualTo(copy.getKeyChainProtectionParams().size());
        assertThat(copy.getEncryptedRecoveryKeyBlob())
                .isEqualTo(snapshot.getEncryptedRecoveryKeyBlob());
        assertThat(copy.getWrappedApplicationKeys().size())
                .isEqualTo(snapshot.getWrappedApplicationKeys().size());
        assertThat(copy.getMaxAttempts()).isEqualTo(snapshot.getMaxAttempts());
        assertThat(copy.getCounterId()).isEqualTo(snapshot.getCounterId());
        assertThat(copy.getServerParams()).isEqualTo(snapshot.getServerParams());
        assertThat(copy.getTrustedHardwarePublicKey())
                .isEqualTo(snapshot.getTrustedHardwarePublicKey());
    }

    @Test
    public void testDeserialize_failsForNewerVersion() throws Exception {
        byte[] newVersion = new byte[]{(byte) 2, (byte) 0, (byte) 0, (byte) 0};
        assertThrows(
                IOException.class,
                () -> PersistentKeyChainSnapshot.deserialize(newVersion));
    }

    @Test
    public void testDeserialize_failsForEmptyData() throws Exception {
        byte[] empty = new byte[]{};
        assertThrows(
                IOException.class,
                () -> PersistentKeyChainSnapshot.deserialize(empty));
    }

}

