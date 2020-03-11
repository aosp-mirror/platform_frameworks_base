/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.FileUtils;
import android.text.format.DateUtils;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Set;

@RunWith(JUnit4.class)
public final class ConversationStoreTest {

    private static final String SHORTCUT_ID = "abc";
    private static final String NOTIFICATION_CHANNEL_ID = "test : abc";
    private static final LocusId LOCUS_ID = new LocusId("def");
    private static final Uri CONTACT_URI = Uri.parse("tel:+1234567890");
    private static final String PHONE_NUMBER = "+1234567890";

    private static final String SHORTCUT_ID_2 = "ghi";
    private static final String NOTIFICATION_CHANNEL_ID_2 = "test : ghi";
    private static final LocusId LOCUS_ID_2 = new LocusId("jkl");
    private static final Uri CONTACT_URI_2 = Uri.parse("tel:+3234567890");
    private static final String PHONE_NUMBER_2 = "+3234567890";

    private static final String SHORTCUT_ID_3 = "mno";
    private static final String NOTIFICATION_CHANNEL_ID_3 = "test : mno";
    private static final LocusId LOCUS_ID_3 = new LocusId("pqr");
    private static final Uri CONTACT_URI_3 = Uri.parse("tel:+9234567890");
    private static final String PHONE_NUMBER_3 = "+9234567890";

