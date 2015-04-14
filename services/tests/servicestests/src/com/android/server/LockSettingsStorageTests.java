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

package com.android.server;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.FileUtils;
import android.os.UserManager;
import android.test.AndroidTestCase;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class LockSettingsStorageTests extends AndroidTestCase {
    LockSettingsStorage mStorage;
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

        final Context ctx = getContext();
        setContext(new ContextWrapper(ctx) {
            @Override
            public Object getSystemService(String name) {
                if (USER_SERVICE.equals(name)) {
                    return new UserManager(ctx, null) {
                        @Override
                        public UserInfo getProfileParent(int userHandle) {
                            if (userHandle == 2) {
                                // User 2 is a profile of user 1.
                                return new UserInfo(1, "name", 0);
                            }
                            if (userHandle == 3) {
                                // User 3 is a profile of user 0.
                                return new UserInfo(0, "name", 0);
                            }
                            return null;
                        }
                    };
                }
                return super.getSystemService(name);
            }
        });

        mStorage = new LockSettingsStorage(getContext(), new LockSettingsStorage.Callback() {
            @Override
            public void initialize(SQLiteDatabase db) {
                mStorage.writeKeyValue(db, "initializedKey", "initialValue", 0);
            }
        }) {
            @Override
            String getLockPatternFilename(int userId) {
                return new File(mStorageDir,
                        super.getLockPatternFilename(userId).replace('/', '-')).getAbsolutePath();
            }

            @Override
            String getLockPasswordFilename(int userId) {
                return new File(mStorageDir,
                        super.getLockPasswordFilename(userId).replace('/', '-')).getAbsolutePath();
            }
        };
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
        mStorage.writePasswordHash(new byte[]{1}, 0);
        mStorage.writePatternHash(new byte[]{2}, 0);

        mStorage.writeKeyValue("key", "value", 1);
        mStorage.writePasswordHash(new byte[]{1}, 1);
        mStorage.writePatternHash(new byte[]{2}, 1);

        mStorage.removeUser(0);

        assertEquals("value", mStorage.readKeyValue("key", "default", 1));
        assertEquals("default", mStorage.readKeyValue("key", "default", 0));
        assertNotNull(mStorage.readPasswordHash(1));
        assertNull(mStorage.readPasswordHash(0));
        assertNotNull(mStorage.readPatternHash(1));
        assertNull(mStorage.readPatternHash(0));
    }

    public void testPassword_Default() {
        assertNull(mStorage.readPasswordHash(0));
    }

    public void testPassword_Write() {
        mStorage.writePasswordHash("thepassword".getBytes(), 0);

        assertArrayEquals("thepassword".getBytes(), mStorage.readPasswordHash(0).hash);
        mStorage.clearCache();
        assertArrayEquals("thepassword".getBytes(), mStorage.readPasswordHash(0).hash);
    }

    public void testPassword_WriteProfileWritesParent() {
        mStorage.writePasswordHash("parentpasswordd".getBytes(), 1);
        mStorage.writePasswordHash("profilepassword".getBytes(), 2);

        assertArrayEquals("profilepassword".getBytes(), mStorage.readPasswordHash(1).hash);
        assertArrayEquals("profilepassword".getBytes(), mStorage.readPasswordHash(2).hash);
        mStorage.clearCache();
        assertArrayEquals("profilepassword".getBytes(), mStorage.readPasswordHash(1).hash);
        assertArrayEquals("profilepassword".getBytes(), mStorage.readPasswordHash(2).hash);
    }

    public void testPassword_WriteParentWritesProfile() {
        mStorage.writePasswordHash("profilepassword".getBytes(), 2);
        mStorage.writePasswordHash("parentpasswordd".getBytes(), 1);

        assertArrayEquals("parentpasswordd".getBytes(), mStorage.readPasswordHash(1).hash);
        assertArrayEquals("parentpasswordd".getBytes(), mStorage.readPasswordHash(2).hash);
        mStorage.clearCache();
        assertArrayEquals("parentpasswordd".getBytes(), mStorage.readPasswordHash(1).hash);
        assertArrayEquals("parentpasswordd".getBytes(), mStorage.readPasswordHash(2).hash);
    }

    public void testPattern_Default() {
        assertNull(mStorage.readPasswordHash(0));
    }

    public void testPattern_Write() {
        mStorage.writePatternHash("thepattern".getBytes(), 0);

        assertArrayEquals("thepattern".getBytes(), mStorage.readPatternHash(0).hash);
        mStorage.clearCache();
        assertArrayEquals("thepattern".getBytes(), mStorage.readPatternHash(0).hash);
    }

    public void testPattern_WriteProfileWritesParent() {
        mStorage.writePatternHash("parentpatternn".getBytes(), 1);
        mStorage.writePatternHash("profilepattern".getBytes(), 2);

        assertArrayEquals("profilepattern".getBytes(), mStorage.readPatternHash(1).hash);
        assertArrayEquals("profilepattern".getBytes(), mStorage.readPatternHash(2).hash);
        mStorage.clearCache();
        assertArrayEquals("profilepattern".getBytes(), mStorage.readPatternHash(1).hash);
        assertArrayEquals("profilepattern".getBytes(), mStorage.readPatternHash(2).hash);
    }

    public void testPattern_WriteParentWritesProfile() {
        mStorage.writePatternHash("profilepattern".getBytes(), 2);
        mStorage.writePatternHash("parentpatternn".getBytes(), 1);

        assertArrayEquals("parentpatternn".getBytes(), mStorage.readPatternHash(1).hash);
        assertArrayEquals("parentpatternn".getBytes(), mStorage.readPatternHash(2).hash);
        mStorage.clearCache();
        assertArrayEquals("parentpatternn".getBytes(), mStorage.readPatternHash(1).hash);
        assertArrayEquals("parentpatternn".getBytes(), mStorage.readPatternHash(2).hash);
    }

    public void testPrefetch() {
        mStorage.writeKeyValue("key", "toBeFetched", 0);
        mStorage.writePatternHash("pattern".getBytes(), 0);
        mStorage.writePasswordHash("password".getBytes(), 0);

        mStorage.clearCache();
        mStorage.prefetchUser(0);

        assertEquals("toBeFetched", mStorage.readKeyValue("key", "default", 0));
        assertArrayEquals("pattern".getBytes(), mStorage.readPatternHash(0).hash);
        assertArrayEquals("password".getBytes(), mStorage.readPasswordHash(0).hash);
    }

    public void testFileLocation_Owner() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext(), null);

        assertEquals("/data/system/gesture.key", storage.getLockPatternFilename(0));
        assertEquals("/data/system/password.key", storage.getLockPasswordFilename(0));
    }

    public void testFileLocation_SecondaryUser() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext(), null);

        assertEquals("/data/system/users/1/gesture.key", storage.getLockPatternFilename(1));
        assertEquals("/data/system/users/1/password.key", storage.getLockPasswordFilename(1));
    }

    public void testFileLocation_ProfileToSecondary() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext(), null);

        assertEquals("/data/system/users/1/gesture.key", storage.getLockPatternFilename(2));
        assertEquals("/data/system/users/1/password.key", storage.getLockPasswordFilename(2));
    }

    public void testFileLocation_ProfileToOwner() {
        LockSettingsStorage storage = new LockSettingsStorage(getContext(), null);

        assertEquals("/data/system/gesture.key", storage.getLockPatternFilename(3));
        assertEquals("/data/system/password.key", storage.getLockPasswordFilename(3));
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("expected:<" + Arrays.toString(expected) +
                    "> but was:<" + Arrays.toString(actual) + ">");
        }
    }
}
