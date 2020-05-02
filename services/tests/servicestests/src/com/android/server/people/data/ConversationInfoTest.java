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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void testBuild() {
        ConversationInfo conversationInfo = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(LOCUS_ID)
                .setContactUri(CONTACT_URI)
                .setContactPhoneNumber(PHONE_NUMBER)
                .setNotificationChannelId(NOTIFICATION_CHANNEL_ID)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED | ShortcutInfo.FLAG_CACHED)
                .setImportant(true)
                .setNotificationSilenced(true)
                .setBubbled(true)
                .setDemoted(true)
                .setPersonImportant(true)
                .setPersonBot(true)
                .setContactStarred(true)
                .build();

        assertEquals(SHORTCUT_ID, conversationInfo.getShortcutId());
        assertEquals(LOCUS_ID, conversationInfo.getLocusId());
        assertEquals(CONTACT_URI, conversationInfo.getContactUri());
        assertEquals(PHONE_NUMBER, conversationInfo.getContactPhoneNumber());
        assertEquals(NOTIFICATION_CHANNEL_ID, conversationInfo.getNotificationChannelId());
        assertTrue(conversationInfo.isShortcutLongLived());
        assertTrue(conversationInfo.isShortcutCached());
        assertTrue(conversationInfo.isImportant());
        assertTrue(conversationInfo.isNotificationSilenced());
        assertTrue(conversationInfo.isBubbled());
        assertTrue(conversationInfo.isDemoted());
        assertTrue(conversationInfo.isPersonImportant());
        assertTrue(conversationInfo.isPersonBot());
        assertTrue(conversationInfo.isContactStarred());
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
        assertFalse(conversationInfo.isShortcutLongLived());
        assertFalse(conversationInfo.isShortcutCached());
        assertFalse(conversationInfo.isImportant());
        assertFalse(conversationInfo.isNotificationSilenced());
        assertFalse(conversationInfo.isBubbled());
        assertFalse(conversationInfo.isDemoted());
        assertFalse(conversationInfo.isPersonImportant());
        assertFalse(conversationInfo.isPersonBot());
        assertFalse(conversationInfo.isContactStarred());
    }

    @Test
    public void testBuildFromAnotherConversationInfo() {
        ConversationInfo source = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(LOCUS_ID)
                .setContactUri(CONTACT_URI)
                .setContactPhoneNumber(PHONE_NUMBER)
                .setNotificationChannelId(NOTIFICATION_CHANNEL_ID)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED)
                .setImportant(true)
                .setNotificationSilenced(true)
                .setBubbled(true)
                .setPersonImportant(true)
                .setPersonBot(true)
                .setContactStarred(true)
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
        assertTrue(destination.isShortcutLongLived());
        assertFalse(destination.isImportant());
        assertTrue(destination.isNotificationSilenced());
        assertTrue(destination.isBubbled());
        assertTrue(destination.isPersonImportant());
        assertTrue(destination.isPersonBot());
        assertFalse(destination.isContactStarred());
    }
}
