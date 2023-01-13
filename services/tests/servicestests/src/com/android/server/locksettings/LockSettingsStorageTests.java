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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.FileUtils;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.platform.test.annotations.Presubmit;
import android.util.Log;
import android.util.Log.TerribleFailure;
import android.util.Log.TerribleFailureHandler;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * atest FrameworksServicesTests:LockSettingsStorageTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsStorageTests {
    private static final int SOME_USER_ID = 1034;
    private final byte[] PASSWORD = "thepassword".getBytes();

    public static final byte[] PAYLOAD = new byte[] {1, 2, -1, -2, 33};

    private LockSettingsStorageTestable mStorage;
    private File mStorageDir;
    private File mDb;
    @Rule
    public FakeSettingsProviderRule mSettingsRule = FakeSettingsProvider.rule();

    @Before
    public void setUp() throws Exception {
        final Context origContext = InstrumentationRegistry.getContext();

        mStorageDir = new File(origContext.getFilesDir(), "locksettings");
        mDb = InstrumentationRegistry.getContext().getDatabasePath("locksettings.db");

        assertTrue(mStorageDir.exists() || mStorageDir.mkdirs());
        assertTrue(FileUtils.deleteContents(mStorageDir));
        assertTrue(!mDb.exists() || mDb.delete());

        final UserManager mockUserManager = mock(UserManager.class);
        // User 2 is a profile of user 1.
        when(mockUserManager.getProfileParent(eq(2))).thenReturn(new UserInfo(1, "name", 0));
        // User 3 is a profile of user 0.
        when(mockUserManager.getProfileParent(eq(3))).thenReturn(new UserInfo(0, "name", 0));

        MockLockSettingsContext context = new MockLockSettingsContext(origContext,
                mSettingsRule.mockContentResolver(origContext), mockUserManager,
                mock(NotificationManager.class), mock(DevicePolicyManager.class),
                mock(StorageManager.class), mock(TrustManager.class), mock(KeyguardManager.class),
                mock(FingerprintManager.class), mock(FaceManager.class),
                mock(PackageManager.class));
        mStorage = new LockSettingsStorageTestable(context,
                new File(InstrumentationRegistry.getContext().getFilesDir(), "locksettings"));
        mStorage.setDatabaseOnCreateCallback(new LockSettingsStorage.Callback() {
                    @Override
                    public void initialize(SQLiteDatabase db) {
                        mStorage.writeKeyValue(db, "initializedKey", "initialValue", 0);
                    }
                });
    }

    @After
    public void tearDown() throws Exception {
        mStorage.closeDatabase();
    }

    @Test
    public void testKeyValue_InitializeWorked() {
        assertEquals("initialValue", mStorage.readKeyValue("initializedKey", "default", 0));
        mStorage.clearCache();
        assertEquals("initialValue", mStorage.readKeyValue("initializedKey", "default", 0));
    }

    @Test
    public void testKeyValue_WriteThenRead() {
        mStorage.writeKeyValue("key", "value", 0);
        assertEquals("value", mStorage.readKeyValue("key", "default", 0));
        mStorage.clearCache();
        assertEquals("value", mStorage.readKeyValue("key", "default", 0));
    }

    @Test
    public void testKeyValue_DefaultValue() {
        assertEquals("default", mStorage.readKeyValue("unititialized key", "default", 0));
        assertEquals("default2", mStorage.readKeyValue("unititialized key", "default2", 0));
    }

    @Test
    public void testKeyValue_ReadWriteConcurrency() {
        final CountDownLatch latch = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int threadId = i;
            threads.add(new Thread("testKeyValue_ReadWriteConcurrency_" + i) {
                @Override
                public void run() {
                    try {
                        latch.await();
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
            });
            threads.get(i).start();
        }
        mStorage.writeKeyValue("key", "initialValue", 0);
        latch.countDown();
        joinAll(threads, 10000);
        assertEquals('5', mStorage.readKeyValue("key", "default", 0).charAt(0));
        mStorage.clearCache();
        assertEquals('5', mStorage.readKeyValue("key", "default", 0).charAt(0));
    }

    // Test that readKeyValue() doesn't pollute the cache when run concurrently with removeKey().
    @Test
    @SuppressWarnings("AssertionFailureIgnored") // intentional try-catch of AssertionError
    public void testKeyValue_ReadRemoveConcurrency() {
        final int numThreads = 2;
        final int numIterations = 50;
        final CyclicBarrier barrier = new CyclicBarrier(numThreads);
        final List<Thread> threads = new ArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int threadId = 0; threadId < numThreads; threadId++) {
            final boolean isWriter = (threadId == 0);
            threads.add(new Thread("testKeyValue_ReadRemoveConcurrency_" + threadId) {
                @Override
                public void run() {
                    try {
                        for (int iter = 0; iter < numIterations; iter++) {
                            if (isWriter) {
                                mStorage.writeKeyValue("key", "value", 0);
                                mStorage.clearCache();
                            }
                            barrier.await();
                            if (isWriter) {
                                mStorage.removeKey("key", 0);
                            } else {
                                mStorage.readKeyValue("key", "default", 0);
                            }
                            barrier.await();
                            try {
                                assertEquals("default", mStorage.readKeyValue("key", "default", 0));
                            } catch (AssertionError e) {
                                failure.compareAndSet(null, e);
                            }
                            barrier.await();
                        }
                    } catch (InterruptedException | BrokenBarrierException e) {
                        failure.compareAndSet(null, e);
                        return;
                    }
                }
            });
            threads.get(threadId).start();
        }
        joinAll(threads, 60000);
        assertNull(failure.get());
    }

    @Test
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

    @Test
    public void testNullKey() {
        mStorage.setString(null, "value", 0);

        // Verify that this doesn't throw an exception.
        assertEquals("value", mStorage.readKeyValue(null, null, 0));

        // The read that happens as part of prefetchUser shouldn't throw an exception either.
        mStorage.clearCache();
        mStorage.prefetchUser(0);

        assertEquals("value", mStorage.readKeyValue(null, null, 0));
    }

    @Test
    public void testRemoveUser() {
        mStorage.writeKeyValue("key", "value", 0);
        mStorage.writeKeyValue("key", "value", 1);

        mStorage.removeUser(0);

        assertEquals("value", mStorage.readKeyValue("key", "default", 1));
        assertEquals("default", mStorage.readKeyValue("key", "default", 0));
    }

    @Test
    public void testProfileLock_ReadWriteChildProfileLock() {
        assertFalse(mStorage.hasChildProfileLock(20));
        mStorage.writeChildProfileLock(20, PASSWORD);
        assertArrayEquals(PASSWORD, mStorage.readChildProfileLock(20));
        assertTrue(mStorage.hasChildProfileLock(20));
        mStorage.clearCache();
        assertArrayEquals(PASSWORD, mStorage.readChildProfileLock(20));
        assertTrue(mStorage.hasChildProfileLock(20));
    }

    @Test
    public void testPrefetch() {
        mStorage.writeKeyValue("key1", "value1", 0);
        mStorage.writeKeyValue("key2", "value2", 0);

        mStorage.clearCache();

        assertFalse(mStorage.isUserPrefetched(0));
        assertFalse(mStorage.isKeyValueCached("key1", 0));
        assertFalse(mStorage.isKeyValueCached("key2", 0));

        mStorage.prefetchUser(0);

        assertTrue(mStorage.isUserPrefetched(0));
        assertTrue(mStorage.isKeyValueCached("key1", 0));
        assertTrue(mStorage.isKeyValueCached("key2", 0));
        assertEquals("value1", mStorage.readKeyValue("key1", "default", 0));
        assertEquals("value2", mStorage.readKeyValue("key2", "default", 0));
    }

    @Test
    public void testFileLocation_Owner() {
        LockSettingsStorage storage = new LockSettingsStorage(InstrumentationRegistry.getContext());

        assertEquals(new File("/data/system/gatekeeper.profile.key"),
                storage.getChildProfileLockFile(0));
    }

    @Test
    public void testFileLocation_SecondaryUser() {
        LockSettingsStorage storage = new LockSettingsStorage(InstrumentationRegistry.getContext());

        assertEquals(new File("/data/system/users/1/gatekeeper.profile.key"),
                storage.getChildProfileLockFile(1));
    }

    @Test
    public void testFileLocation_ProfileToSecondary() {
        LockSettingsStorage storage = new LockSettingsStorage(InstrumentationRegistry.getContext());

        assertEquals(new File("/data/system/users/2/gatekeeper.profile.key"),
                storage.getChildProfileLockFile(2));
    }

    @Test
    public void testFileLocation_ProfileToOwner() {
        LockSettingsStorage storage = new LockSettingsStorage(InstrumentationRegistry.getContext());

        assertEquals(new File("/data/system/users/3/gatekeeper.profile.key"),
                storage.getChildProfileLockFile(3));
    }

    @Test
    public void testSyntheticPasswordState() {
        final byte[] data = {1,2,3,4};
        mStorage.writeSyntheticPasswordState(10, 1234L, "state", data);
        assertArrayEquals(data, mStorage.readSyntheticPasswordState(10, 1234L, "state"));
        assertEquals(null, mStorage.readSyntheticPasswordState(0, 1234L, "state"));

        mStorage.deleteSyntheticPasswordState(10, 1234L, "state");
        assertEquals(null, mStorage.readSyntheticPasswordState(10, 1234L, "state"));
    }

    @Test
    public void testPersistentDataBlock_unavailable() {
        mStorage.mPersistentDataBlockManager = null;

        assertSame(PersistentData.NONE, mStorage.readPersistentDataBlock());
    }

    @Test
    public void testPersistentDataBlock_empty() {
        mStorage.mPersistentDataBlockManager = mock(PersistentDataBlockManagerInternal.class);

        assertSame(PersistentData.NONE, mStorage.readPersistentDataBlock());
    }

    @Test
    public void testPersistentDataBlock_withData() {
        mStorage.mPersistentDataBlockManager = mock(PersistentDataBlockManagerInternal.class);
        when(mStorage.mPersistentDataBlockManager.getFrpCredentialHandle())
                .thenReturn(PersistentData.toBytes(PersistentData.TYPE_SP_WEAVER, SOME_USER_ID,
                        DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, PAYLOAD));

        PersistentData data = mStorage.readPersistentDataBlock();

        assertEquals(PersistentData.TYPE_SP_WEAVER, data.type);
        assertEquals(SOME_USER_ID, data.userId);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, data.qualityForUi);
        assertArrayEquals(PAYLOAD, data.payload);
    }

    @Test
    public void testPersistentDataBlock_exception() {
        mStorage.mPersistentDataBlockManager = mock(PersistentDataBlockManagerInternal.class);
        when(mStorage.mPersistentDataBlockManager.getFrpCredentialHandle())
                .thenThrow(new IllegalStateException("oops"));
        assertSame(PersistentData.NONE, mStorage.readPersistentDataBlock());
    }

    @Test
    public void testPersistentData_serializeUnserialize() {
        byte[] serialized = PersistentData.toBytes(PersistentData.TYPE_SP_GATEKEEPER, SOME_USER_ID,
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, PAYLOAD);
        PersistentData deserialized = PersistentData.fromBytes(serialized);

        assertEquals(PersistentData.TYPE_SP_GATEKEEPER, deserialized.type);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, deserialized.qualityForUi);
        assertArrayEquals(PAYLOAD, deserialized.payload);
    }

    @Test
    public void testPersistentData_unserializeNull() {
        PersistentData deserialized = PersistentData.fromBytes(null);
        assertSame(PersistentData.NONE, deserialized);
    }

    @Test
    public void testPersistentData_unserializeEmptyArray() {
        PersistentData deserialized = PersistentData.fromBytes(new byte[0]);
        assertSame(PersistentData.NONE, deserialized);
    }

    @Test
    public void testPersistentData_unserializeInvalid() {
        assertNotNull(suppressAndReturnWtf(() -> {
            PersistentData deserialized = PersistentData.fromBytes(new byte[]{5});
            assertSame(PersistentData.NONE, deserialized);
        }));
    }

    @Test
    public void testPersistentData_unserialize_version1() {
        // This test ensures that we can read serialized VERSION_1 PersistentData even if we change
        // the wire format in the future.
        byte[] serializedVersion1 = new byte[] {
                1, /* PersistentData.VERSION_1 */
                1, /* PersistentData.TYPE_SP_GATEKEEPER */
                0x00, 0x00, 0x04, 0x0A,  /* SOME_USER_ID */
                0x00, 0x03, 0x00, 0x00,  /* PASSWORD_NUMERIC_COMPLEX */
                1, 2, -1, -2, 33, /* PAYLOAD */
        };
        PersistentData deserialized = PersistentData.fromBytes(serializedVersion1);
        assertEquals(PersistentData.TYPE_SP_GATEKEEPER, deserialized.type);
        assertEquals(SOME_USER_ID, deserialized.userId);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX,
                deserialized.qualityForUi);
        assertArrayEquals(PAYLOAD, deserialized.payload);

        // Make sure the constants we use on the wire do not change.
        assertEquals(0, PersistentData.TYPE_NONE);
        assertEquals(1, PersistentData.TYPE_SP_GATEKEEPER);
        assertEquals(2, PersistentData.TYPE_SP_WEAVER);
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("expected:<" + Arrays.toString(expected) +
                    "> but was:<" + Arrays.toString(actual) + ">");
        }
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

    private static void joinAll(List<Thread> threads, long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        for (Thread t : threads) {
            try {
                t.join(deadline - SystemClock.uptimeMillis());
                if (t.isAlive()) {
                    t.interrupt();
                    throw new RuntimeException(
                            "Joining " + t + " timed out. Stack: \n" + getStack(t));
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while joining " + t, e);
            }
        }
    }

    private static String getStack(Thread t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append('\n');
        for (StackTraceElement ste : t.getStackTrace()) {
            sb.append("\tat ").append(ste.toString()).append('\n');
        }
        return sb.toString();
    }
}
