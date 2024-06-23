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

import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationStats.DISMISSAL_SHADE;
import static android.service.notification.NotificationStats.DISMISS_SENTIMENT_NEUTRAL;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.statusbar.notification.collection.NotifCollection.REASON_NOT_CANCELED;
import static com.android.systemui.statusbar.notification.collection.NotifCollection.REASON_UNKNOWN;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.DISMISSED;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.NOT_DISMISSED;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.PARENT_DISMISSED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.LogBufferEulogizer;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NoManSimulator.NotifEvent;
import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.coalescer.CoalescedEvent;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer.BatchableNotificationHandler;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.InternalNotifUpdater;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionLogger;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotifCollectionTest extends SysuiTestCase {

    @Mock private IStatusBarService mStatusBarService;
    @Mock private NotifPipelineFlags mNotifPipelineFlags;
    private final NotifCollectionLogger mLogger = spy(new NotifCollectionLogger(logcatLogBuffer()));
    @Mock private LogBufferEulogizer mEulogizer;
    @Mock private Handler mMainHandler;

    @Mock private GroupCoalescer mGroupCoalescer;
    @Spy private RecordingCollectionListener mCollectionListener;
    @Mock private CollectionReadyForBuildListener mBuildListener;

    @Spy private RecordingLifetimeExtender mExtender1 = new RecordingLifetimeExtender("Extender1");
    @Spy private RecordingLifetimeExtender mExtender2 = new RecordingLifetimeExtender("Extender2");
    @Spy private RecordingLifetimeExtender mExtender3 = new RecordingLifetimeExtender("Extender3");

    @Spy private RecordingDismissInterceptor mInterceptor1 = new RecordingDismissInterceptor(
            "Interceptor1");
    @Spy private RecordingDismissInterceptor mInterceptor2 = new RecordingDismissInterceptor(
            "Interceptor2");
    @Spy private RecordingDismissInterceptor mInterceptor3 = new RecordingDismissInterceptor(
            "Interceptor3");

    @Captor private ArgumentCaptor<BatchableNotificationHandler> mListenerCaptor;
    @Captor private ArgumentCaptor<NotificationEntry> mEntryCaptor;
    @Captor private ArgumentCaptor<Collection<NotificationEntry>> mBuildListCaptor;

    private NotifCollection mCollection;
    private BatchableNotificationHandler mNotifHandler;

    private InOrder mListenerInOrder;

    private NoManSimulator mNoMan;
    private FakeSystemClock mClock = new FakeSystemClock();
    private FakeExecutor mBgExecutor = new FakeExecutor(mClock);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        when(mEulogizer.record(any(Exception.class))).thenAnswer(i -> i.getArguments()[0]);

        mListenerInOrder = inOrder(mCollectionListener);

        mCollection = new NotifCollection(
                mStatusBarService,
                mClock,
                mNotifPipelineFlags,
                mLogger,
                mMainHandler,
                mBgExecutor,
                mEulogizer,
                mock(DumpManager.class),
                mock(NotificationDismissibilityProvider.class));
        mCollection.attach(mGroupCoalescer);
        mCollection.addCollectionListener(mCollectionListener);
        mCollection.setBuildListener(mBuildListener);

        // Capture the listener object that the collection registers with the listener service so
        // we can simulate listener service events in tests below
        verify(mGroupCoalescer).setNotificationHandler(mListenerCaptor.capture());
        mNotifHandler = requireNonNull(mListenerCaptor.getValue());

        mNoMan = new NoManSimulator();
        mNoMan.addListener(mNotifHandler);

        mNotifHandler.onNotificationsInitialized();
    }

    @Test
    public void testGetGroupSummary() {
        final NotificationEntryBuilder entryBuilder = buildNotif(TEST_PACKAGE, 0)
                .setGroup(mContext, "group")
                .setGroupSummary(mContext, true);
        final String groupKey = entryBuilder.build().getSbn().getGroupKey();
        assertEquals(null, mCollection.getGroupSummary(groupKey));
        NotifEvent summary = mNoMan.postNotif(entryBuilder);

        final NotificationEntry entry = mCollection.getGroupSummary(groupKey);
        assertEquals(summary.key, entry.getKey());
        assertEquals(summary.sbn, entry.getSbn());
        assertEquals(summary.ranking, entry.getRanking());
    }

    @Test
    public void testIsOnlyChildInGroup() {
        final NotificationEntryBuilder entryBuilder = buildNotif(TEST_PACKAGE, 1)
                .setGroup(mContext, "group");
        NotifEvent notif1 = mNoMan.postNotif(entryBuilder);
        final NotificationEntry entry = mCollection.getEntry(notif1.key);
        assertTrue(mCollection.isOnlyChildInGroup(entry));

        // summaries are not counted
        mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, "group")
                        .setGroupSummary(mContext, true));
        assertTrue(mCollection.isOnlyChildInGroup(entry));

        mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 2)
                        .setGroup(mContext, "group"));
        assertFalse(mCollection.isOnlyChildInGroup(entry));
    }

    @Test
    public void testEventDispatchedWhenNotifPosted() {
        // WHEN a notification is posted
        NotifEvent notif1 = mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 3)
                        .setRank(4747));

        // THEN the listener is notified
        final NotificationEntry entry = mCollectionListener.getEntry(notif1.key);

        mListenerInOrder.verify(mCollectionListener).onEntryInit(entry);
        mListenerInOrder.verify(mCollectionListener).onEntryAdded(entry);
        mListenerInOrder.verify(mCollectionListener).onRankingApplied();

        assertEquals(notif1.key, entry.getKey());
        assertEquals(notif1.sbn, entry.getSbn());
        assertEquals(notif1.ranking, entry.getRanking());
    }

    @Test
    public void testCancelNonExistingNotification() {
        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);
        mCollection.dismissNotification(entry, defaultStats(entry));
        mCollection.dismissNotification(entry, defaultStats(entry));
        mCollection.dismissNotification(entry, defaultStats(entry));
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
        verifyBuiltList(
                List.of(
                        capturedAdds.get(0),
                        capturedAdds.get(1),
                        capturedUpdate));
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
        final NotificationEntry entry = mCollectionListener.getEntry(notif2.key);

        mListenerInOrder.verify(mCollectionListener).onEntryUpdated(entry);
        mListenerInOrder.verify(mCollectionListener).onRankingApplied();

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
        mListenerInOrder.verify(mCollectionListener).onEntryRemoved(entry, REASON_APP_CANCEL);
        mListenerInOrder.verify(mCollectionListener).onEntryCleanUp(entry);
        mListenerInOrder.verify(mCollectionListener).onRankingApplied();

        assertEquals(notif.sbn, entry.getSbn());
        assertEquals(notif.ranking, entry.getRanking());
    }

    @Test
    public void testEventDispatchedWhenChannelChanged() {
        // GIVEN a collection with one notif that has a channel
        NotificationEntryBuilder neb = buildNotif(TEST_PACKAGE, 48);
        NotificationChannel channel = new NotificationChannel(
                "channelId",
                "channelName",
                NotificationManager.IMPORTANCE_DEFAULT);
        neb.setChannel(channel);

        NotifEvent notif = mNoMan.postNotif(neb);
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);
        clearInvocations(mCollectionListener);


        // WHEN a notif channel is modified
        channel.setAllowBubbles(true);
        mNoMan.issueChannelModification(
                TEST_PACKAGE,
                entry.getSbn().getUser(),
                channel,
                NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        // THEN the listener is notified
        mListenerInOrder.verify(mCollectionListener).onNotificationChannelModified(
                TEST_PACKAGE,
                entry.getSbn().getUser(),
                channel,
                NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);
    }

    @Test
    public void testScheduleBuildNotificationListWhenChannelChanged() {
        // GIVEN
        final NotificationEntryBuilder neb = buildNotif(TEST_PACKAGE, 48);
        final NotificationChannel channel = new NotificationChannel(
                "channelId",
                "channelName",
                NotificationManager.IMPORTANCE_DEFAULT);
        neb.setChannel(channel);

        final NotifEvent notif = mNoMan.postNotif(neb);
        final NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        when(mMainHandler.hasCallbacks(any())).thenReturn(false);

        clearInvocations(mBuildListener);

        // WHEN
        mNotifHandler.onNotificationChannelModified(TEST_PACKAGE,
                entry.getSbn().getUser(), channel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        // THEN
        verify(mMainHandler).postDelayed(any(), eq(1000L));
    }

    @Test
    public void testCancelScheduledBuildNotificationListEventWhenNotifUpdatedSynchronously() {
        // GIVEN
        final NotificationEntry entry1 = buildNotif(TEST_PACKAGE, 1)
                .setGroup(mContext, "group_1")
                .build();
        final NotificationEntry entry2 = buildNotif(TEST_PACKAGE, 2)
                .setGroup(mContext, "group_1")
                .setContentTitle(mContext, "New version")
                .build();
        final NotificationEntry entry3 = buildNotif(TEST_PACKAGE, 3)
                .setGroup(mContext, "group_1")
                .build();

        final List<CoalescedEvent> entriesToBePosted = Arrays.asList(
                new CoalescedEvent(entry1.getKey(), 0, entry1.getSbn(), entry1.getRanking(), null),
                new CoalescedEvent(entry2.getKey(), 1, entry2.getSbn(), entry2.getRanking(), null),
                new CoalescedEvent(entry3.getKey(), 2, entry3.getSbn(), entry3.getRanking(), null)
        );

        when(mMainHandler.hasCallbacks(any())).thenReturn(true);

        // WHEN
        mNotifHandler.onNotificationBatchPosted(entriesToBePosted);

        // THEN
        verify(mMainHandler).removeCallbacks(any());
    }

    @Test
    public void testBuildNotificationListWhenChannelChanged() {
        // GIVEN
        final NotificationEntryBuilder neb = buildNotif(TEST_PACKAGE, 48);
        final NotificationChannel channel = new NotificationChannel(
                "channelId",
                "channelName",
                NotificationManager.IMPORTANCE_DEFAULT);
        neb.setChannel(channel);

        final NotifEvent notif = mNoMan.postNotif(neb);
        final NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        when(mMainHandler.hasCallbacks(any())).thenReturn(false);
        when(mMainHandler.postDelayed(any(), eq(1000L))).thenAnswer((Answer) invocation -> {
            final Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        });

        clearInvocations(mBuildListener);

        // WHEN
        mNotifHandler.onNotificationChannelModified(TEST_PACKAGE,
                entry.getSbn().getUser(), channel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);

        // THEN
        verifyBuiltList(List.of(entry));
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

        // WHEN entry3's ranking update includes an update to its overrideGroupKey
        final String newOverrideGroupKey = "newOverrideGroupKey";
        Ranking newRanking3 = new RankingBuilder(notif3.ranking)
                .setRank(6)
                .setExplanation("Penguin pizza")
                .setOverrideGroupKey(newOverrideGroupKey)
                .build();

        mNoMan.setRanking(notif1.sbn.getKey(), newRanking1);
        mNoMan.setRanking(notif2.sbn.getKey(), newRanking2);
        mNoMan.setRanking(notif3.sbn.getKey(), newRanking3);
        mNoMan.issueRankingUpdate();

        // THEN all of the NotifEntries have their rankings properly updated
        assertEquals(newRanking1, entry1.getRanking());
        assertEquals(newRanking2, entry2.getRanking());
        assertEquals(newRanking3, entry3.getRanking());

        // THEN the entry3's overrideGroupKey is updated along with its groupKey
        assertEquals(newOverrideGroupKey, entry3.getSbn().getOverrideGroupKey());
        assertNotNull(entry3.getSbn().getGroupKey());
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
    public void testDismissNotificationSentToSystemServer() throws RemoteException {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN a notification is manually dismissed
        DismissedByUserStats stats = defaultStats(entry2);
        mCollection.dismissNotification(entry2, defaultStats(entry2));

        FakeExecutor.exhaustExecutors(mBgExecutor);

        // THEN we send the dismissal to system server
        verify(mStatusBarService).onNotificationClear(
                notif2.sbn.getPackageName(),
                notif2.sbn.getUser().getIdentifier(),
                notif2.sbn.getKey(),
                stats.dismissalSurface,
                stats.dismissalSentiment,
                stats.notificationVisibility);
    }

    @Test
    public void testDismissedNotificationsAreMarkedAsDismissedLocally() {
        // GIVEN a collection with a notification
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN a notification is manually dismissed
        mCollection.dismissNotification(entry1, defaultStats(entry1));

        // THEN the entry is marked as dismissed locally
        assertEquals(DISMISSED, entry1.getDismissState());
    }

    @Test
    public void testDismissedNotificationsCannotBeLifetimeExtended() {
        // GIVEN a collection with a notification and a lifetime extender
        mCollection.addNotificationLifetimeExtender(mExtender1);
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN a notification is manually dismissed
        mCollection.dismissNotification(entry1, defaultStats(entry1));

        // THEN lifetime extenders are never queried
        verify(mExtender1, never()).maybeExtendLifetime(eq(entry1), anyInt());
    }

    @Test
    public void testDismissedNotificationsDoNotTriggerRemovalEvents() {
        // GIVEN a collection with a notification
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN a notification is manually dismissed
        mCollection.dismissNotification(entry1, defaultStats(entry1));

        // THEN onEntryRemoved is not called
        verify(mCollectionListener, never()).onEntryRemoved(eq(entry1), anyInt());
    }

    @Test
    public void testDismissedNotificationsStillAppearInNotificationSet() {
        // GIVEN a collection with a notification
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN a notification is manually dismissed
        mCollection.dismissNotification(entry1, defaultStats(entry1));

        // THEN the dismissed entry still appears in the notification set
        assertEquals(
                new ArraySet<>(singletonList(entry1)),
                new ArraySet<>(mCollection.getAllNotifs()));
    }

    @Test
    public void testRetractingLifetimeExtendedSummaryDoesNotDismissChildren() {
        // GIVEN A notif group with one summary and two children
        mCollection.addNotificationLifetimeExtender(mExtender1);
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1, "myTag")
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif2 = postNotif(
                buildNotif(TEST_PACKAGE, 2, "myTag")
                        .setGroup(mContext, GROUP_1));
        CollectionEvent notif3 = postNotif(
                buildNotif(TEST_PACKAGE, 3, "myTag")
                        .setGroup(mContext, GROUP_1));

        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);
        NotificationEntry entry3 = mCollectionListener.getEntry(notif3.key);

        // GIVEN that the summary and one child are retracted by the app, but both are
        // lifetime-extended
        mExtender1.shouldExtendLifetime = true;
        mNoMan.retractNotif(notif1.sbn, REASON_APP_CANCEL);
        mNoMan.retractNotif(notif2.sbn, REASON_APP_CANCEL);
        assertEquals(
                new ArraySet<>(List.of(entry1, entry2, entry3)),
                new ArraySet<>(mCollection.getAllNotifs()));

        // WHEN the summary is retracted by the app
        mCollection.dismissNotification(entry1, defaultStats(entry1));

        // THEN the summary is removed, but both children stick around
        assertEquals(
                new ArraySet<>(List.of(entry2, entry3)),
                new ArraySet<>(mCollection.getAllNotifs()));
        assertEquals(NOT_DISMISSED, entry2.getDismissState());
        assertEquals(NOT_DISMISSED, entry3.getDismissState());
    }

    @Test
    public void testNMSReportsUserDismissalAlwaysRemovesNotif() throws RemoteException {
        // GIVEN notifications are lifetime extended
        mExtender1.shouldExtendLifetime = true;
        CollectionEvent notif = postNotif(buildNotif(TEST_PACKAGE, 1, "myTag"));
        CollectionEvent notif2 = postNotif(buildNotif(TEST_PACKAGE, 2, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);
        assertEquals(
                new ArraySet<>(List.of(entry, entry2)),
                new ArraySet<>(mCollection.getAllNotifs()));

        // WHEN the notifications are reported to be dismissed by the user by NMS
        mNoMan.retractNotif(notif.sbn, REASON_CANCEL);
        mNoMan.retractNotif(notif2.sbn, REASON_CLICK);

        // THEN the notifications are removed b/c they were dismissed by the user
        assertEquals(
                new ArraySet<>(List.of()),
                new ArraySet<>(mCollection.getAllNotifs()));
    }

    @Test
    public void testDismissNotificationCallsDismissInterceptors() throws RemoteException {
        // GIVEN a collection with notifications with multiple dismiss interceptors
        mInterceptor1.shouldInterceptDismissal = true;
        mInterceptor2.shouldInterceptDismissal = true;
        mInterceptor3.shouldInterceptDismissal = false;
        mCollection.addNotificationDismissInterceptor(mInterceptor1);
        mCollection.addNotificationDismissInterceptor(mInterceptor2);
        mCollection.addNotificationDismissInterceptor(mInterceptor3);

        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        // WHEN a notification is manually dismissed
        DismissedByUserStats stats = defaultStats(entry);
        mCollection.dismissNotification(entry, stats);

        // THEN all interceptors get checked
        verify(mInterceptor1).shouldInterceptDismissal(entry);
        verify(mInterceptor2).shouldInterceptDismissal(entry);
        verify(mInterceptor3).shouldInterceptDismissal(entry);
        assertEquals(List.of(mInterceptor1, mInterceptor2), entry.mDismissInterceptors);

        // THEN we never send the dismissal to system server
        verify(mStatusBarService, never()).onNotificationClear(
                notif.sbn.getPackageName(),
                notif.sbn.getUser().getIdentifier(),
                notif.sbn.getKey(),
                stats.dismissalSurface,
                stats.dismissalSentiment,
                stats.notificationVisibility);
    }

    @Test
    public void testDismissInterceptorsCanceledWhenNotifIsUpdated() throws RemoteException {
        // GIVEN a few lifetime extenders and a couple notifications
        mCollection.addNotificationDismissInterceptor(mInterceptor1);
        mCollection.addNotificationDismissInterceptor(mInterceptor2);

        mInterceptor1.shouldInterceptDismissal = true;
        mInterceptor2.shouldInterceptDismissal = true;

        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        // WHEN a notification is manually dismissed and intercepted
        DismissedByUserStats stats = defaultStats(entry);
        mCollection.dismissNotification(entry, stats);
        assertEquals(List.of(mInterceptor1, mInterceptor2), entry.mDismissInterceptors);
        clearInvocations(mInterceptor1, mInterceptor2);

        // WHEN the notification is reposted
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47));

        // THEN all of the active dismissal interceptors are canceled
        verify(mInterceptor1).cancelDismissInterception(entry);
        verify(mInterceptor2).cancelDismissInterception(entry);
        assertEquals(List.of(), entry.mDismissInterceptors);

        // THEN the notification is never sent to system server to dismiss
        verify(mStatusBarService, never()).onNotificationClear(
                eq(notif.sbn.getPackageName()),
                eq(notif.sbn.getUser().getIdentifier()),
                eq(notif.sbn.getKey()),
                anyInt(),
                anyInt(),
                eq(stats.notificationVisibility));
    }

    @Test
    public void testEndingAllDismissInterceptorsSendsDismiss() throws RemoteException {
        // GIVEN a collection with notifications a dismiss interceptor
        mInterceptor1.shouldInterceptDismissal = true;
        mCollection.addNotificationDismissInterceptor(mInterceptor1);

        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        // GIVEN a notification is manually dismissed
        DismissedByUserStats stats = defaultStats(entry);
        mCollection.dismissNotification(entry, stats);

        // WHEN all interceptors end their interception dismissal
        mInterceptor1.shouldInterceptDismissal = false;
        mInterceptor1.onEndInterceptionCallback.onEndDismissInterception(mInterceptor1, entry,
                stats);

        FakeExecutor.exhaustExecutors(mBgExecutor);

        // THEN we send the dismissal to system server
        verify(mStatusBarService).onNotificationClear(
                eq(notif.sbn.getPackageName()),
                eq(notif.sbn.getUser().getIdentifier()),
                eq(notif.sbn.getKey()),
                anyInt(),
                anyInt(),
                eq(stats.notificationVisibility));
    }

    @Test
    public void testEndDismissInterceptionUpdatesDismissInterceptors() {
        // GIVEN a collection with notifications with multiple dismiss interceptors
        mInterceptor1.shouldInterceptDismissal = true;
        mInterceptor2.shouldInterceptDismissal = true;
        mInterceptor3.shouldInterceptDismissal = false;
        mCollection.addNotificationDismissInterceptor(mInterceptor1);
        mCollection.addNotificationDismissInterceptor(mInterceptor2);
        mCollection.addNotificationDismissInterceptor(mInterceptor3);

        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        // GIVEN a notification is manually dismissed
        mCollection.dismissNotification(entry, defaultStats(entry));

       // WHEN an interceptor ends its interception
        mInterceptor1.shouldInterceptDismissal = false;
        mInterceptor1.onEndInterceptionCallback.onEndDismissInterception(mInterceptor1, entry,
                defaultStats(entry));

        // THEN all interceptors get checked
        verify(mInterceptor1).shouldInterceptDismissal(entry);
        verify(mInterceptor2).shouldInterceptDismissal(entry);
        verify(mInterceptor3).shouldInterceptDismissal(entry);

        // THEN mInterceptor2 is the only dismiss interceptor
        assertEquals(List.of(mInterceptor2), entry.mDismissInterceptors);
    }


    @Test(expected = IllegalStateException.class)
    public void testEndingDismissalOfNonInterceptedThrows() {
        // GIVEN a collection with notifications with a dismiss interceptor that hasn't been called
        mInterceptor1.shouldInterceptDismissal = false;
        mCollection.addNotificationDismissInterceptor(mInterceptor1);

        NotifEvent notif = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notif.key);

        // WHEN we try to end the dismissal of an interceptor that didn't intercept the notif
        mInterceptor1.onEndInterceptionCallback.onEndDismissInterception(mInterceptor1, entry,
                defaultStats(entry));

        // THEN an exception is thrown
    }

    @Test
    public void testGroupChildrenAreDismissedLocallyWhenSummaryIsDismissed() {
        // GIVEN a collection with two grouped notifs in it
        CollectionEvent groupNotif = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent childNotif = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1));
        NotificationEntry groupEntry = mCollectionListener.getEntry(groupNotif.key);
        NotificationEntry childEntry = mCollectionListener.getEntry(childNotif.key);
        ExpandableNotificationRow childRow = mock(ExpandableNotificationRow.class);
        childEntry.setRow(childRow);

        // WHEN the summary is dismissed
        mCollection.dismissNotification(groupEntry, defaultStats(groupEntry));

        // THEN all members of the group are marked as dismissed locally
        assertEquals(DISMISSED, groupEntry.getDismissState());
        assertEquals(PARENT_DISMISSED, childEntry.getDismissState());
    }

    @Test
    public void testUpdatingDismissedSummaryBringsChildrenBack() {
        // GIVEN a collection with two grouped notifs in it
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1));
        NotificationEntry entry0 = mCollectionListener.getEntry(notif0.key);
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN the summary is dismissed but then reposted without a group
        mCollection.dismissNotification(entry0, defaultStats(entry0));
        NotifEvent notif0a = mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 0));

        // THEN it and all of its previous children are no longer dismissed locally
        assertEquals(NOT_DISMISSED, entry0.getDismissState());
        assertEquals(NOT_DISMISSED, entry1.getDismissState());
    }

    @Test
    public void testDismissedChildrenAreNotResetByParentUpdate() {
        // GIVEN a collection with three grouped notifs in it
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1));
        CollectionEvent notif2 = postNotif(
                buildNotif(TEST_PACKAGE, 2)
                        .setGroup(mContext, GROUP_1));
        NotificationEntry entry0 = mCollectionListener.getEntry(notif0.key);
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN a child is dismissed, then the parent is dismissed, then the parent is updated
        mCollection.dismissNotification(entry1, defaultStats(entry1));
        mCollection.dismissNotification(entry0, defaultStats(entry0));
        NotifEvent notif0a = mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 0));

        // THEN the manually-dismissed child is still marked as dismissed
        assertEquals(NOT_DISMISSED, entry0.getDismissState());
        assertEquals(DISMISSED, entry1.getDismissState());
        assertEquals(NOT_DISMISSED, entry2.getDismissState());
    }

    @Test
    public void testUpdatingGroupKeyOfDismissedSummaryBringsChildrenBack() {
        // GIVEN a collection with two grouped notifs in it
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setOverrideGroupKey(GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setOverrideGroupKey(GROUP_1));
        NotificationEntry entry0 = mCollectionListener.getEntry(notif0.key);
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);

        // WHEN the summary is dismissed but then reposted AND in the same update one of the
        // children's ranking loses its override group
        mCollection.dismissNotification(entry0, defaultStats(entry0));
        mNoMan.setRanking(entry1.getKey(), new RankingBuilder()
                .setKey(entry1.getKey())
                .build());
        mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setOverrideGroupKey(GROUP_1)
                        .setGroupSummary(mContext, true));

        // THEN it and all of its previous children are no longer dismissed locally, including the
        // child that is no longer part of the group
        assertEquals(NOT_DISMISSED, entry0.getDismissState());
        assertEquals(NOT_DISMISSED, entry1.getDismissState());
    }

    @Test
    public void testDismissingSummaryDoesDismissForegroundServiceChildren() {
        // GIVEN a collection with three grouped notifs in it
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1)
                        .setFlag(mContext, Notification.FLAG_FOREGROUND_SERVICE, true));
        CollectionEvent notif2 = postNotif(
                buildNotif(TEST_PACKAGE, 2)
                        .setGroup(mContext, GROUP_1));

        // WHEN the summary is dismissed
        mCollection.dismissNotification(notif0.entry, defaultStats(notif0.entry));

        // THEN the foreground service child is dismissed
        assertEquals(DISMISSED, notif0.entry.getDismissState());
        assertEquals(PARENT_DISMISSED, notif1.entry.getDismissState());
        assertEquals(PARENT_DISMISSED, notif2.entry.getDismissState());
    }

    @Test
    public void testDismissingSummaryDoesNotDismissOngoingChildren() {
        // GIVEN a collection with three grouped notifs in it
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1)
                        .setFlag(mContext, FLAG_ONGOING_EVENT, true));
        CollectionEvent notif2 = postNotif(
                buildNotif(TEST_PACKAGE, 2)
                        .setGroup(mContext, GROUP_1));

        // WHEN the summary is dismissed
        mCollection.dismissNotification(notif0.entry, defaultStats(notif0.entry));

        // THEN the ongoing child is not dismissed
        assertEquals(DISMISSED, notif0.entry.getDismissState());
        assertEquals(NOT_DISMISSED, notif1.entry.getDismissState());
        assertEquals(PARENT_DISMISSED, notif2.entry.getDismissState());
    }

    @Test
    public void testDismissingSummaryDoesNotDismissBubbledChildren() {
        // GIVEN a collection with three grouped notifs in it
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1)
                        .setFlag(mContext, Notification.FLAG_BUBBLE, true));
        CollectionEvent notif2 = postNotif(
                buildNotif(TEST_PACKAGE, 2)
                        .setGroup(mContext, GROUP_1));

        // WHEN the summary is dismissed
        mCollection.dismissNotification(notif0.entry, defaultStats(notif0.entry));

        // THEN the bubbled child is not dismissed
        assertEquals(DISMISSED, notif0.entry.getDismissState());
        assertEquals(NOT_DISMISSED, notif1.entry.getDismissState());
        assertEquals(PARENT_DISMISSED, notif2.entry.getDismissState());
    }

    @Test
    public void testDismissingSummaryDoesNotDismissDuplicateSummaries() {
        // GIVEN a group with a two summaries
        CollectionEvent notif0 = postNotif(
                buildNotif(TEST_PACKAGE, 0)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif1 = postNotif(
                buildNotif(TEST_PACKAGE, 1)
                        .setGroup(mContext, GROUP_1)
                        .setGroupSummary(mContext, true));
        CollectionEvent notif2 = postNotif(
                buildNotif(TEST_PACKAGE, 2)
                        .setGroup(mContext, GROUP_1));

        // WHEN the first summary is dismissed
        mCollection.dismissNotification(notif0.entry, defaultStats(notif0.entry));

        // THEN the second summary is not auto-dismissed (but the child is)
        assertEquals(DISMISSED, notif0.entry.getDismissState());
        assertEquals(NOT_DISMISSED, notif1.entry.getDismissState());
        assertEquals(PARENT_DISMISSED, notif2.entry.getDismissState());
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

        // WHEN a notification is removed by the app
        mNoMan.retractNotif(notif2.sbn, REASON_APP_CANCEL);

        // THEN each extender is asked whether to extend, even if earlier ones return true
        verify(mExtender1).maybeExtendLifetime(entry2, REASON_APP_CANCEL);
        verify(mExtender2).maybeExtendLifetime(entry2, REASON_APP_CANCEL);
        verify(mExtender3).maybeExtendLifetime(entry2, REASON_APP_CANCEL);

        // THEN the entry is not removed
        assertTrue(mCollection.getAllNotifs().contains(entry2));

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
        assertTrue(mCollection.getAllNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN the last active extender expires (but new ones become active)
        mExtender1.shouldExtendLifetime = true;
        mExtender2.shouldExtendLifetime = false;
        mExtender3.shouldExtendLifetime = true;
        mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);

        // THEN each extender is re-queried
        verify(mExtender1).maybeExtendLifetime(entry2, REASON_APP_CANCEL);
        verify(mExtender2).maybeExtendLifetime(entry2, REASON_APP_CANCEL);
        verify(mExtender3).maybeExtendLifetime(entry2, REASON_APP_CANCEL);

        // THEN the entry is not removed
        assertTrue(mCollection.getAllNotifs().contains(entry2));

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
        assertTrue(mCollection.getAllNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN one (but not all) of the extenders expires
        mExtender2.shouldExtendLifetime = false;
        mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);

        // THEN the entry is not removed
        assertTrue(mCollection.getAllNotifs().contains(entry2));

        // THEN we don't re-query the extenders
        verify(mExtender1, never()).maybeExtendLifetime(entry2, REASON_APP_CANCEL);
        verify(mExtender2, never()).maybeExtendLifetime(entry2, REASON_APP_CANCEL);
        verify(mExtender3, never()).maybeExtendLifetime(entry2, REASON_APP_CANCEL);

        // THEN the entry properly records all extenders that returned true
        assertEquals(singletonList(mExtender1), entry2.mLifetimeExtenders);
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
        assertTrue(mCollection.getAllNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN all of the active extenders expire
        mExtender2.shouldExtendLifetime = false;
        mExtender2.callback.onEndLifetimeExtension(mExtender2, entry2);
        mExtender1.shouldExtendLifetime = false;
        mExtender1.callback.onEndLifetimeExtension(mExtender1, entry2);

        // THEN the entry removed
        assertFalse(mCollection.getAllNotifs().contains(entry2));
        verify(mCollectionListener).onEntryRemoved(entry2, REASON_UNKNOWN);
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
        assertTrue(mCollection.getAllNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN the notification is reposted
        mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88));

        // THEN all of the active lifetime extenders are canceled
        verify(mExtender1).cancelLifetimeExtension(entry2);
        verify(mExtender2).cancelLifetimeExtension(entry2);

        // THEN the notification is still present
        assertTrue(mCollection.getAllNotifs().contains(entry2));
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
        assertTrue(mCollection.getAllNotifs().contains(entry2));
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
        assertTrue(mCollection.getAllNotifs().contains(entry2));
        clearInvocations(mExtender1, mExtender2, mExtender3);

        // WHEN the notification is reposted
        NotifEvent notif2a = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88)
                .setRank(4747)
                .setExplanation("Some new explanation"));

        // THEN the notification's ranking is properly updated
        assertEquals(notif2a.ranking, entry2.getRanking());
    }

    @Test
    public void testCancellationReasonIsSetWhenNotifIsCancelled() {
        // GIVEN a notification
        NotifEvent notif0 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3));
        NotificationEntry entry0 = mCollectionListener.getEntry(notif0.key);

        // WHEN the notification is retracted
        mNoMan.retractNotif(notif0.sbn, REASON_APP_CANCEL);

        // THEN the retraction reason is stored on the notif
        assertEquals(REASON_APP_CANCEL, entry0.mCancellationReason);
    }

    @Test
    public void testCancellationReasonIsClearedWhenNotifIsUpdated() {
        // GIVEN a notification and a lifetime extender that will preserve it
        NotifEvent notif0 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3));
        NotificationEntry entry0 = mCollectionListener.getEntry(notif0.key);
        mCollection.addNotificationLifetimeExtender(mExtender1);
        mExtender1.shouldExtendLifetime = true;

        // WHEN the notification is retracted and subsequently reposted
        mNoMan.retractNotif(notif0.sbn, REASON_APP_CANCEL);
        assertEquals(REASON_APP_CANCEL, entry0.mCancellationReason);
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 3));

        // THEN the notification has its cancellation reason cleared
        assertEquals(REASON_NOT_CANCELED, entry0.mCancellationReason);
    }

    @Test
    public void testDismissNotificationsRebuildsOnce() {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);
        clearInvocations(mBuildListener);

        // WHEN both notifications are manually dismissed together
        mCollection.dismissNotifications(
                List.of(new Pair<>(entry1, defaultStats(entry1)),
                        new Pair<>(entry2, defaultStats(entry2))));

        // THEN build list is only called one time
        verifyBuiltList(List.of(entry1, entry2));
    }

    @Test
    public void testDismissNotificationsSentToSystemServer() throws RemoteException {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN both notifications are manually dismissed together
        DismissedByUserStats stats1 = defaultStats(entry1);
        DismissedByUserStats stats2 = defaultStats(entry2);
        mCollection.dismissNotifications(
                List.of(new Pair<>(entry1, defaultStats(entry1)),
                        new Pair<>(entry2, defaultStats(entry2))));

        // THEN we send the dismissals to system server
        FakeExecutor.exhaustExecutors(mBgExecutor);
        verify(mStatusBarService).onNotificationClear(
                notif1.sbn.getPackageName(),
                notif1.sbn.getUser().getIdentifier(),
                notif1.sbn.getKey(),
                stats1.dismissalSurface,
                stats1.dismissalSentiment,
                stats1.notificationVisibility);

        verify(mStatusBarService).onNotificationClear(
                notif2.sbn.getPackageName(),
                notif2.sbn.getUser().getIdentifier(),
                notif2.sbn.getKey(),
                stats2.dismissalSurface,
                stats2.dismissalSentiment,
                stats2.notificationVisibility);
    }

    @Test
    public void testDismissNotificationsMarkedAsDismissed() {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN both notifications are manually dismissed together
        mCollection.dismissNotifications(
                List.of(new Pair<>(entry1, defaultStats(entry1)),
                        new Pair<>(entry2, defaultStats(entry2))));

        // THEN the entries are marked as dismissed
        assertEquals(DISMISSED, entry1.getDismissState());
        assertEquals(DISMISSED, entry2.getDismissState());
    }

    @Test
    public void testDismissNotificationssCallsDismissInterceptors() {
        // GIVEN a collection with notifications with multiple dismiss interceptors
        mInterceptor1.shouldInterceptDismissal = true;
        mInterceptor2.shouldInterceptDismissal = true;
        mInterceptor3.shouldInterceptDismissal = false;
        mCollection.addNotificationDismissInterceptor(mInterceptor1);
        mCollection.addNotificationDismissInterceptor(mInterceptor2);
        mCollection.addNotificationDismissInterceptor(mInterceptor3);

        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN both notifications are manually dismissed together
        mCollection.dismissNotifications(
                List.of(new Pair<>(entry1, defaultStats(entry1)),
                        new Pair<>(entry2, defaultStats(entry2))));

        // THEN all interceptors get checked
        verify(mInterceptor1).shouldInterceptDismissal(entry1);
        verify(mInterceptor2).shouldInterceptDismissal(entry1);
        verify(mInterceptor3).shouldInterceptDismissal(entry1);
        verify(mInterceptor1).shouldInterceptDismissal(entry2);
        verify(mInterceptor2).shouldInterceptDismissal(entry2);
        verify(mInterceptor3).shouldInterceptDismissal(entry2);

        assertEquals(List.of(mInterceptor1, mInterceptor2), entry1.mDismissInterceptors);
        assertEquals(List.of(mInterceptor1, mInterceptor2), entry2.mDismissInterceptors);
    }

    @Test
    public void testDismissAllNotificationsCallsRebuildOnce() {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);
        clearInvocations(mBuildListener);

        // WHEN all notifications are dismissed for the user who posted both notifs
        mCollection.dismissAllNotifications(entry1.getSbn().getUser().getIdentifier());

        // THEN build list is only called one time
        verifyBuiltList(List.of(entry1, entry2));
    }

    @Test
    public void testDismissAllNotificationsSentToSystemServer() throws RemoteException {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN all notifications are dismissed for the user who posted both notifs
        mCollection.dismissAllNotifications(entry1.getSbn().getUser().getIdentifier());

        // THEN we send the dismissal to system server
        verify(mStatusBarService).onClearAllNotifications(
                entry1.getSbn().getUser().getIdentifier());
    }

    @Test
    public void testDismissAllNotificationsMarkedAsDismissed() {
        // GIVEN a collection with a couple notifications
        NotifEvent notif1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry entry1 = mCollectionListener.getEntry(notif1.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN all notifications are dismissed for the user who posted both notifs
        mCollection.dismissAllNotifications(entry1.getSbn().getUser().getIdentifier());

        // THEN the entries are marked as dismissed
        assertEquals(DISMISSED, entry1.getDismissState());
        assertEquals(DISMISSED, entry2.getDismissState());
    }

    @Test
    public void testDismissAllNotificationsDoesNotMarkDismissedUnclearableNotifs() {
        // GIVEN a collection with one unclearable notification and one clearable notification
        NotificationEntryBuilder notifEntryBuilder = buildNotif(TEST_PACKAGE, 47, "myTag");
        notifEntryBuilder.modifyNotification(mContext)
                .setFlag(FLAG_NO_CLEAR, true);
        NotifEvent unclearabeNotif = mNoMan.postNotif(notifEntryBuilder);
        NotifEvent notif2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE2, 88, "barTag"));
        NotificationEntry unclearableEntry = mCollectionListener.getEntry(unclearabeNotif.key);
        NotificationEntry entry2 = mCollectionListener.getEntry(notif2.key);

        // WHEN all notifications are dismissed for the user who posted both notifs
        mCollection.dismissAllNotifications(unclearableEntry.getSbn().getUser().getIdentifier());

        // THEN only the clearable entry is marked as dismissed
        assertEquals(NOT_DISMISSED, unclearableEntry.getDismissState());
        assertEquals(DISMISSED, entry2.getDismissState());
    }

    @Test
    public void testDismissAllNotificationsCallsDismissInterceptorsOnlyOnUnclearableNotifs() {
        // GIVEN a collection with multiple dismiss interceptors
        mInterceptor1.shouldInterceptDismissal = true;
        mInterceptor2.shouldInterceptDismissal = true;
        mInterceptor3.shouldInterceptDismissal = false;
        mCollection.addNotificationDismissInterceptor(mInterceptor1);
        mCollection.addNotificationDismissInterceptor(mInterceptor2);
        mCollection.addNotificationDismissInterceptor(mInterceptor3);

        // GIVEN a collection with one unclearable and one clearable notification
        NotifEvent unclearableNotif = mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 47, "myTag")
                        .setFlag(mContext, FLAG_NO_CLEAR, true));
        NotificationEntry unclearable = mCollectionListener.getEntry(unclearableNotif.key);
        NotifEvent clearableNotif = mNoMan.postNotif(
                buildNotif(TEST_PACKAGE, 88, "myTag")
                        .setFlag(mContext, FLAG_NO_CLEAR, false));
        NotificationEntry clearable = mCollectionListener.getEntry(clearableNotif.key);

        // WHEN all notifications are dismissed for the user who posted the notif
        mCollection.dismissAllNotifications(clearable.getSbn().getUser().getIdentifier());

        // THEN all interceptors get checked for the unclearable notification
        verify(mInterceptor1).shouldInterceptDismissal(unclearable);
        verify(mInterceptor2).shouldInterceptDismissal(unclearable);
        verify(mInterceptor3).shouldInterceptDismissal(unclearable);
        assertEquals(List.of(mInterceptor1, mInterceptor2), unclearable.mDismissInterceptors);

        // THEN no interceptors get checked for the clearable notification
        verify(mInterceptor1, never()).shouldInterceptDismissal(clearable);
        verify(mInterceptor2, never()).shouldInterceptDismissal(clearable);
        verify(mInterceptor3, never()).shouldInterceptDismissal(clearable);
    }

    @Test
    public void testClearNotificationDoesntThrowIfMissing() {
        // GIVEN that enough time has passed that we're beyond the forgiveness window
        mClock.advanceTime(5001);

        // WHEN we get a remove event for a notification we don't know about
        final NotificationEntry container = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE)
                .setId(47)
                .build();
        mNotifHandler.onNotificationRemoved(
                container.getSbn(),
                new RankingMap(new Ranking[]{ container.getRanking() }));

        // THEN the event is ignored
        verify(mCollectionListener, never()).onEntryRemoved(any(NotificationEntry.class), anyInt());
    }

    @Test
    public void testClearNotificationDoesntThrowIfInForgivenessWindow() {
        // GIVEN that some time has passed but we're still within the initialization forgiveness
        // window
        mClock.advanceTime(4999);

        // WHEN we get a remove event for a notification we don't know about
        final NotificationEntry container = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE)
                .setId(47)
                .build();
        mNotifHandler.onNotificationRemoved(
                container.getSbn(),
                new RankingMap(new Ranking[]{ container.getRanking() }));

        // THEN no exception is thrown, but no event is fired
        verify(mCollectionListener, never()).onEntryRemoved(any(NotificationEntry.class), anyInt());
    }

    private Runnable getInternalNotifUpdateRunnable(StatusBarNotification sbn) {
        InternalNotifUpdater updater = mCollection.getInternalNotifUpdater("Test");
        updater.onInternalNotificationUpdate(sbn, "reason");
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mMainHandler).post(runnableCaptor.capture());
        return runnableCaptor.getValue();
    }

    @Test
    public void testGetInternalNotifUpdaterPostsToMainHandler() {
        InternalNotifUpdater updater = mCollection.getInternalNotifUpdater("Test");
        updater.onInternalNotificationUpdate(mock(StatusBarNotification.class), "reason");
        verify(mMainHandler).post(any());
    }

    @Test
    public void testSecondPostCallsUpdateWithTrue() {
        // GIVEN a pipeline with one notification
        NotifEvent notifEvent = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notifEvent.key);

        // KNOWING that it already called listener methods once
        verify(mCollectionListener).onEntryAdded(eq(entry));
        verify(mCollectionListener).onRankingApplied();

        // WHEN we update the notification via the system
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));

        // THEN entry updated gets called, added does not, and ranking is called again
        verify(mCollectionListener).onEntryUpdated(eq(entry));
        verify(mCollectionListener).onEntryUpdated(eq(entry), eq(true));
        verify(mCollectionListener).onEntryAdded((entry));
        verify(mCollectionListener, times(2)).onRankingApplied();
    }

    @Test
    public void testInternalNotifUpdaterCallsUpdate() {
        // GIVEN a pipeline with one notification
        NotifEvent notifEvent = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = mCollectionListener.getEntry(notifEvent.key);

        // KNOWING that it will call listener methods once
        verify(mCollectionListener).onEntryAdded(eq(entry));
        verify(mCollectionListener).onRankingApplied();

        // WHEN we update that notification internally
        StatusBarNotification sbn = notifEvent.sbn;
        getInternalNotifUpdateRunnable(sbn).run();

        // THEN only entry updated gets called a second time
        verify(mCollectionListener).onEntryAdded(eq(entry));
        verify(mCollectionListener).onRankingApplied();
        verify(mCollectionListener).onEntryUpdated(eq(entry));
        verify(mCollectionListener).onEntryUpdated(eq(entry), eq(false));
    }

    @Test
    public void testInternalNotifUpdaterIgnoresNew() {
        // GIVEN a pipeline without any notifications
        StatusBarNotification sbn = buildNotif(TEST_PACKAGE, 47, "myTag").build().getSbn();

        // WHEN we internally update an unknown notification
        getInternalNotifUpdateRunnable(sbn).run();

        // THEN only entry updated gets called a second time
        verify(mCollectionListener, never()).onEntryAdded(any());
        verify(mCollectionListener, never()).onRankingUpdate(any());
        verify(mCollectionListener, never()).onRankingApplied();
        verify(mCollectionListener, never()).onEntryUpdated(any());
        verify(mCollectionListener, never()).onEntryUpdated(any(), anyBoolean());
    }

    @Test
    public void testMissingRanking() {
        // GIVEN a pipeline with one two notifications
        String key1 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 1, "myTag")).key;
        String key2 = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 2, "myTag")).key;
        NotificationEntry entry1 = mCollectionListener.getEntry(key1);
        NotificationEntry entry2 = mCollectionListener.getEntry(key2);
        clearInvocations(mCollectionListener);

        // GIVEN the message for removing key1 gets does not reach NotifCollection
        Ranking ranking1 = mNoMan.removeRankingWithoutEvent(key1);
        // WHEN the message for removing key2 arrives
        mNoMan.retractNotif(entry2.getSbn(), REASON_APP_CANCEL);

        // THEN both entry1 and entry2 get removed
        verify(mCollectionListener).onEntryRemoved(eq(entry2), eq(REASON_APP_CANCEL));
        verify(mCollectionListener).onEntryRemoved(eq(entry1), eq(REASON_UNKNOWN));
        verify(mCollectionListener).onEntryCleanUp(eq(entry2));
        verify(mCollectionListener).onEntryCleanUp(eq(entry1));
        verify(mCollectionListener).onRankingApplied();
        verifyNoMoreInteractions(mCollectionListener);
        verify(mLogger).logMissingRankings(eq(List.of(entry1)), eq(1), any());
        verify(mLogger, never()).logRecoveredRankings(any(), anyInt());
        clearInvocations(mCollectionListener, mLogger);

        // WHEN a ranking update includes key1 again
        mNoMan.setRanking(key1, ranking1);
        mNoMan.issueRankingUpdate();

        // VERIFY that we do nothing but log the 'recovery'
        verify(mCollectionListener).onRankingUpdate(any());
        verify(mCollectionListener).onRankingApplied();
        verifyNoMoreInteractions(mCollectionListener);
        verify(mLogger, never()).logMissingRankings(any(), anyInt(), any());
        verify(mLogger).logRecoveredRankings(eq(List.of(key1)), eq(0));
    }

    @Test
    public void testRegisterFutureDismissal() throws RemoteException {
        // GIVEN a pipeline with one notification
        NotifEvent notifEvent = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = requireNonNull(mCollection.getEntry(notifEvent.key));
        clearInvocations(mCollectionListener);

        // WHEN registering a future dismissal, nothing happens right away
        final Runnable onDismiss = mCollection.registerFutureDismissal(entry, REASON_CLICK,
                NotifCollectionTest::defaultStats);
        verifyNoMoreInteractions(mCollectionListener);

        // WHEN finally dismissing
        onDismiss.run();
        FakeExecutor.exhaustExecutors(mBgExecutor);
        verify(mStatusBarService).onNotificationClear(any(), anyInt(), eq(notifEvent.key),
                anyInt(), anyInt(), any());
        verifyNoMoreInteractions(mStatusBarService);
        verifyNoMoreInteractions(mCollectionListener);
    }

    @Test
    public void testRegisterFutureDismissalWithRetractionAndRepost() {
        // GIVEN a pipeline with one notification
        NotifEvent notifEvent = mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        NotificationEntry entry = requireNonNull(mCollection.getEntry(notifEvent.key));
        clearInvocations(mCollectionListener);

        // WHEN registering a future dismissal, nothing happens right away
        final Runnable onDismiss = mCollection.registerFutureDismissal(entry, REASON_CLICK,
                NotifCollectionTest::defaultStats);
        verifyNoMoreInteractions(mCollectionListener);

        // WHEN retracting the notification, and then reposting
        mNoMan.retractNotif(notifEvent.sbn, REASON_CLICK);
        mNoMan.postNotif(buildNotif(TEST_PACKAGE, 47, "myTag"));
        clearInvocations(mCollectionListener);

        // KNOWING that the entry in the collection is different now
        assertThat(mCollection.getEntry(notifEvent.key)).isNotSameInstanceAs(entry);

        // WHEN finally dismissing
        onDismiss.run();

        // VERIFY that nothing happens; the notification should not be removed
        verifyNoMoreInteractions(mCollectionListener);
        assertThat(mCollection.getEntry(notifEvent.key)).isNotNull();
        verifyNoMoreInteractions(mStatusBarService);
    }

    @Test
    public void testCanDismissOtherNotificationChildren() {
        // GIVEN an ongoing notification
        final NotificationEntry container = new NotificationEntryBuilder()
                .setGroup(mContext, "group")
                .build();

        // THEN its children are dismissible
        assertTrue(mCollection.shouldAutoDismissChildren(
                container, container.getSbn().getGroupKey()));
    }

    @Test
    public void testCannotDismissOngoingNotificationChildren() {
        // GIVEN an ongoing notification
        final NotificationEntry container = new NotificationEntryBuilder()
                .setGroup(mContext, "group")
                .setFlag(mContext, FLAG_ONGOING_EVENT, true)
                .build();

        // THEN its children are not dismissible
        assertFalse(mCollection.shouldAutoDismissChildren(
                container, container.getSbn().getGroupKey()));
    }

    @Test
    public void testCannotDismissNoClearNotifications() {
        // GIVEN an no-clear notification
        final NotificationEntry container = new NotificationEntryBuilder()
                .setGroup(mContext, "group")
                .setFlag(mContext, FLAG_NO_CLEAR, true)
                .build();

        // THEN its children are not dismissible
        assertFalse(mCollection.shouldAutoDismissChildren(
                container, container.getSbn().getGroupKey()));
    }

    @Test
    public void testCannotDismissPriorityConversations() {
        // GIVEN an no-clear notification
        NotificationChannel channel =
                new NotificationChannel("foo", "Foo", NotificationManager.IMPORTANCE_HIGH);
        channel.setImportantConversation(true);
        final NotificationEntry container = new NotificationEntryBuilder()
                .setGroup(mContext, "group")
                .setChannel(channel)
                .build();

        // THEN its children are not dismissible
        assertFalse(mCollection.shouldAutoDismissChildren(
                container, container.getSbn().getGroupKey()));
    }

    @Test
    public void testCanDismissFgsNotificationChildren() {
        // GIVEN an FGS but not ongoing notification
        final NotificationEntry container = new NotificationEntryBuilder()
                .setGroup(mContext, "group")
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .build();
        container.setDismissState(NOT_DISMISSED);

        // THEN its children are dismissible
        assertTrue(mCollection.shouldAutoDismissChildren(
                container, container.getSbn().getGroupKey()));
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

    private static DismissedByUserStats defaultStats(NotificationEntry entry) {
        return new DismissedByUserStats(
                DISMISSAL_SHADE,
                DISMISS_SENTIMENT_NEUTRAL,
                NotificationVisibility.obtain(entry.getKey(), 7, 2, true));
    }

    private CollectionEvent postNotif(NotificationEntryBuilder builder) {
        clearInvocations(mCollectionListener);
        NotifEvent rawEvent = mNoMan.postNotif(builder);
        verify(mCollectionListener).onEntryAdded(mEntryCaptor.capture());
        return new CollectionEvent(rawEvent, requireNonNull(mEntryCaptor.getValue()));
    }

    private void verifyBuiltList(Collection<NotificationEntry> expectedList) {
        verify(mBuildListener).onBuildList(mBuildListCaptor.capture(), any());
        assertThat(mBuildListCaptor.getValue()).containsExactly(expectedList.toArray());
    }

    private static class RecordingCollectionListener implements NotifCollectionListener {
        private final Map<String, NotificationEntry> mLastSeenEntries = new ArrayMap<>();

        @Override
        public void onEntryInit(NotificationEntry entry) {
        }

        @Override
        public void onEntryAdded(NotificationEntry entry) {
            mLastSeenEntries.put(entry.getKey(), entry);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            mLastSeenEntries.put(entry.getKey(), entry);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry, boolean fromSystem) {
            onEntryUpdated(entry);
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
        }

        @Override
        public void onRankingApplied() {
        }

        @Override
        public void onRankingUpdate(RankingMap rankingMap) {
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

        @NonNull
        @Override
        public String getName() {
            return mName;
        }

        @Override
        public void setCallback(@NonNull OnEndLifetimeExtensionCallback callback) {
            this.callback = callback;
        }

        @Override
        public boolean maybeExtendLifetime(
                @NonNull NotificationEntry entry,
                @CancellationReason int reason) {
            return shouldExtendLifetime;
        }

        @Override
        public void cancelLifetimeExtension(@NonNull NotificationEntry entry) {
            if (onCancelLifetimeExtension != null) {
                onCancelLifetimeExtension.run();
            }
        }
    }

    private static class RecordingDismissInterceptor implements NotifDismissInterceptor {
        private final String mName;

        public @Nullable OnEndDismissInterception onEndInterceptionCallback;
        public boolean shouldInterceptDismissal = false;

        private RecordingDismissInterceptor(String name) {
            mName = name;
        }

        @Override
        public String getName() {
            return mName;
        }

        @Override
        public void setCallback(OnEndDismissInterception callback) {
            this.onEndInterceptionCallback = callback;
        }

        @Override
        public boolean shouldInterceptDismissal(NotificationEntry entry) {
            return shouldInterceptDismissal;
        }

        @Override
        public void cancelDismissInterception(NotificationEntry entry) {
        }
    }

    /**
     * Wrapper around {@link NotifEvent} that adds the NotificationEntry that the collection under
     * test creates.
     */
    private static class CollectionEvent {
        public final String key;
        public final StatusBarNotification sbn;
        public final Ranking ranking;
        public final RankingMap rankingMap;
        public final NotificationEntry entry;

        private CollectionEvent(NotifEvent rawEvent, NotificationEntry entry) {
            this.key = rawEvent.key;
            this.sbn = rawEvent.sbn;
            this.ranking = rawEvent.ranking;
            this.rankingMap = rawEvent.rankingMap;
            this.entry = entry;
        }
    }

    private static final String TEST_PACKAGE = "com.android.test.collection";
    private static final String TEST_PACKAGE2 = "com.android.test.collection2";

    private static final String GROUP_1 = "group_1";
}
