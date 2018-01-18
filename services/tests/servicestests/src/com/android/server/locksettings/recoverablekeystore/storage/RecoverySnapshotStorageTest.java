package com.android.server.locksettings.recoverablekeystore.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.security.keystore.KeychainSnapshot;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RecoverySnapshotStorageTest {

    private final RecoverySnapshotStorage mRecoverySnapshotStorage = new RecoverySnapshotStorage();

    @Test
    public void get_isNullForNonExistentSnapshot() {
        assertNull(mRecoverySnapshotStorage.get(1000));
    }

    @Test
    public void get_returnsSetSnapshot() {
        int userId = 1000;
        KeychainSnapshot keychainSnapshot = new KeychainSnapshot(
                /*snapshotVersion=*/ 1,
                new ArrayList<>(),
                new ArrayList<>(),
                new byte[0]);
        mRecoverySnapshotStorage.put(userId, keychainSnapshot);

        assertEquals(keychainSnapshot, mRecoverySnapshotStorage.get(userId));
    }

    @Test
    public void remove_removesSnapshots() {
        int userId = 1000;
        KeychainSnapshot keychainSnapshot = new KeychainSnapshot(
                /*snapshotVersion=*/ 1,
                new ArrayList<>(),
                new ArrayList<>(),
                new byte[0]);
        mRecoverySnapshotStorage.put(userId, keychainSnapshot);

        mRecoverySnapshotStorage.remove(userId);

        assertNull(mRecoverySnapshotStorage.get(1000));
    }
}
