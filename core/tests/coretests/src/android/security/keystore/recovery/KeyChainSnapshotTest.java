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

package android.security.keystore.recovery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.google.common.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;

// TODO(b/73862682): Add tests for RecoveryCertPath
@RunWith(AndroidJUnit4.class)
@SmallTest
public class KeyChainSnapshotTest {

    private static final int COUNTER_ID = 42;
    private static final int SNAPSHOT_VERSION = 13;
    private static final byte[] SALT = new byte[] { 0, 1, 0, 1 };
    private static final byte[] SERVER_PARAMS = new byte[] { 6, 7, 9, 2 };
    private static final byte[] RECOVERY_KEY_BLOB = new byte[] { 9, 1, 4, 6, 7 };
    private static final int MAX_ATTEMPTS = 10;
    private static final int LOCK_SCREEN_UI_FORMAT = KeyChainProtectionParams.UI_FORMAT_PASSWORD;
    private static final int USER_SECRET_TYPE = KeyChainProtectionParams.TYPE_LOCKSCREEN;
    private static final String KEY_ALIAS = "steph";
    private static final byte[] KEY_MATERIAL = new byte[] { 3, 5, 7, 9, 1 };

    @Test
    public void build_setsCounterId() {
        assertEquals(COUNTER_ID, createKeyChainSnapshot().getCounterId());
    }

    @Test
    public void build_setsSnapshotVersion() {
        assertEquals(SNAPSHOT_VERSION, createKeyChainSnapshot().getSnapshotVersion());
    }

    @Test
    public void build_setsMaxAttempts() {
        assertEquals(MAX_ATTEMPTS, createKeyChainSnapshot().getMaxAttempts());
    }

    @Test
    public void build_setsServerParams() {
        assertArrayEquals(SERVER_PARAMS, createKeyChainSnapshot().getServerParams());
    }

    @Test
    public void build_setsRecoveryKeyBlob() {
        assertArrayEquals(RECOVERY_KEY_BLOB,
                createKeyChainSnapshot().getEncryptedRecoveryKeyBlob());
    }

    @Test
    public void build_setsKeyChainProtectionParams() {
        KeyChainSnapshot snapshot = createKeyChainSnapshot();

        assertEquals(1, snapshot.getKeyChainProtectionParams().size());
        KeyChainProtectionParams keyChainProtectionParams =
                snapshot.getKeyChainProtectionParams().get(0);
        assertEquals(USER_SECRET_TYPE, keyChainProtectionParams.getUserSecretType());
        assertEquals(LOCK_SCREEN_UI_FORMAT, keyChainProtectionParams.getLockScreenUiFormat());
        KeyDerivationParams keyDerivationParams = keyChainProtectionParams.getKeyDerivationParams();
        assertEquals(KeyDerivationParams.ALGORITHM_SHA256, keyDerivationParams.getAlgorithm());
        assertArrayEquals(SALT, keyDerivationParams.getSalt());
    }

    @Test
    public void build_setsWrappedApplicationKeys() {
        KeyChainSnapshot snapshot = createKeyChainSnapshot();

        assertEquals(1, snapshot.getWrappedApplicationKeys().size());
        WrappedApplicationKey wrappedApplicationKey = snapshot.getWrappedApplicationKeys().get(0);
        assertEquals(KEY_ALIAS, wrappedApplicationKey.getAlias());
        assertArrayEquals(KEY_MATERIAL, wrappedApplicationKey.getEncryptedKeyMaterial());
    }

    @Test
    public void writeToParcel_writesCounterId() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertEquals(COUNTER_ID, snapshot.getCounterId());
    }

    @Test
    public void writeToParcel_writesSnapshotVersion() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertEquals(SNAPSHOT_VERSION, snapshot.getSnapshotVersion());
    }

    @Test
    public void writeToParcel_writesMaxAttempts() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertEquals(MAX_ATTEMPTS, snapshot.getMaxAttempts());
    }

    @Test
    public void writeToParcel_writesServerParams() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertArrayEquals(SERVER_PARAMS, snapshot.getServerParams());
    }

    @Test
    public void writeToParcel_writesKeyRecoveryBlob() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertArrayEquals(RECOVERY_KEY_BLOB, snapshot.getEncryptedRecoveryKeyBlob());
    }

    @Test
    public void writeToParcel_writesKeyChainProtectionParams() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertEquals(1, snapshot.getKeyChainProtectionParams().size());
        KeyChainProtectionParams keyChainProtectionParams =
                snapshot.getKeyChainProtectionParams().get(0);
        assertEquals(USER_SECRET_TYPE, keyChainProtectionParams.getUserSecretType());
        assertEquals(LOCK_SCREEN_UI_FORMAT, keyChainProtectionParams.getLockScreenUiFormat());
        KeyDerivationParams keyDerivationParams = keyChainProtectionParams.getKeyDerivationParams();
        assertEquals(KeyDerivationParams.ALGORITHM_SHA256, keyDerivationParams.getAlgorithm());
        assertArrayEquals(SALT, keyDerivationParams.getSalt());
    }

    @Test
    public void writeToParcel_writesWrappedApplicationKeys() {
        KeyChainSnapshot snapshot = writeToThenReadFromParcel(createKeyChainSnapshot());

        assertEquals(1, snapshot.getWrappedApplicationKeys().size());
        WrappedApplicationKey wrappedApplicationKey = snapshot.getWrappedApplicationKeys().get(0);
        assertEquals(KEY_ALIAS, wrappedApplicationKey.getAlias());
        assertArrayEquals(KEY_MATERIAL, wrappedApplicationKey.getEncryptedKeyMaterial());
    }

    private static KeyChainSnapshot createKeyChainSnapshot() {
        return new KeyChainSnapshot.Builder()
                .setCounterId(COUNTER_ID)
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setServerParams(SERVER_PARAMS)
                .setMaxAttempts(MAX_ATTEMPTS)
                .setEncryptedRecoveryKeyBlob(RECOVERY_KEY_BLOB)
                .setKeyChainProtectionParams(Lists.newArrayList(createKeyChainProtectionParams()))
                .setWrappedApplicationKeys(Lists.newArrayList(createWrappedApplicationKey()))
                .build();
    }

    private static KeyChainProtectionParams createKeyChainProtectionParams() {
        return new KeyChainProtectionParams.Builder()
                .setKeyDerivationParams(createKeyDerivationParams())
                .setUserSecretType(USER_SECRET_TYPE)
                .setLockScreenUiFormat(LOCK_SCREEN_UI_FORMAT)
                .build();
    }

    private static KeyDerivationParams createKeyDerivationParams() {
        return KeyDerivationParams.createSha256Params(SALT);
    }

    private static WrappedApplicationKey createWrappedApplicationKey() {
        return new WrappedApplicationKey.Builder()
                .setAlias(KEY_ALIAS)
                .setEncryptedKeyMaterial(KEY_MATERIAL)
                .build();
    }

    private static KeyChainSnapshot writeToThenReadFromParcel(KeyChainSnapshot params) {
        Parcel parcel = Parcel.obtain();
        params.writeToParcel(parcel, /*flags=*/ 0);
        parcel.setDataPosition(0);
        KeyChainSnapshot fromParcel = KeyChainSnapshot.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return fromParcel;
    }
}
