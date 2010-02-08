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
import android.test.suitebuilder.annotation.SmallTest;
import android.test.mock.MockContext;
import android.test.mock.MockContentResolver;
import android.accounts.Account;
import android.os.Bundle;

import java.util.List;
import java.io.File;

public class SyncStorageEngineTest extends AndroidTestCase {

    /**
     * Test that we handle the case of a history row being old enough to purge before the
     * correcponding sync is finished. This can happen if the clock changes while we are syncing.
     *
     */
    @SmallTest
    public void testPurgeActiveSync() throws Exception {
        final Account account = new Account("a@example.com", "example.type");
        final String authority = "testprovider";

        MockContentResolver mockResolver = new MockContentResolver();

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));

        long time0 = 1000;
        long historyId = engine.insertStartSyncEvent(
                account, authority, time0, SyncStorageEngine.SOURCE_LOCAL);
        long time1 = time0 + SyncStorageEngine.MILLIS_IN_4WEEKS * 2;
        engine.stopSyncEvent(historyId, new Bundle(), time1 - time0, "yay", 0, 0);
    }

    /**
     * Test that we can create, remove and retrieve periodic syncs
     */
    @SmallTest
    public void testPeriodics() throws Exception {
        final Account account1 = new Account("a@example.com", "example.type");
        final Account account2 = new Account("b@example.com", "example.type.2");
        final String authority = "testprovider";
        final Bundle extras1 = new Bundle();
        extras1.putString("a", "1");
        final Bundle extras2 = new Bundle();
        extras2.putString("a", "2");
        final int period1 = 200;
        final int period2 = 1000;

        PeriodicSync sync1 = new PeriodicSync(account1, authority, extras1, period1);
        PeriodicSync sync2 = new PeriodicSync(account1, authority, extras2, period1);
        PeriodicSync sync3 = new PeriodicSync(account1, authority, extras2, period2);
        PeriodicSync sync4 = new PeriodicSync(account2, authority, extras2, period2);

        MockContentResolver mockResolver = new MockContentResolver();

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));

        removePeriodicSyncs(engine, account1, authority);
        removePeriodicSyncs(engine, account2, authority);

        // this should add two distinct periodic syncs for account1 and one for account2
        engine.addPeriodicSync(sync1.account, sync1.authority, sync1.extras, sync1.period);
        engine.addPeriodicSync(sync2.account, sync2.authority, sync2.extras, sync2.period);
        engine.addPeriodicSync(sync3.account, sync3.authority, sync3.extras, sync3.period);
        engine.addPeriodicSync(sync4.account, sync4.authority, sync4.extras, sync4.period);

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(account1, authority);

        assertEquals(2, syncs.size());

        assertEquals(sync1, syncs.get(0));
        assertEquals(sync3, syncs.get(1));

        engine.removePeriodicSync(sync1.account, sync1.authority, sync1.extras);

        syncs = engine.getPeriodicSyncs(account1, authority);
        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));

        syncs = engine.getPeriodicSyncs(account2, authority);
        assertEquals(1, syncs.size());
        assertEquals(sync4, syncs.get(0));
    }

    private void removePeriodicSyncs(SyncStorageEngine engine, Account account, String authority) {
        engine.setIsSyncable(account, authority, engine.getIsSyncable(account, authority));
        List<PeriodicSync> syncs = engine.getPeriodicSyncs(account, authority);
        for (PeriodicSync sync : syncs) {
            engine.removePeriodicSync(sync.account, sync.authority, sync.extras);
        }
    }

    @SmallTest
    public void testAuthorityPersistence() throws Exception {
        final Account account1 = new Account("a@example.com", "example.type");
        final Account account2 = new Account("b@example.com", "example.type.2");
        final String authority1 = "testprovider1";
        final String authority2 = "testprovider2";
        final Bundle extras1 = new Bundle();
        extras1.putString("a", "1");
        final Bundle extras2 = new Bundle();
        extras2.putString("a", "2");
        extras2.putLong("b", 2);
        extras2.putInt("c", 1);
        extras2.putBoolean("d", true);
        extras2.putDouble("e", 1.2);
        extras2.putFloat("f", 4.5f);
        extras2.putParcelable("g", account1);
        final int period1 = 200;
        final int period2 = 1000;

        PeriodicSync sync1 = new PeriodicSync(account1, authority1, extras1, period1);
        PeriodicSync sync2 = new PeriodicSync(account1, authority1, extras2, period1);
        PeriodicSync sync3 = new PeriodicSync(account1, authority2, extras1, period1);
        PeriodicSync sync4 = new PeriodicSync(account1, authority2, extras2, period2);
        PeriodicSync sync5 = new PeriodicSync(account2, authority1, extras1, period1);

        MockContentResolver mockResolver = new MockContentResolver();

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));

        removePeriodicSyncs(engine, account1, authority1);
        removePeriodicSyncs(engine, account2, authority1);
        removePeriodicSyncs(engine, account1, authority2);
        removePeriodicSyncs(engine, account2, authority2);

        engine.setMasterSyncAutomatically(false);

        engine.setIsSyncable(account1, authority1, 1);
        engine.setSyncAutomatically(account1, authority1, true);

        engine.setIsSyncable(account2, authority1, 1);
        engine.setSyncAutomatically(account2, authority1, true);

        engine.setIsSyncable(account1, authority2, 1);
        engine.setSyncAutomatically(account1, authority2, false);

        engine.setIsSyncable(account2, authority2, 0);
        engine.setSyncAutomatically(account2, authority2, true);

        engine.addPeriodicSync(sync1.account, sync1.authority, sync1.extras, sync1.period);
        engine.addPeriodicSync(sync2.account, sync2.authority, sync2.extras, sync2.period);
        engine.addPeriodicSync(sync3.account, sync3.authority, sync3.extras, sync3.period);
        engine.addPeriodicSync(sync4.account, sync4.authority, sync4.extras, sync4.period);
        engine.addPeriodicSync(sync5.account, sync5.authority, sync5.extras, sync5.period);

        engine.writeAllState();
        engine.clearAndReadState();

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(account1, authority1);
        assertEquals(2, syncs.size());
        assertEquals(sync1, syncs.get(0));
        assertEquals(sync2, syncs.get(1));

        syncs = engine.getPeriodicSyncs(account1, authority2);
        assertEquals(2, syncs.size());
        assertEquals(sync3, syncs.get(0));
        assertEquals(sync4, syncs.get(1));

        syncs = engine.getPeriodicSyncs(account2, authority1);
        assertEquals(1, syncs.size());
        assertEquals(sync5, syncs.get(0));

        assertEquals(true, engine.getSyncAutomatically(account1, authority1));
        assertEquals(true, engine.getSyncAutomatically(account2, authority1));
        assertEquals(false, engine.getSyncAutomatically(account1, authority2));
        assertEquals(true, engine.getSyncAutomatically(account2, authority2));

        assertEquals(1, engine.getIsSyncable(account1, authority1));
        assertEquals(1, engine.getIsSyncable(account2, authority1));
        assertEquals(1, engine.getIsSyncable(account1, authority2));
        assertEquals(0, engine.getIsSyncable(account2, authority2));
    }
}

class TestContext extends ContextWrapper {

    ContentResolver mResolver;

    private final Context mRealContext;

    public TestContext(ContentResolver resolver, Context realContext) {
        super(new RenamingDelegatingContext(new MockContext(), realContext, "test."));
        mRealContext = realContext;
        mResolver = resolver;
    }

    @Override
    public File getFilesDir() {
        return mRealContext.getFilesDir();
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
    }

    @Override
    public void sendBroadcast(Intent intent) {
    }

    @Override
    public ContentResolver getContentResolver() {
        return mResolver;
    }
}