    private MockScheduledExecutorService mMockScheduledExecutorService;
    private ConversationStore mConversationStore;
    private File mFile;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getContext();
        mFile = new File(ctx.getCacheDir(), "testdir");
        resetConversationStore();
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mFile);
    }

    @Test
    public void testAddConversation() {
        mConversationStore.addOrUpdate(buildConversationInfo(SHORTCUT_ID));

        ConversationInfo out = mConversationStore.getConversation(SHORTCUT_ID);
        assertNotNull(out);
        assertEquals(SHORTCUT_ID, out.getShortcutId());
    }

    @Test
    public void testUpdateConversation() {
        ConversationInfo original = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, null);
        mConversationStore.addOrUpdate(original);
        assertEquals(LOCUS_ID, mConversationStore.getConversation(SHORTCUT_ID).getLocusId());
        assertNull(mConversationStore.getConversation(SHORTCUT_ID).getNotificationChannelId());

        LocusId newLocusId = new LocusId("ghi");
        ConversationInfo update = buildConversationInfo(
                SHORTCUT_ID, newLocusId, CONTACT_URI, PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        mConversationStore.addOrUpdate(update);
        ConversationInfo updated = mConversationStore.getConversation(SHORTCUT_ID);
        assertEquals(newLocusId, updated.getLocusId());
        assertEquals(NOTIFICATION_CHANNEL_ID, updated.getNotificationChannelId());
    }

    @Test
    public void testDeleteConversation() {
        mConversationStore.addOrUpdate(buildConversationInfo(SHORTCUT_ID));
        assertNotNull(mConversationStore.getConversation(SHORTCUT_ID));

        mConversationStore.deleteConversation(SHORTCUT_ID);
        assertNull(mConversationStore.getConversation(SHORTCUT_ID));
    }

    @Test
    public void testForAllConversations() {
        mConversationStore.addOrUpdate(buildConversationInfo("a"));
        mConversationStore.addOrUpdate(buildConversationInfo("b"));
        mConversationStore.addOrUpdate(buildConversationInfo("c"));

        Set<String> shortcutIds = new ArraySet<>();

        mConversationStore.forAllConversations(
                conversationInfo -> shortcutIds.add(conversationInfo.getShortcutId()));
        assertTrue(shortcutIds.contains("a"));
        assertTrue(shortcutIds.contains("b"));
        assertTrue(shortcutIds.contains("c"));
    }

    @Test
    public void testGetConversationByLocusId() {
        ConversationInfo in = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        mConversationStore.addOrUpdate(in);
        ConversationInfo out = mConversationStore.getConversationByLocusId(LOCUS_ID);
        assertNotNull(out);
        assertEquals(SHORTCUT_ID, out.getShortcutId());

        mConversationStore.deleteConversation(SHORTCUT_ID);
        assertNull(mConversationStore.getConversationByLocusId(LOCUS_ID));
    }

    @Test
    public void testGetConversationByContactUri() {
        ConversationInfo in = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        mConversationStore.addOrUpdate(in);
        ConversationInfo out = mConversationStore.getConversationByContactUri(CONTACT_URI);
        assertNotNull(out);
        assertEquals(SHORTCUT_ID, out.getShortcutId());

        mConversationStore.deleteConversation(SHORTCUT_ID);
        assertNull(mConversationStore.getConversationByContactUri(CONTACT_URI));
    }

    @Test
    public void testGetConversationByPhoneNumber() {
        ConversationInfo in = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        mConversationStore.addOrUpdate(in);
        ConversationInfo out = mConversationStore.getConversationByPhoneNumber(PHONE_NUMBER);
        assertNotNull(out);
        assertEquals(SHORTCUT_ID, out.getShortcutId());

        mConversationStore.deleteConversation(SHORTCUT_ID);
        assertNull(mConversationStore.getConversationByPhoneNumber(PHONE_NUMBER));
    }

    @Test
    public void testGetConversationByNotificationChannelId() {
        ConversationInfo in = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        mConversationStore.addOrUpdate(in);
        ConversationInfo out = mConversationStore.getConversationByNotificationChannelId(
                NOTIFICATION_CHANNEL_ID);
        assertNotNull(out);
        assertEquals(SHORTCUT_ID, out.getShortcutId());

        mConversationStore.deleteConversation(SHORTCUT_ID);
        assertNull(
                mConversationStore.getConversationByNotificationChannelId(NOTIFICATION_CHANNEL_ID));
    }

    @Test
    public void testDataPersistenceAndRestoration() {
        // Add conversation infos, causing it to be loaded to disk.
        ConversationInfo in1 = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        ConversationInfo in2 = buildConversationInfo(SHORTCUT_ID_2, LOCUS_ID_2, CONTACT_URI_2,
                PHONE_NUMBER_2, NOTIFICATION_CHANNEL_ID_2);
        ConversationInfo in3 = buildConversationInfo(SHORTCUT_ID_3, LOCUS_ID_3, CONTACT_URI_3,
                PHONE_NUMBER_3, NOTIFICATION_CHANNEL_ID_3);
        mConversationStore.addOrUpdate(in1);
        mConversationStore.addOrUpdate(in2);
        mConversationStore.addOrUpdate(in3);

        long futuresExecuted = mMockScheduledExecutorService.fastForwardTime(
                3L * DateUtils.MINUTE_IN_MILLIS);
        assertEquals(1, futuresExecuted);

        mMockScheduledExecutorService.resetTimeElapsedMillis();

        // During restoration, we want to confirm that this conversation was removed.
        mConversationStore.deleteConversation(SHORTCUT_ID_3);
        mMockScheduledExecutorService.fastForwardTime(3L * DateUtils.MINUTE_IN_MILLIS);

        resetConversationStore();
        ConversationInfo out1 = mConversationStore.getConversation(SHORTCUT_ID);
        ConversationInfo out2 = mConversationStore.getConversation(SHORTCUT_ID_2);
        ConversationInfo out3 = mConversationStore.getConversation(SHORTCUT_ID_3);
        mConversationStore.deleteConversation(SHORTCUT_ID);
        mConversationStore.deleteConversation(SHORTCUT_ID_2);
        mConversationStore.deleteConversation(SHORTCUT_ID_3);
        assertEquals(in1, out1);
        assertEquals(in2, out2);
        assertNull(out3);
    }

    @Test
    public void testDelayedDiskWrites() {
        ConversationInfo in1 = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        ConversationInfo in2 = buildConversationInfo(SHORTCUT_ID_2, LOCUS_ID_2, CONTACT_URI_2,
                PHONE_NUMBER_2, NOTIFICATION_CHANNEL_ID_2);
        ConversationInfo in3 = buildConversationInfo(SHORTCUT_ID_3, LOCUS_ID_3, CONTACT_URI_3,
                PHONE_NUMBER_3, NOTIFICATION_CHANNEL_ID_3);

        mConversationStore.addOrUpdate(in1);
        mMockScheduledExecutorService.fastForwardTime(3L * DateUtils.MINUTE_IN_MILLIS);
        mMockScheduledExecutorService.resetTimeElapsedMillis();

        // Should not see second conversation on disk because of disk write delay has not been
        // reached.
        mConversationStore.addOrUpdate(in2);
        mMockScheduledExecutorService.fastForwardTime(DateUtils.MINUTE_IN_MILLIS);

        resetConversationStore();
        ConversationInfo out1 = mConversationStore.getConversation(SHORTCUT_ID);
        ConversationInfo out2 = mConversationStore.getConversation(SHORTCUT_ID_2);
        assertEquals(in1, out1);
        assertNull(out2);

        mConversationStore.addOrUpdate(in2);
        mMockScheduledExecutorService.fastForwardTime(3L * DateUtils.MINUTE_IN_MILLIS);
        mMockScheduledExecutorService.resetTimeElapsedMillis();

        mConversationStore.addOrUpdate(in3);
        mMockScheduledExecutorService.fastForwardTime(3L * DateUtils.MINUTE_IN_MILLIS);

        resetConversationStore();
        out1 = mConversationStore.getConversation(SHORTCUT_ID);
        out2 = mConversationStore.getConversation(SHORTCUT_ID_2);
        ConversationInfo out3 = mConversationStore.getConversation(SHORTCUT_ID_3);
        assertEquals(in1, out1);
        assertEquals(in2, out2);
        assertEquals(in3, out3);
    }

    @Test
    public void testMimicDevicePowerOff() {

        // Even without fast forwarding time with our mock ScheduledExecutorService, we should
        // see the conversations immediately saved to disk.
        ConversationInfo in1 = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        ConversationInfo in2 = buildConversationInfo(SHORTCUT_ID_2, LOCUS_ID_2, CONTACT_URI_2,
                PHONE_NUMBER_2, NOTIFICATION_CHANNEL_ID_2);

        mConversationStore.addOrUpdate(in1);
        mConversationStore.addOrUpdate(in2);
        mConversationStore.saveConversationsToDisk();

        // Ensure that futures were cancelled and the immediate flush occurred.
        assertEquals(0, mMockScheduledExecutorService.getFutures().size());

        // Expect to see 1 execute: saveConversationsToDisk.
        assertEquals(1, mMockScheduledExecutorService.getExecutes().size());

        resetConversationStore();
        ConversationInfo out1 = mConversationStore.getConversation(SHORTCUT_ID);
        ConversationInfo out2 = mConversationStore.getConversation(SHORTCUT_ID_2);
        assertEquals(in1, out1);
        assertEquals(in2, out2);
    }

    @Test
    public void testBackupAndRestore() {
        ConversationInfo in1 = buildConversationInfo(SHORTCUT_ID, LOCUS_ID, CONTACT_URI,
                PHONE_NUMBER, NOTIFICATION_CHANNEL_ID);
        ConversationInfo in2 = buildConversationInfo(SHORTCUT_ID_2, LOCUS_ID_2, CONTACT_URI_2,
                PHONE_NUMBER_2, NOTIFICATION_CHANNEL_ID_2);
        mConversationStore.addOrUpdate(in1);
        mConversationStore.addOrUpdate(in2);

        byte[] backupPayload = mConversationStore.getBackupPayload();
        assertNotNull(backupPayload);

        ConversationStore conversationStore = new ConversationStore(mFile,
                mMockScheduledExecutorService);
        ConversationInfo out1 = conversationStore.getConversation(SHORTCUT_ID);
        assertNull(out1);

        conversationStore.restore(backupPayload);
        out1 = conversationStore.getConversation(SHORTCUT_ID);
        ConversationInfo out2 = conversationStore.getConversation(SHORTCUT_ID_2);
        assertEquals(in1, out1);
        assertEquals(in2, out2);
    }

    private void resetConversationStore() {
        mFile.mkdir();
        mMockScheduledExecutorService = new MockScheduledExecutorService();
        mConversationStore = new ConversationStore(mFile, mMockScheduledExecutorService);
        mConversationStore.loadConversationsFromDisk();
    }

    private static ConversationInfo buildConversationInfo(String shortcutId) {
        return buildConversationInfo(shortcutId, null, null, null, null);
    }

    private static ConversationInfo buildConversationInfo(
            String shortcutId, LocusId locusId, Uri contactUri, String phoneNumber,
            String notificationChannelId) {
        return new ConversationInfo.Builder()
                .setShortcutId(shortcutId)
                .setLocusId(locusId)
                .setContactUri(contactUri)
                .setContactPhoneNumber(phoneNumber)
                .setNotificationChannelId(notificationChannelId)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED)
                .setImportant(true)
                .setBubbled(true)
                .build();
    }
}
