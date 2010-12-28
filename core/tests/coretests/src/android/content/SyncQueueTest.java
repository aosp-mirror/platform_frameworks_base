/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContext;
import android.test.mock.MockContentResolver;
import android.accounts.Account;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.File;

public class SyncQueueTest extends AndroidTestCase {
    private static final Account ACCOUNT1 = new Account("test.account1", "test.type1");
    private static final Account ACCOUNT2 = new Account("test.account2", "test.type2");
    private static final String AUTHORITY1 = "test.authority1";
    private static final String AUTHORITY2 = "test.authority2";
    private static final String AUTHORITY3 = "test.authority3";

    private SyncStorageEngine mSettings;
    private SyncQueue mSyncQueue;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockContentResolver mockResolver = new MockContentResolver();
        mSettings = SyncStorageEngine.newTestInstance(new TestContext(mockResolver, getContext()));
        mSyncQueue = new SyncQueue(mSettings);
    }

    public void testSyncQueueOrder() throws Exception {
        final SyncOperation op1 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("1"), 0);
        final SyncOperation op2 = new SyncOperation(
                ACCOUNT2, SyncStorageEngine.SOURCE_USER, AUTHORITY2, newTestBundle("2"), 100);
        final SyncOperation op3 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("3"), 150);
        final SyncOperation op4 = new SyncOperation(
                ACCOUNT2, SyncStorageEngine.SOURCE_USER, AUTHORITY2, newTestBundle("4"), 60);
        final SyncOperation op5 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("5"), 80);
        final SyncOperation op6 = new SyncOperation(
                ACCOUNT2, SyncStorageEngine.SOURCE_USER, AUTHORITY2, newTestBundle("6"), 0);
        op6.expedited = true;

        mSyncQueue.add(op1);
        mSyncQueue.add(op2);
        mSyncQueue.add(op3);
        mSyncQueue.add(op4);
        mSyncQueue.add(op5);
        mSyncQueue.add(op6);

        long now = SystemClock.elapsedRealtime() + 200;

        assertEquals(op6, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op6);

        assertEquals(op1, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op1);

        assertEquals(op4, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op4);

        assertEquals(op5, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op5);

        assertEquals(op2, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op2);

        assertEquals(op3, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op3);
    }

    public void testOrderWithBackoff() throws Exception {
        final SyncOperation op1 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("1"), 0);
        final SyncOperation op2 = new SyncOperation(
                ACCOUNT2, SyncStorageEngine.SOURCE_USER, AUTHORITY2, newTestBundle("2"), 100);
        final SyncOperation op3 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("3"), 150);
        final SyncOperation op4 = new SyncOperation(
                ACCOUNT2, SyncStorageEngine.SOURCE_USER, AUTHORITY3, newTestBundle("4"), 60);
        final SyncOperation op5 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("5"), 80);
        final SyncOperation op6 = new SyncOperation(
                ACCOUNT2, SyncStorageEngine.SOURCE_USER, AUTHORITY2, newTestBundle("6"), 0);
        op6.expedited = true;

        mSyncQueue.add(op1);
        mSyncQueue.add(op2);
        mSyncQueue.add(op3);
        mSyncQueue.add(op4);
        mSyncQueue.add(op5);
        mSyncQueue.add(op6);

        long now = SystemClock.elapsedRealtime() + 200;

        assertEquals(op6, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op6);

        assertEquals(op1, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op1);

        mSettings.setBackoff(ACCOUNT2,  AUTHORITY3, now + 200, 5);
        assertEquals(op5, mSyncQueue.nextReadyToRun(now).first);

        mSettings.setBackoff(ACCOUNT2,  AUTHORITY3, SyncStorageEngine.NOT_IN_BACKOFF_MODE, 0);
        assertEquals(op4, mSyncQueue.nextReadyToRun(now).first);

        mSettings.setDelayUntilTime(ACCOUNT2,  AUTHORITY3, now + 200);
        assertEquals(op5, mSyncQueue.nextReadyToRun(now).first);

        mSettings.setDelayUntilTime(ACCOUNT2,  AUTHORITY3, 0);
        assertEquals(op4, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op4);

        assertEquals(op5, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op5);

        assertEquals(op2, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op2);

        assertEquals(op3, mSyncQueue.nextReadyToRun(now).first);
        mSyncQueue.remove(op3);
    }

    public void testExpeditedVsBackoff() throws Exception {

        final SyncOperation op1 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("1"), 0);
        final SyncOperation op2 = new SyncOperation(
                ACCOUNT1, SyncStorageEngine.SOURCE_USER, AUTHORITY1, newTestBundle("2"), 0);

        // op1 is expedited but backed off
        mSettings.setBackoff(ACCOUNT1,  AUTHORITY1, SystemClock.elapsedRealtime() + 1000000, 5);
        op1.expedited = true;

        // op2 is not expidaited but ignores back off so it should run
        op2.extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);

        // First test top level method
        mSyncQueue.remove(ACCOUNT1, AUTHORITY1);
        mSyncQueue.add(op1);
        mSyncQueue.add(op2);
        assertEquals(op2, mSyncQueue.nextReadyToRun(SystemClock.elapsedRealtime()).first);

        // Since the queue is implemented as a hash, we cannot control the order the op's get
        // fed into the algorithm so test the inner method in both cases
        final long opTime1 = mSyncQueue.getOpTime(op1);
        final boolean isInitial1 = mSyncQueue.getIsInitial(op1);
        final long opTime2 = mSyncQueue.getOpTime(op2);
        final boolean opIsInitial2 = mSyncQueue.getIsInitial(op2);

        assertTrue(mSyncQueue.isOpBetter(op1, opTime1, isInitial1, op2, opTime2, opIsInitial2));
        assertFalse(mSyncQueue.isOpBetter(op2, opTime2, opIsInitial2, op1, opTime1, isInitial1));
    }


    Bundle newTestBundle(String val) {
        Bundle bundle = new Bundle();
        bundle.putString("test", val);
        return bundle;
    }

    static class TestContext extends ContextWrapper {
        ContentResolver mResolver;

        public TestContext(ContentResolver resolver, Context realContext) {
                        super(new RenamingDelegatingContext(new MockContext() {
                @Override
                public File getFilesDir() {
                    return new File("/data");
                }
            }, realContext, "test."));
            mResolver = resolver;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }
    }
}
