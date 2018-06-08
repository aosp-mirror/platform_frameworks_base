/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.locksettings;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.pm.UserInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.FileUtils;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.test.AndroidTestCase;
import android.util.Log;
import android.util.Log.TerribleFailure;
import android.util.Log.TerribleFailureHandler;

import com.android.internal.widget.LockPatternUtils;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.locksettings.LockSettingsStorage.CredentialHash;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * runtest frameworks-services -c com.android.server.locksettings.LockSettingsStorageTests
 */
public class LockSettingsStorageTests extends AndroidTestCase {
    private static final int SOME_USER_ID = 1034;
    private final byte[] PASSWORD_0 = "thepassword0".getBytes();
    private final byte[] PASSWORD_1 = "password1".getBytes();
    private final byte[] PATTERN_0 = "123654".getBytes();
    private final byte[] PATTERN_1 = "147852369".getBytes();

    public static final byte[] PAYLOAD = new byte[] {1, 2, -1, -2, 33};

    LockSettingsStorageTestable mStorage;
    File mStorageDir;

    private File mDb;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mStorageDir = new File(getContext().getFilesDir(), "locksettings");
        mDb = getContext().getDatabasePath("locksettings.db");

        assertTrue(mStorageDir.exists() || mStorageDir.mkdirs());
        assertTrue(FileUtils.deleteContents(mStorageDir));
        assertTrue(!mDb.exists() || mDb.delete());

        final UserManager mockUserManager = mock(UserManager.class);
        // User 2 is a profile of user 1.
        when(mockUserManager.getProfileParent(eq(2))).thenReturn(new UserInfo(1, "name", 0));
        // User 3 is a profile of user 0.
        when(mockUserManager.getProfileParent(eq(3))).thenReturn(new UserInfo(0, "name", 0));

