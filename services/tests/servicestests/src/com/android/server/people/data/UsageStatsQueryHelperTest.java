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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.content.LocusId;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

@RunWith(JUnit4.class)
public final class UsageStatsQueryHelperTest {

    private static final int USER_ID_PRIMARY = 0;
    private static final String PKG_NAME = "pkg";
    private static final String ACTIVITY_NAME = "TestActivity";
    private static final String SHORTCUT_ID = "abc";
    private static final String NOTIFICATION_CHANNEL_ID = "test : abc";
    private static final LocusId LOCUS_ID_1 = new LocusId("locus_1");
    private static final LocusId LOCUS_ID_2 = new LocusId("locus_2");

    @Mock private UsageStatsManagerInternal mUsageStatsManagerInternal;

    private TestPackageData mPackageData;
    private UsageStatsQueryHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        addLocalServiceMock(UsageStatsManagerInternal.class, mUsageStatsManagerInternal);

        Context ctx = InstrumentationRegistry.getContext();
        File testDir = new File(ctx.getCacheDir(), "testdir");
        ScheduledExecutorService scheduledExecutorService = new MockScheduledExecutorService();
        ContactsQueryHelper helper = new ContactsQueryHelper(ctx);

        mPackageData = new TestPackageData(PKG_NAME, USER_ID_PRIMARY, pkg -> false, pkg -> false,
                scheduledExecutorService, testDir, helper);
        mPackageData.mConversationStore.mConversationInfo = new ConversationInfo.Builder()
                .setShortcutId(SHORTCUT_ID)
                .setNotificationChannelId(NOTIFICATION_CHANNEL_ID)
                .setLocusId(LOCUS_ID_1)
                .build();

