/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CLICK;

import static com.android.systemui.statusbar.notification.collection.NotifCollection.REASON_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationStats;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.NoManSimulator.NotifEvent;
import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.notifcollection.CoalescedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.GroupCoalescer;
import com.android.systemui.statusbar.notification.collection.notifcollection.GroupCoalescer.BatchableNotificationHandler;
import com.android.systemui.util.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotifCollectionTest extends SysuiTestCase {

    @Mock private IStatusBarService mStatusBarService;
    @Mock private GroupCoalescer mGroupCoalescer;
    @Spy private RecordingCollectionListener mCollectionListener;
    @Mock private CollectionReadyForBuildListener mBuildListener;

    @Spy private RecordingLifetimeExtender mExtender1 = new RecordingLifetimeExtender("Extender1");
    @Spy private RecordingLifetimeExtender mExtender2 = new RecordingLifetimeExtender("Extender2");
    @Spy private RecordingLifetimeExtender mExtender3 = new RecordingLifetimeExtender("Extender3");

    @Captor private ArgumentCaptor<BatchableNotificationHandler> mListenerCaptor;
    @Captor private ArgumentCaptor<NotificationEntry> mEntryCaptor;
    @Captor private ArgumentCaptor<Collection<NotificationEntry>> mBuildListCaptor;

    private NotifCollection mCollection;
    private BatchableNotificationHandler mNotifHandler;

    private NoManSimulator mNoMan;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Assert.sMainLooper = TestableLooper.get(this).getLooper();

        mCollection = new NotifCollection(mStatusBarService);
        mCollection.attach(mGroupCoalescer);
        mCollection.addCollectionListener(mCollectionListener);
        mCollection.setBuildListener(mBuildListener);

        // Capture the listener object that the collection registers with the listener service so
        // we can simulate listener service events in tests below
        verify(mGroupCoalescer).setNotificationHandler(mListenerCaptor.capture());
        mNotifHandler = requireNonNull(mListenerCaptor.getValue());

        mNoMan = new NoManSimulator();
        mNoMan.addListener(mNotifHandler);
    }

    @Test
    public void testEventDispatchedWhenNotifPosted() {
        // WHEN a notification is posted
        NotifEvent notif1 = mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 3)
                        .setRank(4747));

        // THEN the listener is notified
        verify(mCollectionListener).onEntryAdded(mEntryCaptor.capture());

        NotificationEntry entry = mEntryCaptor.getValue();
        assertEquals(notif1.key, entry.getKey());
        assertEquals(notif1.sbn, entry.getSbn());
        assertEquals(notif1.ranking, entry.getRanking());
    }

    @Test
    public void testEventDispatchedWhenNotifBatchPosted() {
        // GIVEN a NotifCollection with one notif already posted
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 2)
                .setGroup(mContext, "group_1")
                .setContentTitle(mContext, "Old version"));

        clearInvocations(mCollectionListener);
        clearInvocations(mBuildListener);

        // WHEN three notifications from the same group are posted (one of them an update, two of
        // them new)
        NotificationEntry entry1 = buildNotif(TEST_PACKAGE, 1)
                .setGroup(mContext, "group_1")
                .build();
        NotificationEntry entry2 = buildNotif(TEST_PACKAGE, 2)
                .setGroup(mContext, "group_1")
                .setContentTitle(mContext, "New version")
                .build();
        NotificationEntry entry3 = buildNotif(TEST_PACKAGE, 3)
                .setGroup(mContext, "group_1")
                .build();

        mNotifHandler.onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(entry1.getKey(), 0, entry1.getSbn(), entry1.getRanking(), null),
                new CoalescedEvent(entry2.getKey(), 1, entry2.getSbn(), entry2.getRanking(), null),
                new CoalescedEvent(entry3.getKey(), 2, entry3.getSbn(), entry3.getRanking(), null)
        ));

        // THEN onEntryAdded is called on the new ones
        verify(mCollectionListener, times(2)).onEntryAdded(mEntryCaptor.capture());

        List<NotificationEntry> capturedAdds = mEntryCaptor.getAllValues();

        assertEquals(entry1.getSbn(), capturedAdds.get(0).getSbn());
        assertEquals(entry1.getRanking(), capturedAdds.get(0).getRanking());

        assertEquals(entry3.getSbn(), capturedAdds.get(1).getSbn());
        assertEquals(entry3.getRanking(), capturedAdds.get(1).getRanking());

        // THEN onEntryUpdated is called on the middle one
        verify(mCollectionListener).onEntryUpdated(mEntryCaptor.capture());
        NotificationEntry capturedUpdate = mEntryCaptor.getValue();
        assertEquals(entry2.getSbn(), capturedUpdate.getSbn());
        assertEquals(entry2.getRanking(), capturedUpdate.getRanking());

        // THEN onBuildList is called only once
        verify(mBuildListener).onBuildList(mBuildListCaptor.capture());
        assertEquals(new ArraySet<>(Arrays.asList(
                capturedAdds.get(0),
                capturedAdds.get(1),
                capturedUpdate
        )), new ArraySet<>(mBuildListCaptor.getValue()));
    }

    @Test
    public void testEventDispatchedWhenNotifUpdated() {
        // GIVEN a collection with one notif
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3)
                .setRank(4747));

        // WHEN the notif is reposted
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3)
                .setRank(89));

        // THEN the listener is notified
        verify(mCollectionListener).onEntryUpdated(mEntryCaptor.capture());

        NotificationEntry entry = mEntryCaptor.getValue();
        assertEquals(notif2.key, entry.getKey());
        assertEquals(notif2.sbn, entry.getSbn());
        assertEquals(notif2.ranking, entry.getRanking());
    }

    @Test
    public void testEventDispatchedWhenNotifRemoved() {
        // GIVEN a collection with one notif
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3));
        clearInvocations(mCollectionListener);

        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);
        clearInvocations(mCollectionListener);

        // WHEN a notif is retracted
        mNoMan.retractNotif(notif.sbn, REASON_APP_CANCEL);

        // THEN the listener is notified
        verify(mCollectionListener).onEntryRemoved(entry, REASON_APP_CANCEL, false);
        assertEquals(notif.sbn, entry.getSbn());
        assertEquals(notif.ranking, entry.getRanking());
    }

    @Test
    public void testRankingsAreUpdatedForOtherNotifs() {
        // GIVEN a collection with one notif
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3)
                .setRank(47));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN a new notif is posted, triggering a rerank
        mNoMan.setRanking(notif1.sbn.getKey(), new RankingBuilder(notif1.ranking)
                .setRank(56)
                .build());
        mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 77));

        // THEN the ranking is updated on the first entry
        assertEquals(56, entry1.getRanking().getRank());
    }

    @Test
    public void testRankingUpdateIsProperlyIssuedToEveryone() {
        // GIVEN a collection with a couple notifs
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3)
                .setRank(3));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 8)
                .setRank(2));
        NotifEvent notif3 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 77)
                .setRank(1));

        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);
        NotificationEntry entry3 = mCollectionListener.getEntry(notif3.key);

        // WHEN a ranking update is delivered
        Ranking newRanking1 = new RankingBuilder(notif1.ranking)
                .setRank(4)
                .setExplanation("Foo bar")
                .build();
        Ranking newRanking2 = new RankingBuilder(notif2.ranking)
                .setRank(5)
                .setExplanation("baz buzz")
                .build();
        Ranking newRanking3 = new RankingBuilder(notif3.ranking)
                .setRank(6)
                .setExplanation("Penguin pizza")
                .build();

        mNoMan.setRanking(notif1.sbn.getKey(), newRanking1);
        mNoMan.setRanking(notif2.sbn.getKey(), newRanking2);
        mNoMan.setRanking(notif3.sbn.getKey(), newRanking3);
        mNoMan.issueRankingUpdate();

        // THEN all of the NotifEntries have their rankings properly updated
        assertEquals(newRanking1, entry1.getRanking());
        assertEquals(newRanking2, entry2.getRanking());
        assertEquals(newRanking3, entry3.getRanking());
    }

    @Test
    public void testNotifEntriesAreNotPersistedAcrossRemovalAndReposting() {
        // GIVEN a notification that has been posted
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN the notification is retracted and then reposted
        mNoMan.retractNotif(notif1.sbn, REASON_APP_CANCEL);
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3));

        // THEN the new NotificationEntry is a new object
        NotificationEntry entry2 = mCollectionListener.getEntry(notif1.key);
        assertNotEquals(entry2, entry1);
    }

    @Test
    public void testDismissNotification() throws RemoteException {
        // GIVEN a collection with a couple notifications and a lifetime extender
        mCollection.addNotificationLifetimeExtender(mExtender1);

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN a notification is manually dismissed
        DismissedByUserStats stats = new DismissedByUserStats(
                NotificationStats.DISMISSAL_SHADE,
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                NotificationVisibility.obtain(entry2.getKey(), 7, 2, true));

        mCollection.dismissNotification(entry2, REASON_CLICK, stats);

        // THEN we check for lifetime extension
        verify(mExtender1).shouldExtendLifetime(entry2, REASON_CLICK);

        // THEN we send the dismissal to system server
        verify(mStatusBarService).onNotificationClear(
                notif2.sbn.getPackageName(),
                notif2.sbn.getTag(),
                88,
                notif2.sbn.getUser().getIdentifier(),
                notif2.sbn.getKey(),
                stats.dismissalSurface,
                stats.dismissalSentiment,
                stats.notificationVisibility);

        // THEN we fire a remove event
        verify(mCollectionListener).onEntryRemoved(entry2, REASON_CLICK, true);
    }

    @Test(expected = IllegalStateException.class)
    public void testDismissingNonExistentNotificationThrows() {
        // GIVEN a collection that originally had three notifs, but where one was dismissed
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotifEvent notif3 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 99));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);
        mNoMan.retractNotif(notif2.sbn, REASON_UNKNOWN);

        // WHEN we try to dismiss a notification that isn't present
        mCollection.dismissNotification(
                entry2,
                REASON_CLICK,
                new DismissedByUserStats(0, 0, NotificationVisibility.obtain("foo", 47, 3, true)));

        // THEN an exception is thrown
    }

    @Test
    public void testLifetimeExtendersAreQueriedWhenNotifRemoved() {
        // GIVEN a couple notifications and a few lifetime extenders
        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = true;

        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN a notification is removed
        mNoMan.retractNotif(notif2.sbn, REASON_UNKNOWN);

        // THEN each extender is asked whether to extend, even if earlier ones return true
        verify(mExtender1).shouldExtendLifetime(entry2, REASON_UNKNOWN);
        verify(mExtender2).shouldExtendLifetime(entry2, REASON_UNKNOWN);
        verify(mExtender3).shouldExtendLifetime(entry2, REASON_UNKNOWN);

        // THEN the entry is not removed
        assertTrue(mCollection.getNotifs().contains(entry2));

        // THEN the entry properly records all extenders that returned true
        assertEquals(Arrays.asList(mExtender1, mExtender2), entry2.mLifetimeExtenders);
    }

    @Test
    public void testWhenLastLifetimeExtenderExpiresAllAreReQueried() {
        // GIVEN a couple notifications and a few lifetime extenders
        mExtender2.shouldExtendLifetime = true;

        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // GIVEN a notification gets lifetime-extended by one of them
        mNoMan.retractNotif(notif2.sbn, REASON_APP_CANCEL);
        assertTrue(mCollection.getNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN the last active extender expires (but new ones become active)
        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = false;
        mExtender3.shouldExtendLifetime = true;
        mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);

        // THEN each extender is re-queried
        verify(mExtender1).shouldExtendLifetime(entry2, REASON_UNKNOWN);
        verify(mExtender2).shouldExtendLifetime(entry2, REASON_UNKNOWN);
        verify(mExtender3).shouldExtendLifetime(entry2, REASON_UNKNOWN);

        // THEN the entry is not removed
        assertTrue(mCollection.getNotifs().contains(entry2));

        // THEN the entry properly records all extenders that returned true
        assertEquals(Arrays.asList(mExtender1, mExtender3), entry2.mLifetimeExtenders);
    }

    @Test
    public void testExtendersAreNotReQueriedUntilFinalActiveExtenderExpires() {
        // GIVEN a couple notifications and a few lifetime extenders
        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = true;

        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // GIVEN a notification gets lifetime-extended by a couple of them
        mNoMan.retractNotif(notif2.sbn, REASON_APP_CANCEL);
        assertTrue(mCollection.getNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN one (but not all) of the extenders expires
        mExtender2.shouldExtendLifetime = false;
        mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);

        // THEN the entry is not removed
        assertTrue(mCollection.getNotifs().contains(entry2));

        // THEN we don't re-query the extenders
        verify(mExtender1, never()).shouldExtendLifetime(eq(entry2), anyInt());
        verify(mExtender2, never()).shouldExtendLifetime(eq(entry2), anyInt());
        verify(mExtender3, never()).shouldExtendLifetime(eq(entry2), anyInt());

        // THEN the entry properly records all extenders that returned true
        assertEquals(Arrays.asList(mExtender1), entry2.mLifetimeExtenders);
    }

    @Test
    public void testNotificationIsRemovedWhenAllLifetimeExtendersExpire() {
        // GIVEN a couple notifications and a few lifetime extenders
        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = true;

        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // GIVEN a notification gets lifetime-extended by a couple of them
        mNoMan.retractNotif(notif2.sbn, REASON_UNKNOWN);
        assertTrue(mCollection.getNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN all of the active extenders expire
        mExtender2.shouldExtendLifetime = false;
        mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);
        mExtender1.shouldExtendLifetime = false;
        mExtender1.callback.onEndLifetimeExtension(mExtender1, entry2);

        // THEN the entry removed
        assertFalse(mCollection.getNotifs().contains(entry2));
        verify(mCollectionListener).onEntryRemoved(entry2, REASON_UNKNOWN, false);
    }

    @Test
    public void testLifetimeExtensionIsCanceledWhenNotifIsUpdated() {
        // GIVEN a few lifetime extenders and a couple notifications
        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = true;

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // GIVEN a notification gets lifetime-extended by a couple of them
        mNoMan.retractNotif(notif2.sbn, REASON_UNKNOWN);
        assertTrue(mCollection.getNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN the notification is reposted
        mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));

        // THEN all of the active lifetime extenders are canceled
        verify(mExtender1).cancelLifetimeExtension(entry2);
        verify(mExtender2).cancelLifetimeExtension(entry2);

        // THEN the notification is still present
        assertTrue(mCollection.getNotifs().contains(entry2));
    }

    @Test(expected = IllegalStateException.class)
    public void testReentrantCallsToLifetimeExtendersThrow() {
        // GIVEN a few lifetime extenders and a couple notifications
        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = true;

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // GIVEN a notification gets lifetime-extended by a couple of them
        mNoMan.retractNotif(notif2.sbn, REASON_UNKNOWN);
        assertTrue(mCollection.getNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN a lifetime extender makes a reentrant call during cancelLifetimeExtension()
        mExtender2.onCancelLifetimeExtension = () -> {
            mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);
        };
        // This triggers the call to cancelLifetimeExtension()
        mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));

        // THEN an exception is thrown
    }

    @Test
    public void testRankingIsUpdatedWhenALifetimeExtendedNotifIsReposted() {
        // GIVEN a few lifetime extenders and a couple notifications
        mCollection.addNotificationLifetimeExtender(mExtender1);
        mCollection.addNotificationLifetimeExtender(mExtender2);
        mCollection.addNotificationLifetimeExtender(mExtender3);

        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = true;

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // GIVEN a notification gets lifetime-extended by a couple of them
        mNoMan.retractNotif(notif2.sbn, REASON_UNKNOWN);
        assertTrue(mCollection.getNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN the notification is reposted
        NotifEvent notif2a = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88)
                .setRank(4747)
                .setExplanation("Some new explanation"));

        // THEN the notification's ranking is properly updated
        assertEquals(notif2a.ranking, entry2.getRanking());
    }

    private static NotificationEntryBuilder buildNotif(String pkg, int id, String tag) {
        return new NotificationEntryBuilder()
                .setPkg(pkg)
                .setId(id)
                .setTag(tag);
    }

    private static NotificationEntryBuilder buildNotif(String pkg, int id) {
        return new NotificationEntryBuilder()
                .setPkg(pkg)
                .setId(id);
    }

    private static class RecordingCollectionListener implements NotifCollectionListener {
        private final Map<String, NotificationEntry> mLastSeenEntries = new ArrayMap<>();

        @Override
        public void onEntryAdded(NotificationEntry entry) {
            mLastSeenEntries.put(entry.getKey(), entry);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason, boolean removedByUser) {
        }

        public NotificationEntry getEntry(String key) {
            if (!mLastSeenEntries.containsKey(key)) {
                throw new RuntimeException("Key not found: " + key);
            }
            return mLastSeenEntries.get(key);
        }
    }

    private static class RecordingLifetimeExtender implements NotifLifetimeExtender {
        private final String mName;

        public @Nullable OnEndLifetimeExtensionCallback callback;
        public boolean shouldExtendLifetime = false;
        public @Nullable Runnable onCancelLifetimeExtension;

        private RecordingLifetimeExtender(String name) {
            mName = name;
        }

        @Override
        public String getName() {
            return mName;
        }

        @Override
        public void setCallback(OnEndLifetimeExtensionCallback callback) {
            this.callback = callback;
        }

        @Override
        public boolean shouldExtendLifetime(
                NotificationEntry entry,
                @CancellationReason int reason) {
            return shouldExtendLifetime;
        }

        @Override
        public void cancelLifetimeExtension(NotificationEntry entry) {
            if (onCancelLifetimeExtension != null) {
                onCancelLifetimeExtension.run();
            }
        }
    }

    private static final String TEST_PACKAGE = "com.android.test.collection";
    private static final String TEST_PACKAGE2 = "com.android.test.collection2";
}
