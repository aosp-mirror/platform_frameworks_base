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

import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.util.ArraySet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Set;

@RunWith(JUnit4.class)
public final class ConversationStoreTest {

    private static final String SHORTCUT_ID = "abc";
    private static final String NOTIFICATION_CHANNEL_ID = "test : abc";
    private static final LocusId LOCUS_ID = new LocusId("def");
    private static final Uri CONTACT_URI = Uri.parse("tel:+1234567890");
    private static final String PHONE_NUMBER = "+1234567890";

    private ConversationStore mConversationStore;

    @Before
    public void setUp() {
        mConversationStore = new ConversationStore();
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
