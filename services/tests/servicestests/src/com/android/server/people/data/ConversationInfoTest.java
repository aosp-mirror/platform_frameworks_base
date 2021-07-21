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

import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.people.ConversationStatus;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ConversationInfoTest {

    private static final String SHORTCUT_ID = "abc";
    private static final LocusId LOCUS_ID = new LocusId("def");
    private static final Uri CONTACT_URI = Uri.parse("tel:+1234567890");
    private static final String PHONE_NUMBER = "+1234567890";
    private static final String NOTIFICATION_CHANNEL_ID = "test : abc";
    private static final String PARENT_NOTIFICATION_CHANNEL_ID = "test";

    @Test
    public void testBuild() {
        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();
        ConversationStatus cs2 = new ConversationStatus.Builder("id2", ACTIVITY_GAME).build();

        ConversationInfo conversationInfo = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(LOCUS_ID)
                .setContactUri(CONTACT_URI)
                .setContactPhoneNumber(PHONE_NUMBER)
                .setNotificationChannelId(NOTIFICATION_CHANNEL_ID)
                .setParentNotificationChannelId(PARENT_NOTIFICATION_CHANNEL_ID)
                .setLastEventTimestamp(100L)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED
                        | ShortcutInfo.FLAG_CACHED_NOTIFICATIONS)
                .setImportant(true)
                .setNotificationSilenced(true)
                .setBubbled(true)
                .setDemoted(true)
                .setPersonImportant(true)
                .setPersonBot(true)
                .setContactStarred(true)
                .addOrUpdateStatus(cs)
                .addOrUpdateStatus(cs2)
                .build();

        assertEquals(SHORTCUT_ID, conversationInfo.getShortcutId());
        assertEquals(LOCUS_ID, conversationInfo.getLocusId());
        assertEquals(CONTACT_URI, conversationInfo.getContactUri());
        assertEquals(PHONE_NUMBER, conversationInfo.getContactPhoneNumber());
        assertEquals(NOTIFICATION_CHANNEL_ID, conversationInfo.getNotificationChannelId());
        assertEquals(PARENT_NOTIFICATION_CHANNEL_ID,
                conversationInfo.getParentNotificationChannelId());
        assertEquals(100L, conversationInfo.getLastEventTimestamp());
        assertTrue(conversationInfo.isShortcutLongLived());
        assertTrue(conversationInfo.isShortcutCachedForNotification());
        assertTrue(conversationInfo.isImportant());
        assertTrue(conversationInfo.isNotificationSilenced());
        assertTrue(conversationInfo.isBubbled());
        assertTrue(conversationInfo.isDemoted());
        assertTrue(conversationInfo.isPersonImportant());
        assertTrue(conversationInfo.isPersonBot());
        assertTrue(conversationInfo.isContactStarred());
        assertThat(conversationInfo.getStatuses()).contains(cs);
        assertThat(conversationInfo.getStatuses()).contains(cs2);
    }

    @Test
    public void testBuildEmpty() {
        ConversationInfo conversationInfo = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .build();

        assertEquals(SHORTCUT_ID, conversationInfo.getShortcutId());
        assertNull(conversationInfo.getLocusId());
        assertNull(conversationInfo.getContactUri());
        assertNull(conversationInfo.getContactPhoneNumber());
        assertNull(conversationInfo.getNotificationChannelId());
        assertNull(conversationInfo.getParentNotificationChannelId());
        assertEquals(0L, conversationInfo.getLastEventTimestamp());
        assertFalse(conversationInfo.isShortcutLongLived());
        assertFalse(conversationInfo.isShortcutCachedForNotification());
        assertFalse(conversationInfo.isImportant());
        assertFalse(conversationInfo.isNotificationSilenced());
        assertFalse(conversationInfo.isBubbled());
        assertFalse(conversationInfo.isDemoted());
        assertFalse(conversationInfo.isPersonImportant());
        assertFalse(conversationInfo.isPersonBot());
        assertFalse(conversationInfo.isContactStarred());
        assertThat(conversationInfo.getStatuses()).isNotNull();
        assertThat(conversationInfo.getStatuses()).isEmpty();
    }

    @Test
    public void testBuildFromAnotherConversationInfo() {
        ConversationStatus cs = new ConversationStatus.Builder("id", ACTIVITY_ANNIVERSARY).build();
        ConversationStatus cs2 = new ConversationStatus.Builder("id2", ACTIVITY_GAME).build();

        ConversationInfo source = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(LOCUS_ID)
                .setContactUri(CONTACT_URI)
                .setContactPhoneNumber(PHONE_NUMBER)
                .setNotificationChannelId(NOTIFICATION_CHANNEL_ID)
                .setParentNotificationChannelId(PARENT_NOTIFICATION_CHANNEL_ID)
                .setLastEventTimestamp(100L)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED)
                .setImportant(true)
                .setNotificationSilenced(true)
                .setBubbled(true)
                .setPersonImportant(true)
                .setPersonBot(true)
                .setContactStarred(true)
                .addOrUpdateStatus(cs)
                .addOrUpdateStatus(cs2)
                .build();

        ConversationInfo destination = new ConversationInfo.Builder(source)
                .setImportant(false)
                .setContactStarred(false)
                .build();

        assertEquals(SHORTCUT_ID, destination.getShortcutId());
        assertEquals(LOCUS_ID, destination.getLocusId());
        assertEquals(CONTACT_URI, destination.getContactUri());
        assertEquals(PHONE_NUMBER, destination.getContactPhoneNumber());
        assertEquals(NOTIFICATION_CHANNEL_ID, destination.getNotificationChannelId());
        assertEquals(PARENT_NOTIFICATION_CHANNEL_ID, destination.getParentNotificationChannelId());
        assertEquals(100L, destination.getLastEventTimestamp());
        assertTrue(destination.isShortcutLongLived());
        assertFalse(destination.isImportant());
        assertTrue(destination.isNotificationSilenced());
        assertTrue(destination.isBubbled());
        assertTrue(destination.isPersonImportant());
        assertTrue(destination.isPersonBot());
        assertFalse(destination.isContactStarred());
        assertThat(destination.getStatuses()).contains(cs);
        assertThat(destination.getStatuses()).contains(cs2);
    }
}
