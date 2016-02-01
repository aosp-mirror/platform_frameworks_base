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
        SyncOperation op = new SyncOperation(account, 0, 0, "foo",
                SyncOperation.REASON_PERIODIC,
                SyncStorageEngine.SOURCE_LOCAL,
                authority,
                Bundle.EMPTY, true);
        long historyId = engine.insertStartSyncEvent(op, time0);
        long time1 = time0 + SyncStorageEngine.MILLIS_IN_4WEEKS * 2;
        engine.stopSyncEvent(historyId, time1 - time0, "yay", 0, 0);
    }

    @LargeTest
    public void testAuthorityPersistence() throws Exception {
        final Account account1 = new Account("a@example.com", "example.type");
        final Account account2 = new Account("b@example.com", "example.type.2");
        final String authority1 = "testprovider1";
        final String authority2 = "testprovider2";

        engine.setMasterSyncAutomatically(false, 0);

        engine.setIsSyncable(account1, 0, authority1, 1);
        engine.setSyncAutomatically(account1, 0, authority1, true);

        engine.setIsSyncable(account2, 0, authority1, 1);
        engine.setSyncAutomatically(account2, 0, authority1, true);

        engine.setIsSyncable(account1, 0, authority2, 1);
        engine.setSyncAutomatically(account1, 0, authority2, false);

        engine.setIsSyncable(account2, 0, authority2, 0);
        engine.setSyncAutomatically(account2, 0, authority2, true);

        engine.writeAllState();
        engine.clearAndReadState();

        assertEquals(true, engine.getSyncAutomatically(account1, 0, authority1));
        assertEquals(true, engine.getSyncAutomatically(account2, 0, authority1));
        assertEquals(false, engine.getSyncAutomatically(account1, 0, authority2));
        assertEquals(true, engine.getSyncAutomatically(account2, 0, authority2));

        assertEquals(1, engine.getIsSyncable(account1, 0, authority1));
        assertEquals(1, engine.getIsSyncable(account2, 0, authority1));
        assertEquals(1, engine.getIsSyncable(account1, 0, authority2));
        assertEquals(0, engine.getIsSyncable(account2, 0, authority2));
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