        mHelper = new UsageStatsQueryHelper(USER_ID_PRIMARY, pkg -> mPackageData);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
    }

    @Test
    public void testQueryNoEvents() {
        assertFalse(mHelper.querySince(50L));
    }

    @Test
    public void testQueryShortcutInvocationEvent() {
        addUsageEvents(createShortcutInvocationEvent(100L));

        assertTrue(mHelper.querySince(50L));
        assertEquals(100L, mHelper.getLastEventTimestamp());
        Event expectedEvent = new Event(100L, Event.TYPE_SHORTCUT_INVOCATION);
        List<Event> events = mPackageData.mEventStore.mShortcutEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEquals(expectedEvent, events.get(0));
    }

    @Test
    public void testQueryNotificationInterruptionEvent() {
        addUsageEvents(createNotificationInterruptionEvent(100L));

        assertTrue(mHelper.querySince(50L));
        assertEquals(100L, mHelper.getLastEventTimestamp());
        Event expectedEvent = new Event(100L, Event.TYPE_NOTIFICATION_POSTED);
        List<Event> events = mPackageData.mEventStore.mShortcutEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEquals(expectedEvent, events.get(0));
    }

    @Test
    public void testInAppConversationSwitch() {
        addUsageEvents(
                createLocusIdSetEvent(100_000L, LOCUS_ID_1.getId()),
                createLocusIdSetEvent(110_000L, LOCUS_ID_2.getId()));

        assertTrue(mHelper.querySince(50_000L));
        assertEquals(110_000L, mHelper.getLastEventTimestamp());
        List<Event> events = mPackageData.mEventStore.mLocusEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEquals(createInAppConversationEvent(100_000L, 10), events.get(0));
    }

    @Test
    public void testInAppConversationExplicitlyEnd() {
        addUsageEvents(
                createLocusIdSetEvent(100_000L, LOCUS_ID_1.getId()),
                createLocusIdSetEvent(110_000L, null));

        assertTrue(mHelper.querySince(50_000L));
        assertEquals(110_000L, mHelper.getLastEventTimestamp());
        List<Event> events = mPackageData.mEventStore.mLocusEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEquals(createInAppConversationEvent(100_000L, 10), events.get(0));
    }

    @Test
    public void testInAppConversationImplicitlyEnd() {
        addUsageEvents(
                createLocusIdSetEvent(100_000L, LOCUS_ID_1.getId()),
                createActivityStoppedEvent(110_000L));

        assertTrue(mHelper.querySince(50_000L));
        assertEquals(110_000L, mHelper.getLastEventTimestamp());
        List<Event> events = mPackageData.mEventStore.mLocusEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEquals(createInAppConversationEvent(100_000L, 10), events.get(0));
    }

    @Test
    public void testMultipleInAppConversations() {
        addUsageEvents(
                createLocusIdSetEvent(100_000L, LOCUS_ID_1.getId()),
                createLocusIdSetEvent(110_000L, LOCUS_ID_2.getId()),
                createLocusIdSetEvent(130_000L, LOCUS_ID_1.getId()),
                createActivityStoppedEvent(160_000L));

        assertTrue(mHelper.querySince(50_000L));
        assertEquals(160_000L, mHelper.getLastEventTimestamp());
        List<Event> events = mPackageData.mEventStore.mLocusEventHistory.queryEvents(
                Event.ALL_EVENT_TYPES, 0L, Long.MAX_VALUE);
        assertEquals(3, events.size());
        assertEquals(createInAppConversationEvent(100_000L, 10), events.get(0));
        assertEquals(createInAppConversationEvent(110_000L, 20), events.get(1));
        assertEquals(createInAppConversationEvent(130_000L, 30), events.get(2));
    }

    private void addUsageEvents(UsageEvents.Event... events) {
        UsageEvents usageEvents = new UsageEvents(Arrays.asList(events), new String[]{});
        when(mUsageStatsManagerInternal.queryEventsForUser(anyInt(), anyLong(), anyLong(),
                anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(usageEvents);
    }

    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private static UsageEvents.Event createShortcutInvocationEvent(long timestamp) {
        UsageEvents.Event e = createUsageEvent(UsageEvents.Event.SHORTCUT_INVOCATION, timestamp);
        e.mShortcutId = SHORTCUT_ID;
        return e;
    }

    private static UsageEvents.Event createNotificationInterruptionEvent(long timestamp) {
        UsageEvents.Event e = createUsageEvent(UsageEvents.Event.NOTIFICATION_INTERRUPTION,
                timestamp);
        e.mNotificationChannelId = NOTIFICATION_CHANNEL_ID;
        return e;
    }

    private static UsageEvents.Event createLocusIdSetEvent(long timestamp, String locusId) {
        UsageEvents.Event e = createUsageEvent(UsageEvents.Event.LOCUS_ID_SET, timestamp);
        e.mClass = ACTIVITY_NAME;
        e.mLocusId = locusId;
        return e;
    }

    private static UsageEvents.Event createActivityStoppedEvent(long timestamp) {
        UsageEvents.Event e = createUsageEvent(UsageEvents.Event.ACTIVITY_STOPPED, timestamp);
        e.mClass = ACTIVITY_NAME;
        return e;
    }

    private static UsageEvents.Event createUsageEvent(int eventType, long timestamp) {
        UsageEvents.Event e = new UsageEvents.Event(eventType, timestamp);
        e.mPackage = PKG_NAME;
        return e;
    }

    private static Event createInAppConversationEvent(long timestamp, int durationSeconds) {
        return new Event.Builder(timestamp, Event.TYPE_IN_APP_CONVERSATION)
                .setDurationSeconds(durationSeconds)
                .build();
    }

    private static class TestConversationStore extends ConversationStore {

        private ConversationInfo mConversationInfo;

        TestConversationStore(File packageDir,
                ScheduledExecutorService scheduledExecutorService,
                ContactsQueryHelper helper) {
            super(packageDir, scheduledExecutorService, helper);
        }

        @Override
        @Nullable
        ConversationInfo getConversation(@Nullable String shortcutId) {
            return mConversationInfo;
        }
    }

    private static class TestPackageData extends PackageData {

        private final TestConversationStore mConversationStore;
        private final TestEventStore mEventStore = new TestEventStore();

        TestPackageData(@NonNull String packageName, @UserIdInt int userId,
                @NonNull Predicate<String> isDefaultDialerPredicate,
                @NonNull Predicate<String> isDefaultSmsAppPredicate,
                @NonNull ScheduledExecutorService scheduledExecutorService, @NonNull File rootDir,
                @NonNull ContactsQueryHelper helper) {
            super(packageName, userId, isDefaultDialerPredicate, isDefaultSmsAppPredicate,
                    scheduledExecutorService, rootDir, helper);
            mConversationStore = new TestConversationStore(rootDir, scheduledExecutorService,
                    helper);
        }

        @Override
        @NonNull
        ConversationStore getConversationStore() {
            return mConversationStore;
        }

        @Override
        @NonNull
        EventStore getEventStore() {
            return mEventStore;
        }
    }

    private static class TestEventStore extends EventStore {

        private final EventHistoryImpl mShortcutEventHistory = new TestEventHistoryImpl();
        private final EventHistoryImpl mLocusEventHistory = new TestEventHistoryImpl();

        @Override
        @NonNull
        EventHistoryImpl getOrCreateShortcutEventHistory(String shortcutId) {
            return mShortcutEventHistory;
        }

        @Override
        @NonNull
        EventHistoryImpl getOrCreateLocusEventHistory(LocusId locusId) {
            return mLocusEventHistory;
        }
    }

    private static class TestEventHistoryImpl extends EventHistoryImpl {

        private final List<Event> mEvents = new ArrayList<>();

        @Override
        @NonNull
        public List<Event> queryEvents(Set<Integer> eventTypes, long startTime, long endTime) {
            return mEvents;
        }

        @Override
        void addEvent(Event event) {
            mEvents.add(event);
        }
    }
}
