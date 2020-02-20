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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.List;

@RunWith(JUnit4.class)
public final class PackageDataTest {

    private static final String PACKAGE_NAME = "com.google.test";
    private static final int USER_ID = 0;
    private static final String SHORTCUT_ID = "abc";
    private static final LocusId LOCUS_ID = new LocusId("def");
    private static final Uri CONTACT_URI = Uri.parse("tel:+1234567890");
    private static final String PHONE_NUMBER = "+1234567890";

    private Event mE1;
    private Event mE2;
    private Event mE3;
    private Event mE4;
    private boolean mIsDefaultDialer;
    private boolean mIsDefaultSmsApp;

    private PackageData mPackageData;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getContext();
        File testDir = new File(ctx.getCacheDir(), "testdir");
        testDir.mkdir();
        mPackageData = new PackageData(
                PACKAGE_NAME, USER_ID, pkg -> mIsDefaultDialer, pkg -> mIsDefaultSmsApp,
                new MockScheduledExecutorService(), testDir);
        ConversationInfo conversationInfo = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(LOCUS_ID)
                .setContactUri(CONTACT_URI)
                .setContactPhoneNumber(PHONE_NUMBER)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED)
                .build();
        mPackageData.getConversationStore().addOrUpdate(conversationInfo);

        long currentTimestamp = System.currentTimeMillis();
        mE1 = new Event(currentTimestamp - 800L, Event.TYPE_SHORTCUT_INVOCATION);
        mE2 = new Event(currentTimestamp - 700L, Event.TYPE_NOTIFICATION_OPENED);
        mE3 = new Event(currentTimestamp - 600L, Event.TYPE_CALL_INCOMING);
        mE4 = new Event(currentTimestamp - 500L, Event.TYPE_SMS_OUTGOING);
    }

    @Test
    public void testGetEventHistory() {
        EventStore eventStore = mPackageData.getEventStore();
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SHORTCUT_BASED, SHORTCUT_ID)
                .addEvent(mE1);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_LOCUS_ID_BASED, LOCUS_ID.getId())
                .addEvent(mE2);

        EventHistory eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        List<Event> events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(2, events.size());
        assertEventEquals(mE1, events.get(0));
        assertEventEquals(mE2, events.get(1));
    }

    @Test
    public void testGetEventHistoryDefaultDialerAndSmsApp() {
        mIsDefaultDialer = true;
        mIsDefaultSmsApp = true;
        EventStore eventStore = mPackageData.getEventStore();
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SHORTCUT_BASED, SHORTCUT_ID)
                .addEvent(mE1);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_CALL, PHONE_NUMBER).addEvent(mE3);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SMS, PHONE_NUMBER).addEvent(mE4);

        assertTrue(mPackageData.isDefaultDialer());
        assertTrue(mPackageData.isDefaultSmsApp());
        EventHistory eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        List<Event> events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(3, events.size());
        assertEventEquals(mE1, events.get(0));
        assertEventEquals(mE3, events.get(1));
        assertEventEquals(mE4, events.get(2));
    }

    @Test
    public void testGetEventHistoryNotDefaultDialerOrSmsApp() {
        EventStore eventStore = mPackageData.getEventStore();
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SHORTCUT_BASED, SHORTCUT_ID)
                .addEvent(mE1);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_CALL, PHONE_NUMBER).addEvent(mE3);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SMS, PHONE_NUMBER).addEvent(mE4);

        assertFalse(mPackageData.isDefaultDialer());
        assertFalse(mPackageData.isDefaultSmsApp());
        EventHistory eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        List<Event> events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEventEquals(mE1, events.get(0));
    }

    @Test
    public void testDeleteConversationData() {
        mIsDefaultDialer = true;
        mIsDefaultSmsApp = true;
        EventStore eventStore = mPackageData.getEventStore();
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SHORTCUT_BASED, SHORTCUT_ID)
                .addEvent(mE1);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_LOCUS_ID_BASED, LOCUS_ID.getId())
                .addEvent(mE2);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_CALL, PHONE_NUMBER).addEvent(mE3);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SMS, PHONE_NUMBER).addEvent(mE4);

        EventHistory eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        List<Event> events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(4, events.size());

        mPackageData.deleteDataForConversation(SHORTCUT_ID);

        eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertTrue(events.isEmpty());
    }

    @Test
    public void testPruneOrphanEvents() {
        mIsDefaultDialer = true;
        mIsDefaultSmsApp = true;
        EventStore eventStore = mPackageData.getEventStore();
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SHORTCUT_BASED, SHORTCUT_ID)
                .addEvent(mE1);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_LOCUS_ID_BASED, LOCUS_ID.getId())
                .addEvent(mE2);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_CALL, PHONE_NUMBER).addEvent(mE3);
        eventStore.getOrCreateEventHistory(EventStore.CATEGORY_SMS, PHONE_NUMBER).addEvent(mE4);

        EventHistory eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        List<Event> events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(4, events.size());

        ConversationInfo conversationInfo = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setLocusId(null)
                .setContactUri(null)
                .setContactPhoneNumber(null)
                .setShortcutFlags(ShortcutInfo.FLAG_LONG_LIVED)
                .build();
        mPackageData.getConversationStore().addOrUpdate(conversationInfo);
        mPackageData.pruneOrphanEvents();
        eventHistory = mPackageData.getEventHistory(SHORTCUT_ID);
        events = eventHistory.queryEvents(Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        // Only the shortcut based event is kept. All the other events are deleted.
        assertEventEquals(mE1, events.get(0));
    }

    private void assertEventEquals(Event expected, Event actual) {
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getType(), actual.getType());
    }
}
