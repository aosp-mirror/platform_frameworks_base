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

package com.android.server.content;

import android.accounts.Account;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.PeriodicSync;
import android.content.res.Resources;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.content.SyncStorageEngine.EndPoint;

import com.android.internal.os.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import com.android.server.content.SyncStorageEngine.EndPoint;

public class SyncStorageEngineTest extends AndroidTestCase {

    protected Account account1;
    protected Account account2;
    protected ComponentName syncService1;
    protected String authority1 = "testprovider";
    protected Bundle defaultBundle;
    protected final int DEFAULT_USER = 0;

    /* Some default poll frequencies. */
    final long dayPoll = (60 * 60 * 24);
    final long dayFuzz = 60;
    final long thousandSecs = 1000;
    final long thousandSecsFuzz = 100;

    MockContentResolver mockResolver;
    SyncStorageEngine engine;

    private File getSyncDir() {
        return new File(new File(getContext().getFilesDir(), "system"), "sync");
    }

    @Override
    public void setUp() {
        account1 = new Account("a@example.com", "example.type");
        account2 = new Account("b@example.com", "example.type");
        syncService1 = new ComponentName("com.example", "SyncService");
        // Default bundle.
        defaultBundle = new Bundle();
        defaultBundle.putInt("int_key", 0);
        defaultBundle.putString("string_key", "hello");
        // Set up storage engine.
        mockResolver = new MockContentResolver();
        engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));
    }

    /**
     * Test that we handle the case of a history row being old enough to purge before the
     * corresponding sync is finished. This can happen if the clock changes while we are syncing.
     *
     */
    // TODO: this test causes AidlTest to fail. Omit for now
    // @SmallTest
    public void testPurgeActiveSync() throws Exception {
        final Account account = new Account("a@example.com", "example.type");
        final String authority = "testprovider";

        MockContentResolver mockResolver = new MockContentResolver();

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));
        long time0 = 1000;
        SyncOperation op = new SyncOperation(account, 0,
                SyncOperation.REASON_PERIODIC,
                SyncStorageEngine.SOURCE_LOCAL,
                authority,
                Bundle.EMPTY, time0, 0 /* flex*/, 0, 0, true);
        long historyId = engine.insertStartSyncEvent(op, time0);
        long time1 = time0 + SyncStorageEngine.MILLIS_IN_4WEEKS * 2;
        engine.stopSyncEvent(historyId, time1 - time0, "yay", 0, 0);
    }

    /**
     * Test persistence of pending operations.
     */
    @MediumTest
    public void testAppendPending() throws Exception {
        SyncOperation sop = new SyncOperation(account1,
                DEFAULT_USER,
                SyncOperation.REASON_PERIODIC,
                SyncStorageEngine.SOURCE_LOCAL, authority1, Bundle.EMPTY,
                0 /* runtime */, 0 /* flex */, 0 /* backoff */, 0 /* delayuntil */,
                true /* expedited */);
        engine.insertIntoPending(sop);

        // Force engine to read from disk.
        engine.clearAndReadState();

        assertTrue(engine.getPendingOperationCount() == 1);
        List<SyncStorageEngine.PendingOperation> pops = engine.getPendingOperations();
        SyncStorageEngine.PendingOperation popRetrieved = pops.get(0);
        assertEquals(sop.target.account, popRetrieved.target.account);
        assertEquals(sop.target.provider, popRetrieved.target.provider);
        assertEquals(sop.target.service, popRetrieved.target.service);
        assertEquals(sop.target.userId, popRetrieved.target.userId);
        assertEquals(sop.reason, popRetrieved.reason);
        assertEquals(sop.syncSource, popRetrieved.syncSource);
        assertEquals(sop.isExpedited(), popRetrieved.expedited);
        assert(android.content.PeriodicSync.syncExtrasEquals(sop.extras, popRetrieved.extras));
    }

    /**
     * Verify {@link com.android.server.content.SyncStorageEngine#writePendingOperationsLocked()}
     */
    public void testWritePendingOperationsLocked() throws Exception {
        SyncOperation sop = new SyncOperation(account1,
                DEFAULT_USER,
                SyncOperation.REASON_IS_SYNCABLE,
                SyncStorageEngine.SOURCE_LOCAL, authority1, Bundle.EMPTY,
                1000L /* runtime */, 57L /* flex */, 0 /* backoff */, 0 /* delayuntil */,
                true /* expedited */);
        SyncOperation sop1 = new SyncOperation(account2,
                DEFAULT_USER,
                SyncOperation.REASON_PERIODIC,
                SyncStorageEngine.SOURCE_LOCAL, authority1, defaultBundle,
                0 /* runtime */, 0 /* flex */, 20L /* backoff */, 100L /* delayuntil */,
                false /* expedited */);
        SyncOperation deleted = new SyncOperation(account2,
                DEFAULT_USER,
                SyncOperation.REASON_SYNC_AUTO,
                SyncStorageEngine.SOURCE_LOCAL, authority1, Bundle.EMPTY,
                0 /* runtime */, 0 /* flex */, 20L /* backoff */, 100L /* delayuntil */,
                false /* expedited */);
        engine.insertIntoPending(sop);
        engine.insertIntoPending(sop1);
        engine.insertIntoPending(deleted);

        SyncStorageEngine.PendingOperation popDeleted = engine.getPendingOperations().get(2);
        // Free verifying, going to delete it anyway.
        assertEquals(deleted.target.account, popDeleted.target.account);
        assertEquals(deleted.target.provider, popDeleted.target.provider);
        assertEquals(deleted.target.service, popDeleted.target.service);
        assertEquals(deleted.target.userId, popDeleted.target.userId);
        assertEquals(deleted.reason, popDeleted.reason);
        assertEquals(deleted.syncSource, popDeleted.syncSource);
        assertEquals(deleted.isExpedited(), popDeleted.expedited);
        assert(android.content.PeriodicSync.syncExtrasEquals(deleted.extras, popDeleted.extras));
        // Delete one to force write-all
        engine.deleteFromPending(popDeleted);
        assertEquals("Delete of pending op failed.", 2, engine.getPendingOperationCount());
        // If there's dirty pending data (which there is because we deleted a pending op) this
        // re-writes the entire file.
        engine.writeAllState();

        engine.clearAndReadState();

        // Validate state read back out.
        assertEquals("Delete of pending op failed.", 2, engine.getPendingOperationCount());

        List<SyncStorageEngine.PendingOperation> pops = engine.getPendingOperations();

        SyncStorageEngine.PendingOperation popRetrieved = pops.get(0);
        assertEquals(sop.target.account, popRetrieved.target.account);
        assertEquals(sop.target.provider, popRetrieved.target.provider);
        assertEquals(sop.target.service, popRetrieved.target.service);
        assertEquals(sop.target.userId, popRetrieved.target.userId);
        assertEquals(sop.reason, popRetrieved.reason);
        assertEquals(sop.syncSource, popRetrieved.syncSource);
        assertEquals(sop.isExpedited(), popRetrieved.expedited);
        assert(android.content.PeriodicSync.syncExtrasEquals(sop.extras, popRetrieved.extras));

        popRetrieved = pops.get(1);
        assertEquals(sop1.target.account, popRetrieved.target.account);
        assertEquals(sop1.target.provider, popRetrieved.target.provider);
        assertEquals(sop1.target.service, popRetrieved.target.service);
        assertEquals(sop1.target.userId, popRetrieved.target.userId);
        assertEquals(sop1.reason, popRetrieved.reason);
        assertEquals(sop1.syncSource, popRetrieved.syncSource);
        assertEquals(sop1.isExpedited(), popRetrieved.expedited);
        assert(android.content.PeriodicSync.syncExtrasEquals(sop1.extras, popRetrieved.extras));
    }

    /**
     * Test that we can create, remove and retrieve periodic syncs. Backwards compatibility -
     * periodic syncs with no flex time are no longer used.
     */
    @MediumTest
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
        EndPoint end1 = new EndPoint(account1, authority, 0);

        PeriodicSync sync2 = new PeriodicSync(account1, authority, extras2, period1);
        PeriodicSync sync3 = new PeriodicSync(account1, authority, extras2, period2);
        PeriodicSync sync4 = new PeriodicSync(account2, authority, extras2, period2);



        removePeriodicSyncs(engine, account1, 0, authority);
        removePeriodicSyncs(engine, account2, 0, authority);
        removePeriodicSyncs(engine, account1, 1, authority);

        // this should add two distinct periodic syncs for account1 and one for account2
        engine.updateOrAddPeriodicSync(new EndPoint(account1, authority, 0), period1, 0, extras1);
        engine.updateOrAddPeriodicSync(new EndPoint(account1, authority, 0), period1, 0, extras2);
        engine.updateOrAddPeriodicSync(new EndPoint(account1, authority, 0), period2, 0, extras2);
        engine.updateOrAddPeriodicSync(new EndPoint(account2, authority, 0), period2, 0, extras2);
        // add a second user
        engine.updateOrAddPeriodicSync(new EndPoint(account1, authority, 1), period1, 0, extras2);

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(new EndPoint(account1, authority, 0));

        assertEquals(2, syncs.size());

        assertEquals(sync1, syncs.get(0));
        assertEquals(sync3, syncs.get(1));

        engine.removePeriodicSync(new EndPoint(account1, authority, 0), extras1);

        syncs = engine.getPeriodicSyncs(new EndPoint(account1, authority, 0));
        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));

        syncs = engine.getPeriodicSyncs(new EndPoint(account2, authority, 0));
        assertEquals(1, syncs.size());
        assertEquals(sync4, syncs.get(0));

        syncs = engine.getPeriodicSyncs(new EndPoint(sync2.account, sync2.authority, 1));
        assertEquals(1, syncs.size());
        assertEquals(sync2, syncs.get(0));
    }

    /**
     * Test that we can create, remove and retrieve periodic syncs with a provided flex time.
     */
    @MediumTest
    public void testPeriodicsV2() throws Exception {
        final Account account1 = new Account("a@example.com", "example.type");
        final Account account2 = new Account("b@example.com", "example.type.2");
        final String authority = "testprovider";
        final Bundle extras1 = new Bundle();
        extras1.putString("a", "1");
        final Bundle extras2 = new Bundle();
        extras2.putString("a", "2");
        final int period1 = 200;
        final int period2 = 1000;
        final int flex1 = 10;
        final int flex2 = 100;
        EndPoint point1 = new EndPoint(account1, authority, 0);
        EndPoint point2 = new EndPoint(account2, authority, 0);
        EndPoint point1User2 = new EndPoint(account1, authority, 1);

        PeriodicSync sync1 = new PeriodicSync(account1, authority, extras1, period1, flex1);
        PeriodicSync sync2 = new PeriodicSync(account1, authority, extras2, period1, flex1);
        PeriodicSync sync3 = new PeriodicSync(account1, authority, extras2, period2, flex2);
        PeriodicSync sync4 = new PeriodicSync(account2, authority, extras2, period2, flex2);

        EndPoint target1 = new EndPoint(account1, authority, 0);
        EndPoint target2 = new EndPoint(account2, authority, 0);
        EndPoint target1UserB = new EndPoint(account1, authority, 1);

        MockContentResolver mockResolver = new MockContentResolver();

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));

        removePeriodicSyncs(engine, account1, 0, authority);
        removePeriodicSyncs(engine, account2, 0, authority);
        removePeriodicSyncs(engine, account1, 1, authority);

        // This should add two distinct periodic syncs for account1 and one for account2
        engine.updateOrAddPeriodicSync(target1, period1, flex1, extras1);
        engine.updateOrAddPeriodicSync(target1, period1, flex1, extras2);
        // Edit existing sync and update the period and flex.
        engine.updateOrAddPeriodicSync(target1, period2, flex2, extras2);
        engine.updateOrAddPeriodicSync(target2, period2, flex2, extras2);
        // add a target for a second user.
        engine.updateOrAddPeriodicSync(target1UserB, period1, flex1, extras2);

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(target1);

        assertEquals(2, syncs.size());

        assertEquals(sync1, syncs.get(0));
        assertEquals(sync3, syncs.get(1));

        engine.removePeriodicSync(target1, extras1);

        syncs = engine.getPeriodicSyncs(target1);
        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(1, syncs.size());
        assertEquals(sync4, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target1UserB);
        assertEquals(1, syncs.size());
        assertEquals(sync2, syncs.get(0));
    }

    private void removePeriodicSyncs(SyncStorageEngine engine, Account account, int userId, String authority) {
        EndPoint target = new EndPoint(account, authority, userId);
        engine.setIsSyncable(account, userId, authority, engine.getIsSyncable(account, userId, authority));
        List<PeriodicSync> syncs = engine.getPeriodicSyncs(target);
        for (PeriodicSync sync : syncs) {
            engine.removePeriodicSync(target, sync.extras);
        }
    }

    @LargeTest
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
        final int flex1 = 10;
        final int flex2 = 100;

        EndPoint point1 = new EndPoint(account1, authority1, 0);
        EndPoint point2 = new EndPoint(account1, authority2, 0);
        EndPoint point3 = new EndPoint(account2, authority1, 0);

        PeriodicSync sync1 = new PeriodicSync(account1, authority1, extras1, period1, flex1);
        PeriodicSync sync2 = new PeriodicSync(account1, authority1, extras2, period1, flex1);
        PeriodicSync sync3 = new PeriodicSync(account1, authority2, extras1, period1, flex1);
        PeriodicSync sync4 = new PeriodicSync(account1, authority2, extras2, period2, flex2);
        PeriodicSync sync5 = new PeriodicSync(account2, authority1, extras1, period1, flex1);

        EndPoint target1 = new EndPoint(account1, authority1, 0);
        EndPoint target2 = new EndPoint(account1, authority2, 0);
        EndPoint target3 = new EndPoint(account2, authority1, 0);

        removePeriodicSyncs(engine, account1, 0, authority1);
        removePeriodicSyncs(engine, account2, 0, authority1);
        removePeriodicSyncs(engine, account1, 0, authority2);
        removePeriodicSyncs(engine, account2, 0, authority2);

        engine.setMasterSyncAutomatically(false, 0);

        engine.setIsSyncable(account1, 0, authority1, 1);
        engine.setSyncAutomatically(account1, 0, authority1, true);

        engine.setIsSyncable(account2, 0, authority1, 1);
        engine.setSyncAutomatically(account2, 0, authority1, true);

        engine.setIsSyncable(account1, 0, authority2, 1);
        engine.setSyncAutomatically(account1, 0, authority2, false);

        engine.setIsSyncable(account2, 0, authority2, 0);
        engine.setSyncAutomatically(account2, 0, authority2, true);

        engine.updateOrAddPeriodicSync(target1, period1, flex1, extras1);
        engine.updateOrAddPeriodicSync(target1, period1, flex1, extras2);
        engine.updateOrAddPeriodicSync(target2, period1, flex1, extras1);
        engine.updateOrAddPeriodicSync(target2, period2, flex2, extras2);
        engine.updateOrAddPeriodicSync(target3, period1, flex1, extras1);

        engine.writeAllState();
        engine.clearAndReadState();

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(target1);
        assertEquals(2, syncs.size());
        assertEquals(sync1, syncs.get(0));
        assertEquals(sync2, syncs.get(1));

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(2, syncs.size());
        assertEquals(sync3, syncs.get(0));
        assertEquals(sync4, syncs.get(1));

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(1, syncs.size());
        assertEquals(sync5, syncs.get(0));

        assertEquals(true, engine.getSyncAutomatically(account1, 0, authority1));
        assertEquals(true, engine.getSyncAutomatically(account2, 0, authority1));
        assertEquals(false, engine.getSyncAutomatically(account1, 0, authority2));
        assertEquals(true, engine.getSyncAutomatically(account2, 0, authority2));

        assertEquals(1, engine.getIsSyncable(account1, 0, authority1));
        assertEquals(1, engine.getIsSyncable(account2, 0, authority1));
        assertEquals(1, engine.getIsSyncable(account1, 0, authority2));
        assertEquals(0, engine.getIsSyncable(account2, 0, authority2));
    }

    @SmallTest
    public void testComponentParsing() throws Exception {

        byte[] accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts version=\"2\" >\n"
                + "<authority id=\"0\" user=\"0\" package=\"" + syncService1.getPackageName() + "\""
                + " class=\"" + syncService1.getClassName() + "\" syncable=\"true\">"
                + "\n<periodicSync period=\"" + dayPoll + "\" flex=\"" + dayFuzz + "\"/>"
                + "\n</authority>"
                + "</accounts>").getBytes();

        File syncDir = getSyncDir();
        syncDir.mkdirs();
        AtomicFile accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        FileOutputStream fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        engine.clearAndReadState();

        SyncStorageEngine.AuthorityInfo aInfo = engine.getAuthority(0);
        assertNotNull(aInfo);

        // Test service component read
        List<PeriodicSync> syncs = engine.getPeriodicSyncs(
                new SyncStorageEngine.EndPoint(syncService1, 0));
        assertEquals(1, syncs.size());
        assertEquals(true, engine.getIsTargetServiceActive(syncService1, 0));
    }

    @SmallTest
    public void testComponentSettings() throws Exception {
        EndPoint target1 = new EndPoint(syncService1, 0);
        engine.updateOrAddPeriodicSync(target1, dayPoll, dayFuzz, Bundle.EMPTY);
        
        engine.setIsTargetServiceActive(target1.service, 0, true);
        boolean active = engine.getIsTargetServiceActive(target1.service, 0);
        assert(active);

        engine.setIsTargetServiceActive(target1.service, 1, false);
        active = engine.getIsTargetServiceActive(target1.service, 1);
        assert(!active);
    }

    @MediumTest
    /**
     * V2 introduces flex time as well as service components.
     * @throws Exception
     */
    public void testAuthorityParsingV2() throws Exception {
        final Account account = new Account("account1", "type1");
        final String authority1 = "auth1";
        final String authority2 = "auth2";
        final String authority3 = "auth3";

        EndPoint target1 = new EndPoint(account, authority1, 0);
        EndPoint target2 = new EndPoint(account, authority2, 0);
        EndPoint target3 = new EndPoint(account, authority3, 0);
        EndPoint target4 = new EndPoint(account, authority3, 1);

        PeriodicSync sync1 = new PeriodicSync(account, authority1, Bundle.EMPTY, dayPoll, dayFuzz);
        PeriodicSync sync2 = new PeriodicSync(account, authority2, Bundle.EMPTY, dayPoll, dayFuzz);
        PeriodicSync sync3 = new PeriodicSync(account, authority3, Bundle.EMPTY, dayPoll, dayFuzz);
        PeriodicSync sync1s = new PeriodicSync(account, authority1, Bundle.EMPTY, thousandSecs,
                thousandSecsFuzz);
        PeriodicSync sync2s = new PeriodicSync(account, authority2, Bundle.EMPTY, thousandSecs,
                thousandSecsFuzz);
        PeriodicSync sync3s = new PeriodicSync(account, authority3, Bundle.EMPTY, thousandSecs,
                thousandSecsFuzz);

        byte[] accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts version=\"2\" >\n"
                + "<authority id=\"0\" user=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\" >"
                + "\n<periodicSync period=\"" + dayPoll + "\" flex=\"" + dayFuzz + "\"/>"
                + "\n</authority>"
                + "<authority id=\"1\" user=\"0\" account=\"account1\" type=\"type1\" authority=\"auth2\" >"
                + "\n<periodicSync period=\"" + dayPoll + "\" flex=\"" + dayFuzz + "\"/>"
                + "\n</authority>"
                // No user defaults to user 0 - all users.
                + "<authority id=\"2\"            account=\"account1\" type=\"type1\" authority=\"auth3\" >"
                + "\n<periodicSync period=\"" + dayPoll + "\" flex=\"" + dayFuzz + "\"/>"
                + "\n</authority>"
                + "<authority id=\"3\" user=\"1\" account=\"account1\" type=\"type1\" authority=\"auth3\" >"
                + "\n<periodicSync period=\"" + dayPoll + "\" flex=\"" + dayFuzz + "\"/>"
                + "\n</authority>"
                + "</accounts>").getBytes();

        File syncDir = getSyncDir();
        syncDir.mkdirs();
        AtomicFile accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        FileOutputStream fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        engine.clearAndReadState();

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(target1);
        assertEquals("Got incorrect # of syncs", 1, syncs.size());
        assertEquals(sync1, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(1, syncs.size());
        assertEquals(sync2, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target4);

        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));

        // Test empty periodic data.
        accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts version=\"2\">\n"
                + "<authority id=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\" />\n"
                + "<authority id=\"1\" account=\"account1\" type=\"type1\" authority=\"auth2\" />\n"
                + "<authority id=\"2\" account=\"account1\" type=\"type1\" authority=\"auth3\" />\n"
                + "</accounts>\n").getBytes();

        accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        engine.clearAndReadState();

        syncs = engine.getPeriodicSyncs(target1);
        assertEquals(0, syncs.size());

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(0, syncs.size());

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(0, syncs.size());

        accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts version=\"2\">\n"
                + "<authority id=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\">\n"
                + "<periodicSync period=\"1000\" />\n"
                + "</authority>"
                + "<authority id=\"1\" account=\"account1\" type=\"type1\" authority=\"auth2\">\n"
                + "<periodicSync period=\"1000\" />\n"
                + "</authority>"
                + "<authority id=\"2\" account=\"account1\" type=\"type1\" authority=\"auth3\">\n"
                + "<periodicSync period=\"1000\" />\n"
                + "</authority>"
                + "</accounts>\n").getBytes();

        accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        engine.clearAndReadState();

        syncs = engine.getPeriodicSyncs(target1);
        assertEquals(1, syncs.size());
        assertEquals(sync1s, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(1, syncs.size());
        assertEquals(sync2s, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(1, syncs.size());
        assertEquals(sync3s, syncs.get(0));
    }

    @MediumTest
    public void testAuthorityParsing() throws Exception {
        final Account account = new Account("account1", "type1");
        final String authority1 = "auth1";
        final String authority2 = "auth2";
        final String authority3 = "auth3";
        final Bundle extras = new Bundle();

        EndPoint target1 = new EndPoint(account, authority1, 0);
        EndPoint target2 = new EndPoint(account, authority2, 0);
        EndPoint target3 = new EndPoint(account, authority3, 0);
        EndPoint target4 = new EndPoint(account, authority3, 1);

        PeriodicSync sync1 = new PeriodicSync(account, authority1, extras, (long) (60 * 60 * 24));
        PeriodicSync sync2 = new PeriodicSync(account, authority2, extras, (long) (60 * 60 * 24));
        PeriodicSync sync3 = new PeriodicSync(account, authority3, extras, (long) (60 * 60 * 24));
        PeriodicSync sync1s = new PeriodicSync(account, authority1, extras, 1000);
        PeriodicSync sync2s = new PeriodicSync(account, authority2, extras, 1000);
        PeriodicSync sync3s = new PeriodicSync(account, authority3, extras, 1000);

        MockContentResolver mockResolver = new MockContentResolver();

        final TestContext testContext = new TestContext(mockResolver, getContext());

        byte[] accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts>\n"
                + "<authority id=\"0\" user=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\" />\n"
                + "<authority id=\"1\" user=\"0\" account=\"account1\" type=\"type1\" authority=\"auth2\" />\n"
                + "<authority id=\"2\"            account=\"account1\" type=\"type1\" authority=\"auth3\" />\n"
                + "<authority id=\"3\" user=\"1\" account=\"account1\" type=\"type1\" authority=\"auth3\" />\n"
                + "</accounts>\n").getBytes();

        File syncDir = getSyncDir();
        syncDir.mkdirs();
        AtomicFile accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        FileOutputStream fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(testContext);

        List<PeriodicSync> syncs = engine.getPeriodicSyncs(target1);
        assertEquals(1, syncs.size());
        assertEquals("expected sync1: " + sync1.toString() + " == sync 2" + syncs.get(0).toString(), sync1, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(1, syncs.size());
        assertEquals(sync2, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));
        syncs = engine.getPeriodicSyncs(target4);


        assertEquals(1, syncs.size());
        assertEquals(sync3, syncs.get(0));

        accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts version=\"2\">\n"
                + "<authority id=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\" />\n"
                + "<authority id=\"1\" account=\"account1\" type=\"type1\" authority=\"auth2\" />\n"
                + "<authority id=\"2\" account=\"account1\" type=\"type1\" authority=\"auth3\" />\n"
                + "</accounts>\n").getBytes();

        accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        engine.clearAndReadState();

        syncs = engine.getPeriodicSyncs(target1);
        assertEquals(0, syncs.size());

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(0, syncs.size());

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(0, syncs.size());

        accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts version=\"2\">\n"
                + "<authority id=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\">\n"
                + "<periodicSync period=\"1000\" />\n"
                + "</authority>"
                + "<authority id=\"1\" account=\"account1\" type=\"type1\" authority=\"auth2\">\n"
                + "<periodicSync period=\"1000\" />\n"
                + "</authority>"
                + "<authority id=\"2\" account=\"account1\" type=\"type1\" authority=\"auth3\">\n"
                + "<periodicSync period=\"1000\" />\n"
                + "</authority>"
                + "</accounts>\n").getBytes();

        accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        engine.clearAndReadState();

        syncs = engine.getPeriodicSyncs(target1);
        assertEquals(1, syncs.size());
        assertEquals(sync1s, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target2);
        assertEquals(1, syncs.size());
        assertEquals(sync2s, syncs.get(0));

        syncs = engine.getPeriodicSyncs(target3);
        assertEquals(1, syncs.size());
        assertEquals(sync3s, syncs.get(0));
    }

    @MediumTest
    public void testListenForTicklesParsing() throws Exception {
        byte[] accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts>\n"
                + "<listenForTickles user=\"0\" enabled=\"false\" />"
                + "<listenForTickles user=\"1\" enabled=\"true\" />"
                + "<authority id=\"0\" user=\"0\" account=\"account1\" type=\"type1\" authority=\"auth1\" />\n"
                + "<authority id=\"1\" user=\"1\" account=\"account1\" type=\"type1\" authority=\"auth1\" />\n"
                + "</accounts>\n").getBytes();

        MockContentResolver mockResolver = new MockContentResolver();
        final TestContext testContext = new TestContext(mockResolver, getContext());

        File syncDir = getSyncDir();
        syncDir.mkdirs();
        AtomicFile accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        FileOutputStream fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(testContext);

        assertEquals(false, engine.getMasterSyncAutomatically(0));
        assertEquals(true, engine.getMasterSyncAutomatically(1));
        assertEquals(true, engine.getMasterSyncAutomatically(2));

    }

    @MediumTest
    public void testAuthorityRenaming() throws Exception {
        final Account account1 = new Account("acc1", "type1");
        final Account account2 = new Account("acc2", "type2");
        final String authorityContacts = "contacts";
        final String authorityCalendar = "calendar";
        final String authorityOther = "other";
        final String authorityContactsNew = "com.android.contacts";
        final String authorityCalendarNew = "com.android.calendar";

        MockContentResolver mockResolver = new MockContentResolver();

        final TestContext testContext = new TestContext(mockResolver, getContext());

        byte[] accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts>\n"
                + "<authority id=\"0\" account=\"acc1\" type=\"type1\" authority=\"contacts\" />\n"
                + "<authority id=\"1\" account=\"acc1\" type=\"type1\" authority=\"calendar\" />\n"
                + "<authority id=\"2\" account=\"acc1\" type=\"type1\" authority=\"other\" />\n"
                + "<authority id=\"3\" account=\"acc2\" type=\"type2\" authority=\"contacts\" />\n"
                + "<authority id=\"4\" account=\"acc2\" type=\"type2\" authority=\"calendar\" />\n"
                + "<authority id=\"5\" account=\"acc2\" type=\"type2\" authority=\"other\" />\n"
                + "<authority id=\"6\" account=\"acc2\" type=\"type2\" enabled=\"false\""
                + " authority=\"com.android.calendar\" />\n"
                + "<authority id=\"7\" account=\"acc2\" type=\"type2\" enabled=\"false\""
                + " authority=\"com.android.contacts\" />\n"
                + "</accounts>\n").getBytes();

        File syncDir = new File(new File(testContext.getFilesDir(), "system"), "sync");
        syncDir.mkdirs();
        AtomicFile accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        FileOutputStream fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(testContext);

        assertEquals(false, engine.getSyncAutomatically(account1, 0, authorityContacts));
        assertEquals(false, engine.getSyncAutomatically(account1, 0, authorityCalendar));
        assertEquals(true, engine.getSyncAutomatically(account1, 0, authorityOther));
        assertEquals(true, engine.getSyncAutomatically(account1, 0, authorityContactsNew));
        assertEquals(true, engine.getSyncAutomatically(account1, 0, authorityCalendarNew));

        assertEquals(false, engine.getSyncAutomatically(account2, 0, authorityContacts));
        assertEquals(false, engine.getSyncAutomatically(account2, 0, authorityCalendar));
        assertEquals(true, engine.getSyncAutomatically(account2, 0, authorityOther));
        assertEquals(false, engine.getSyncAutomatically(account2, 0, authorityContactsNew));
        assertEquals(false, engine.getSyncAutomatically(account2, 0, authorityCalendarNew));
    }

    @SmallTest
    public void testSyncableMigration() throws Exception {
        final Account account = new Account("acc", "type");

        MockContentResolver mockResolver = new MockContentResolver();

        final TestContext testContext = new TestContext(mockResolver, getContext());

        byte[] accountsFileData = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<accounts>\n"
                + "<authority id=\"0\" account=\"acc\" authority=\"other1\" />\n"
                + "<authority id=\"1\" account=\"acc\" type=\"type\" authority=\"other2\" />\n"
                + "<authority id=\"2\" account=\"acc\" type=\"type\" syncable=\"false\""
                + " authority=\"other3\" />\n"
                + "<authority id=\"3\" account=\"acc\" type=\"type\" syncable=\"true\""
                + " authority=\"other4\" />\n"
                + "</accounts>\n").getBytes();

        File syncDir = new File(new File(testContext.getFilesDir(), "system"), "sync");
        syncDir.mkdirs();
        AtomicFile accountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        FileOutputStream fos = accountInfoFile.startWrite();
        fos.write(accountsFileData);
        accountInfoFile.finishWrite(fos);

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(testContext);

        assertEquals(-1, engine.getIsSyncable(account, 0, "other1"));
        assertEquals(1, engine.getIsSyncable(account, 0, "other2"));
        assertEquals(0, engine.getIsSyncable(account, 0, "other3"));
        assertEquals(1, engine.getIsSyncable(account, 0, "other4"));
    }

    /**
     * Verify that the API cannot cause a run-time reboot by passing in the empty string as an
     * authority. The problem here is that
     * {@link SyncStorageEngine#getOrCreateAuthorityLocked(account, provider)} would register
     * an empty authority which causes a RTE in {@link SyncManager#scheduleReadyPeriodicSyncs()}.
     * This is not strictly a SSE test, but it does depend on the SSE data structures.
     */
    @SmallTest
    public void testExpectedIllegalArguments() throws Exception {
        try {
            ContentResolver.setSyncAutomatically(account1, "", true);
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.addPeriodicSync(account1, "", Bundle.EMPTY, 84000L);
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.removePeriodicSync(account1, "", Bundle.EMPTY);
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.cancelSync(account1, "");
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.setIsSyncable(account1, "", 0);
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.cancelSync(account1, "");
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.requestSync(account1, "", Bundle.EMPTY);
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        try {
            ContentResolver.getSyncStatus(account1, "");
            fail("empty provider string should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // Make sure we aren't blocking null account/provider for those functions that use it
        // to specify ALL accounts/providers.
        ContentResolver.requestSync(null, null, Bundle.EMPTY);
        ContentResolver.cancelSync(null, null);
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
    public Resources getResources() {
        return mRealContext.getResources();
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