        MockLockSettingsContext context = new MockLockSettingsContext(getContext(), mockUserManager,
                mock(NotificationManager.class), mock(DevicePolicyManager.class),
                mock(StorageManager.class), mock(TrustManager.class), mock(KeyguardManager.class));
        mStorage = new LockSettingsStorageTestable(context,
                new File(getContext().getFilesDir(), "locksettings"));
        mStorage.setDatabaseOnCreateCallback(new LockSettingsStorage.Callback() {
                    @Override
                    public void initialize(SQLiteDatabase db) {
                        mStorage.writeKeyValue(db, "initializedKey", "initialValue", 0);
                    }
                });
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mStorage.closeDatabase();
    }

    public void testKeyValue_InitializeWorked() {
        assertEquals("initialValue", mStorage.readKeyValue("initializedKey", "default", 0));
        mStorage.clearCache();
        assertEquals("initialValue", mStorage.readKeyValue("initializedKey", "default", 0));
    }

    public void testKeyValue_WriteThenRead() {
        mStorage.writeKeyValue("key", "value", 0);
        assertEquals("value", mStorage.readKeyValue("key", "default", 0));
        mStorage.clearCache();
        assertEquals("value", mStorage.readKeyValue("key", "default", 0));
    }

    public void testKeyValue_DefaultValue() {
        assertEquals("default", mStorage.readKeyValue("unititialized key", "default", 0));
        assertEquals("default2", mStorage.readKeyValue("unititialized key", "default2", 0));
    }

    public void testKeyValue_Concurrency() {
        final Object monitor = new Object();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int threadId = i;
            threads.add(new Thread() {
                @Override
                public void run() {
                    synchronized (monitor) {
                        try {
                            monitor.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                        mStorage.writeKeyValue("key", "1 from thread " + threadId, 0);
                        mStorage.readKeyValue("key", "default", 0);
                        mStorage.writeKeyValue("key", "2 from thread " + threadId, 0);
                        mStorage.readKeyValue("key", "default", 0);
                        mStorage.writeKeyValue("key", "3 from thread " + threadId, 0);
                        mStorage.readKeyValue("key", "default", 0);
                        mStorage.writeKeyValue("key", "4 from thread " + threadId, 0);
                        mStorage.readKeyValue("key", "default", 0);
                        mStorage.writeKeyValue("key", "5 from thread " + threadId, 0);
                        mStorage.readKeyValue("key", "default", 0);
                    }
                }
            });
            threads.get(i).start();
        }
        mStorage.writeKeyValue("key", "initalValue", 0);
        synchronized (monitor) {
            monitor.notifyAll();
        }
        for (int i = 0; i < threads.size(); i++) {
            try {
                threads.get(i).join();
            } catch (InterruptedException e) {
            }
        }
        assertEquals('5', mStorage.readKeyValue("key", "default", 0).charAt(0));
        mStorage.clearCache();
        assertEquals('5', mStorage.readKeyValue("key", "default", 0).charAt(0));
    }

    public void testKeyValue_CacheStarvedWriter() {
        final CountDownLatch latch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int threadId = i;
            threads.add(new Thread() {
                @Override
                public void run() {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (threadId == 50) {
                        mStorage.writeKeyValue("starvedWriterKey", "value", 0);
                    } else {
                        mStorage.readKeyValue("starvedWriterKey", "default", 0);
                    }
                }
            });
            threads.get(i).start();
        }
        latch.countDown();
        for (int i = 0; i < threads.size(); i++) {
            try {
                threads.get(i).join();
            } catch (InterruptedException e) {
            }
        }
        String cached = mStorage.readKeyValue("key", "default", 0);
        mStorage.clearCache();
        String storage = mStorage.readKeyValue("key", "default", 0);
        assertEquals("Cached value didn't match stored value", storage, cached);
    }

    public void testRemoveUser() {
        mStorage.writeKeyValue("key", "value", 0);
        writePasswordBytes(PASSWORD_0, 0);
        writePatternBytes(PATTERN_0, 0);

        mStorage.writeKeyValue("key", "value", 1);
        writePasswordBytes(PASSWORD_1, 1);
        writePatternBytes(PATTERN_1, 1);

        mStorage.removeUser(0);

        assertEquals("value", mStorage.readKeyValue("key", "default", 1));
        assertEquals("default", mStorage.readKeyValue("key", "default", 0));
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_NONE, mStorage.readCredentialHash(0).type);
        assertPatternBytes(PATTERN_1, 1);
    }

    public void testCredential_Default() {
        assertEquals(mStorage.readCredentialHash(0).type, LockPatternUtils.CREDENTIAL_TYPE_NONE);
    }

    public void testPassword_Write() {
        writePasswordBytes(PASSWORD_0, 0);

        assertPasswordBytes(PASSWORD_0, 0);
        mStorage.clearCache();
        assertPasswordBytes(PASSWORD_0, 0);
    }

    public void testPassword_WriteProfileWritesParent() {
        writePasswordBytes(PASSWORD_0, 1);
        writePasswordBytes(PASSWORD_1, 2);

        assertPasswordBytes(PASSWORD_0, 1);
        assertPasswordBytes(PASSWORD_1, 2);
        mStorage.clearCache();
        assertPasswordBytes(PASSWORD_0, 1);
        assertPasswordBytes(PASSWORD_1, 2);
    }

    public void testLockType_WriteProfileWritesParent() {
        writePasswordBytes(PASSWORD_0, 10);
        writePatternBytes(PATTERN_0, 20);

        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                mStorage.readCredentialHash(10).type);
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                mStorage.readCredentialHash(20).type);
        mStorage.clearCache();
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                mStorage.readCredentialHash(10).type);
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                mStorage.readCredentialHash(20).type);
    }

    public void testPassword_WriteParentWritesProfile() {
        writePasswordBytes(PASSWORD_0, 2);
        writePasswordBytes(PASSWORD_1, 1);

        assertPasswordBytes(PASSWORD_1, 1);
        assertPasswordBytes(PASSWORD_0, 2);
        mStorage.clearCache();
        assertPasswordBytes(PASSWORD_1, 1);
        assertPasswordBytes(PASSWORD_0, 2);
    }

    public void testProfileLock_ReadWriteChildProfileLock() {
        assertFalse(mStorage.hasChildProfileLock(20));
        mStorage.writeChildProfileLock(20, PASSWORD_0);
        assertArrayEquals(PASSWORD_0, mStorage.readChildProfileLock(20));
        assertTrue(mStorage.hasChildProfileLock(20));
        mStorage.clearCache();
        assertArrayEquals(PASSWORD_0, mStorage.readChildProfileLock(20));
        assertTrue(mStorage.hasChildProfileLock(20));
    }

    public void testPattern_Write() {
        writePatternBytes(PATTERN_0, 0);

        assertPatternBytes(PATTERN_0, 0);
        mStorage.clearCache();
        assertPatternBytes(PATTERN_0, 0);
    }

    public void testPattern_WriteProfileWritesParent() {
        writePatternBytes(PATTERN_0, 1);
        writePatternBytes(PATTERN_1, 2);

        assertPatternBytes(PATTERN_0, 1);
        assertPatternBytes(PATTERN_1, 2);
        mStorage.clearCache();
        assertPatternBytes(PATTERN_0, 1);
        assertPatternBytes(PATTERN_1, 2);
    }

    public void testPattern_WriteParentWritesProfile() {
        writePatternBytes(PATTERN_1, 2);
        writePatternBytes(PATTERN_0, 1);

        assertPatternBytes(PATTERN_0, 1);
        assertPatternBytes(PATTERN_1, 2);
        mStorage.clearCache();
        assertPatternBytes(PATTERN_0, 1);
        assertPatternBytes(PATTERN_1, 2);
    }

    public void testPrefetch() {
        mStorage.writeKeyValue("key", "toBeFetched", 0);
        writePatternBytes(PATTERN_0, 0);

        mStorage.clearCache();
        mStorage.prefetchUser(0);

        assertEquals("toBeFetched", mStorage.readKeyValue("key", "default", 0));
        assertPatternBytes(PATTERN_0, 0);
    }

    public void testFileLocation_Owner() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext());

        assertEquals("/data/system/gesture.key", storage.getLegacyLockPatternFilename(0));
        assertEquals("/data/system/password.key", storage.getLegacyLockPasswordFilename(0));
        assertEquals("/data/system/gatekeeper.pattern.key", storage.getLockPatternFilename(0));
        assertEquals("/data/system/gatekeeper.password.key", storage.getLockPasswordFilename(0));
    }

    public void testFileLocation_SecondaryUser() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext());

        assertEquals("/data/system/users/1/gatekeeper.pattern.key", storage.getLockPatternFilename(1));
        assertEquals("/data/system/users/1/gatekeeper.password.key", storage.getLockPasswordFilename(1));
    }

    public void testFileLocation_ProfileToSecondary() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext());

        assertEquals("/data/system/users/2/gatekeeper.pattern.key", storage.getLockPatternFilename(2));
        assertEquals("/data/system/users/2/gatekeeper.password.key", storage.getLockPasswordFilename(2));
    }

    public void testFileLocation_ProfileToOwner() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext());

        assertEquals("/data/system/users/3/gatekeeper.pattern.key", storage.getLockPatternFilename(3));
        assertEquals("/data/system/users/3/gatekeeper.password.key", storage.getLockPasswordFilename(3));
    }

    public void testSyntheticPasswordState() {
        final byte[] data = {1,2,3,4};
        mStorage.writeSyntheticPasswordState(10, 1234L, "state", data);
        assertArrayEquals(data, mStorage.readSyntheticPasswordState(10, 1234L, "state"));
        assertEquals(null, mStorage.readSyntheticPasswordState(0, 1234L, "state"));

        mStorage.deleteSyntheticPasswordState(10, 1234L, "state");
        assertEquals(null, mStorage.readSyntheticPasswordState(10, 1234L, "state"));
    }

    public void testPersistentDataBlock_unavailable() {
        mStorage.mPersistentDataBlock = null;

        assertSame(PersistentData.NONE, mStorage.readPersistentDataBlock());
    }

    public void testPersistentDataBlock_empty() {
        mStorage.mPersistentDataBlock = mock(PersistentDataBlockManagerInternal.class);

        assertSame(PersistentData.NONE, mStorage.readPersistentDataBlock());
    }

    public void testPersistentDataBlock_withData() {
        mStorage.mPersistentDataBlock = mock(PersistentDataBlockManagerInternal.class);
        when(mStorage.mPersistentDataBlock.getFrpCredentialHandle())
                .thenReturn(PersistentData.toBytes(PersistentData.TYPE_SP_WEAVER, SOME_USER_ID,
                        DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, PAYLOAD));

        PersistentData data = mStorage.readPersistentDataBlock();

        assertEquals(PersistentData.TYPE_SP_WEAVER, data.type);
        assertEquals(SOME_USER_ID, data.userId);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, data.qualityForUi);
        assertArrayEquals(PAYLOAD, data.payload);
    }

    public void testPersistentDataBlock_exception() {
        mStorage.mPersistentDataBlock = mock(PersistentDataBlockManagerInternal.class);
        when(mStorage.mPersistentDataBlock.getFrpCredentialHandle())
                .thenThrow(new IllegalStateException("oops"));
        assertSame(PersistentData.NONE, mStorage.readPersistentDataBlock());
    }

    public void testPersistentData_serializeUnserialize() {
        byte[] serialized = PersistentData.toBytes(PersistentData.TYPE_SP, SOME_USER_ID,
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, PAYLOAD);
        PersistentData deserialized = PersistentData.fromBytes(serialized);

        assertEquals(PersistentData.TYPE_SP, deserialized.type);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, deserialized.qualityForUi);
        assertArrayEquals(PAYLOAD, deserialized.payload);
    }

    public void testPersistentData_unserializeNull() {
        PersistentData deserialized = PersistentData.fromBytes(null);
        assertSame(PersistentData.NONE, deserialized);
    }

    public void testPersistentData_unserializeEmptyArray() {
        PersistentData deserialized = PersistentData.fromBytes(new byte[0]);
        assertSame(PersistentData.NONE, deserialized);
    }

    public void testPersistentData_unserializeInvalid() {
        assertNotNull(suppressAndReturnWtf(() -> {
            PersistentData deserialized = PersistentData.fromBytes(new byte[]{5});
            assertSame(PersistentData.NONE, deserialized);
        }));
    }

    public void testPersistentData_unserialize_version1() {
        // This test ensures that we can read serialized VERSION_1 PersistentData even if we change
        // the wire format in the future.
        byte[] serializedVersion1 = new byte[] {
                1, /* PersistentData.VERSION_1 */
                1, /* PersistentData.TYPE_SP */
                0x00, 0x00, 0x04, 0x0A,  /* SOME_USER_ID */
                0x00, 0x03, 0x00, 0x00,  /* PASSWORD_NUMERIC_COMPLEX */
                1, 2, -1, -2, 33, /* PAYLOAD */
        };
        PersistentData deserialized = PersistentData.fromBytes(serializedVersion1);
        assertEquals(PersistentData.TYPE_SP, deserialized.type);
        assertEquals(SOME_USER_ID, deserialized.userId);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX,
                deserialized.qualityForUi);
        assertArrayEquals(PAYLOAD, deserialized.payload);

        // Make sure the constants we use on the wire do not change.
        assertEquals(0, PersistentData.TYPE_NONE);
        assertEquals(1, PersistentData.TYPE_SP);
        assertEquals(2, PersistentData.TYPE_SP_WEAVER);
    }

    public void testCredentialHash_serializeUnserialize() {
        byte[] serialized = CredentialHash.create(
                PAYLOAD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD).toBytes();
        CredentialHash deserialized = CredentialHash.fromBytes(serialized);

        assertEquals(CredentialHash.VERSION_GATEKEEPER, deserialized.version);
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, deserialized.type);
        assertArrayEquals(PAYLOAD, deserialized.hash);
        assertFalse(deserialized.isBaseZeroPattern);
    }

    public void testCredentialHash_unserialize_versionGatekeeper() {
        // This test ensures that we can read serialized VERSION_GATEKEEPER CredentialHashes
        // even if we change the wire format in the future.
        byte[] serialized = new byte[] {
                1, /* VERSION_GATEKEEPER */
                2, /* CREDENTIAL_TYPE_PASSWORD */
                0, 0, 0, 5, /* hash length */
                1, 2, -1, -2, 33, /* hash */
        };
        CredentialHash deserialized = CredentialHash.fromBytes(serialized);

        assertEquals(CredentialHash.VERSION_GATEKEEPER, deserialized.version);
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, deserialized.type);
        assertArrayEquals(PAYLOAD, deserialized.hash);
        assertFalse(deserialized.isBaseZeroPattern);

        // Make sure the constants we use on the wire do not change.
        assertEquals(-1, LockPatternUtils.CREDENTIAL_TYPE_NONE);
        assertEquals(1, LockPatternUtils.CREDENTIAL_TYPE_PATTERN);
        assertEquals(2, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD);
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("expected:<" + Arrays.toString(expected) +
                    "> but was:<" + Arrays.toString(actual) + ">");
        }
    }

    private void writePasswordBytes(byte[] password, int userId) {
        mStorage.writeCredentialHash(CredentialHash.create(
                password, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD), userId);
    }

    private void writePatternBytes(byte[] pattern, int userId) {
        mStorage.writeCredentialHash(CredentialHash.create(
                pattern, LockPatternUtils.CREDENTIAL_TYPE_PATTERN), userId);
    }

    private void assertPasswordBytes(byte[] password, int userId) {
        CredentialHash cred = mStorage.readCredentialHash(userId);
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, cred.type);
        assertArrayEquals(password, cred.hash);
    }

    private void assertPatternBytes(byte[] pattern, int userId) {
        CredentialHash cred = mStorage.readCredentialHash(userId);
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PATTERN, cred.type);
        assertArrayEquals(pattern, cred.hash);
    }

    /**
     * Suppresses reporting of the WTF to system_server, so we don't pollute the dropbox with
     * intentionally caused WTFs.
     */
    private TerribleFailure suppressAndReturnWtf(Runnable r) {
        TerribleFailure[] captured = new TerribleFailure[1];
        TerribleFailureHandler prevWtfHandler = Log.setWtfHandler((t, w, s) -> captured[0] = w);
        try {
            r.run();
        } finally {
            Log.setWtfHandler(prevWtfHandler);
        }
        return captured[0];
    }
}
