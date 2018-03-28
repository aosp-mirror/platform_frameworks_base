package com.android.server.locksettings.recoverablekeystore.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.security.keystore.recovery.KeyChainSnapshot;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.TestData;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.CertificateException;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySnapshotStorageTest {
    private static final KeyChainSnapshot MINIMAL_KEYCHAIN_SNAPSHOT =
            createMinimalKeyChainSnapshot();

    private final RecoverySnapshotStorage mRecoverySnapshotStorage = new RecoverySnapshotStorage();

    @Test
    public void get_isNullForNonExistentSnapshot() {
        assertNull(mRecoverySnapshotStorage.get(1000));
    }

    @Test
    public void get_returnsSetSnapshot() {
        int userId = 1000;

        mRecoverySnapshotStorage.put(userId, MINIMAL_KEYCHAIN_SNAPSHOT);

        assertEquals(MINIMAL_KEYCHAIN_SNAPSHOT, mRecoverySnapshotStorage.get(userId));
    }

    @Test
    public void remove_removesSnapshots() {
        int userId = 1000;

        mRecoverySnapshotStorage.put(userId, MINIMAL_KEYCHAIN_SNAPSHOT);
        mRecoverySnapshotStorage.remove(userId);

        assertNull(mRecoverySnapshotStorage.get(1000));
    }

    private static KeyChainSnapshot createMinimalKeyChainSnapshot() {
        try {
            return new KeyChainSnapshot.Builder()
                    .setCounterId(1)
                    .setSnapshotVersion(1)
                    .setServerParams(new byte[0])
                    .setMaxAttempts(10)
                    .setEncryptedRecoveryKeyBlob(new byte[0])
                    .setKeyChainProtectionParams(new ArrayList<>())
                    .setWrappedApplicationKeys(new ArrayList<>())
                    .setTrustedHardwareCertPath(TestData.CERT_PATH_1)
                    .build();
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }
}
