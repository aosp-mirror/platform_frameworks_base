package com.android.server.locksettings.recoverablekeystore.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.TestData;

import com.google.common.io.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPath;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySnapshotStorageTest {
    private static final int COUNTER_ID = 432546;
    private static final int MAX_ATTEMPTS = 10;
    private static final byte[] SERVER_PARAMS = new byte[] { 12, 8, 2, 4, 15, 64 };
    private static final byte[] KEY_BLOB = new byte[] { 124, 56, 53, 99, 0, 0, 1 };
    private static final CertPath CERT_PATH = TestData.CERT_PATH_2;
    private static final int SECRET_TYPE = KeyChainProtectionParams.TYPE_LOCKSCREEN;
    private static final int LOCK_SCREEN_UI = KeyChainProtectionParams.UI_FORMAT_PATTERN;
    private static final byte[] SALT = new byte[] { 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1 };
    private static final int MEMORY_DIFFICULTY = 12;
    private static final byte[] SECRET = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 0 };

    private static final String TEST_KEY_1_ALIAS = "alias1";
    private static final byte[] TEST_KEY_1_BYTES = new byte[] { 100, 32, 43, 66, 77, 88 };

    private static final String TEST_KEY_2_ALIAS = "alias11";
    private static final byte[] TEST_KEY_2_BYTES = new byte[] { 100, 0, 0, 99, 33, 11 };

    private static final String TEST_KEY_3_ALIAS = "alias111";
    private static final byte[] TEST_KEY_3_BYTES = new byte[] { 1, 1, 1, 0, 2, 8, 100 };

    private static final int TEST_UID = 1000;
    private static final String SNAPSHOT_DIRECTORY = "recoverablekeystore/snapshots";
    private static final String SNAPSHOT_FILE_PATH = "1000.xml";
    private static final String SNAPSHOT_TOP_LEVEL_DIRECTORY = "recoverablekeystore";

    private static final KeyChainSnapshot MINIMAL_KEYCHAIN_SNAPSHOT =
            createTestKeyChainSnapshot(1);

    private Context mContext;
    private RecoverySnapshotStorage mRecoverySnapshotStorage;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mRecoverySnapshotStorage = new RecoverySnapshotStorage(mContext.getFilesDir());
    }

    @After
    public void tearDown() {
        File file = new File(mContext.getFilesDir(), SNAPSHOT_TOP_LEVEL_DIRECTORY);
        FileUtils.deleteContentsAndDir(file);
    }

    @Test
    public void get_isNullForNonExistentSnapshot() {
        assertNull(mRecoverySnapshotStorage.get(1000));
    }

    @Test
    public void get_returnsSetSnapshot() {
        mRecoverySnapshotStorage.put(TEST_UID, MINIMAL_KEYCHAIN_SNAPSHOT);

        assertEquals(MINIMAL_KEYCHAIN_SNAPSHOT, mRecoverySnapshotStorage.get(TEST_UID));
    }

    @Test
    public void get_readsFromDiskIfNoneInMemory() {
        mRecoverySnapshotStorage.put(TEST_UID, MINIMAL_KEYCHAIN_SNAPSHOT);
        RecoverySnapshotStorage storage = new RecoverySnapshotStorage(mContext.getFilesDir());

        assertKeyChainSnapshotsAreEqual(MINIMAL_KEYCHAIN_SNAPSHOT, storage.get(TEST_UID));
    }

    @Test
    public void get_deletesFileIfItIsInvalidSnapshot() throws Exception {
        File folder = new File(mContext.getFilesDir(), SNAPSHOT_DIRECTORY);
        folder.mkdirs();
        File file = new File(folder, SNAPSHOT_FILE_PATH);
        byte[] fileContents = "<keyChainSnapshot></keyChainSnapshot>".getBytes(
                StandardCharsets.UTF_8);
        Files.write(fileContents, file);
        assertTrue(file.exists());

        assertNull(mRecoverySnapshotStorage.get(TEST_UID));

        assertFalse(file.exists());
    }

    @Test
    public void put_overwritesOldFiles() {
        int snapshotVersion = 2;
        mRecoverySnapshotStorage.put(TEST_UID, MINIMAL_KEYCHAIN_SNAPSHOT);

        mRecoverySnapshotStorage.put(TEST_UID, createTestKeyChainSnapshot(snapshotVersion));

        KeyChainSnapshot snapshot = new RecoverySnapshotStorage(mContext.getFilesDir())
                .get(TEST_UID);
        assertEquals(snapshotVersion, snapshot.getSnapshotVersion());
    }

    @Test
    public void put_doesNotThrowIfCannotCreateFiles() throws Exception {
        File evilFile = new File(mContext.getFilesDir(), "recoverablekeystore");
        Files.write(new byte[] { 1 }, evilFile);

        mRecoverySnapshotStorage.put(TEST_UID, MINIMAL_KEYCHAIN_SNAPSHOT);

        assertNull(new RecoverySnapshotStorage(mContext.getFilesDir()).get(TEST_UID));
    }

    @Test
    public void remove_removesSnapshotsFromMemory() {
        mRecoverySnapshotStorage.put(TEST_UID, MINIMAL_KEYCHAIN_SNAPSHOT);
        mRecoverySnapshotStorage.remove(TEST_UID);

        assertNull(mRecoverySnapshotStorage.get(TEST_UID));
    }

    @Test
    public void remove_removesSnapshotsFromDisk() {
        mRecoverySnapshotStorage.put(TEST_UID, MINIMAL_KEYCHAIN_SNAPSHOT);

        new RecoverySnapshotStorage(mContext.getFilesDir()).remove(TEST_UID);

        assertNull(new RecoverySnapshotStorage(mContext.getFilesDir()).get(TEST_UID));
    }

    private void assertKeyChainSnapshotsAreEqual(KeyChainSnapshot a, KeyChainSnapshot b) {
        assertEquals(b.getCounterId(), a.getCounterId());
        assertEquals(b.getSnapshotVersion(), a.getSnapshotVersion());
        assertArrayEquals(b.getServerParams(), a.getServerParams());
        assertEquals(b.getMaxAttempts(), a.getMaxAttempts());
        assertArrayEquals(b.getEncryptedRecoveryKeyBlob(), a.getEncryptedRecoveryKeyBlob());
        assertEquals(b.getTrustedHardwareCertPath(), a.getTrustedHardwareCertPath());

        List<WrappedApplicationKey> aKeys = a.getWrappedApplicationKeys();
        List<WrappedApplicationKey> bKeys = b.getWrappedApplicationKeys();
        assertEquals(bKeys.size(), aKeys.size());
        for (int i = 0; i < aKeys.size(); i++) {
            assertWrappedApplicationKeysAreEqual(aKeys.get(i), bKeys.get(i));
        }

        List<KeyChainProtectionParams> aParams = a.getKeyChainProtectionParams();
        List<KeyChainProtectionParams> bParams = b.getKeyChainProtectionParams();
        assertEquals(bParams.size(), aParams.size());
        for (int i = 0; i < aParams.size(); i++) {
            assertKeyChainProtectionParamsAreEqual(aParams.get(i), bParams.get(i));
        }
    }

    private void assertWrappedApplicationKeysAreEqual(
            WrappedApplicationKey a, WrappedApplicationKey b) {
        assertEquals(b.getAlias(), a.getAlias());
        assertArrayEquals(b.getEncryptedKeyMaterial(), a.getEncryptedKeyMaterial());
    }

    private void assertKeyChainProtectionParamsAreEqual(
            KeyChainProtectionParams a, KeyChainProtectionParams b) {
        assertEquals(b.getUserSecretType(), a.getUserSecretType());
        assertEquals(b.getLockScreenUiFormat(), a.getLockScreenUiFormat());
        assertKeyDerivationParamsAreEqual(a.getKeyDerivationParams(), b.getKeyDerivationParams());
    }

    private void assertKeyDerivationParamsAreEqual(KeyDerivationParams a, KeyDerivationParams b) {
        assertEquals(b.getAlgorithm(), a.getAlgorithm());
        assertEquals(b.getMemoryDifficulty(), a.getMemoryDifficulty());
        assertArrayEquals(b.getSalt(), a.getSalt());
    }

    private static KeyChainSnapshot createTestKeyChainSnapshot(int snapshotVersion) {
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

        ArrayList<WrappedApplicationKey> keyList = new ArrayList<>();
        keyList.add(createKey(TEST_KEY_1_ALIAS, TEST_KEY_1_BYTES));
        keyList.add(createKey(TEST_KEY_2_ALIAS, TEST_KEY_2_BYTES));
        keyList.add(createKey(TEST_KEY_3_ALIAS, TEST_KEY_3_BYTES));

        try {
            return new KeyChainSnapshot.Builder()
                    .setCounterId(COUNTER_ID)
                    .setSnapshotVersion(snapshotVersion)
                    .setServerParams(SERVER_PARAMS)
                    .setMaxAttempts(MAX_ATTEMPTS)
                    .setEncryptedRecoveryKeyBlob(KEY_BLOB)
                    .setKeyChainProtectionParams(keyChainProtectionParamsList)
                    .setWrappedApplicationKeys(keyList)
                    .setTrustedHardwareCertPath(CERT_PATH)
                    .build();
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    private static WrappedApplicationKey createKey(String alias, byte[] bytes) {
        return new WrappedApplicationKey.Builder()
                .setAlias(alias)
                .setEncryptedKeyMaterial(bytes)
                .build();
    }
}
