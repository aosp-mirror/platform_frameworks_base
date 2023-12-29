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

package com.android.systemui.statusbar.notification.collection.coalescer;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import static java.util.Objects.requireNonNull;

import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.NoManSimulator;
import com.android.systemui.statusbar.notification.collection.NoManSimulator.NotifEvent;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
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

import java.util.Arrays;
import java.util.Collections;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class GroupCoalescerTest extends SysuiTestCase {

    private GroupCoalescer mCoalescer;

    @Mock private NotificationListener mListenerService;
    @Mock private GroupCoalescer.BatchableNotificationHandler mListener;
    private final GroupCoalescerLogger mLogger = new GroupCoalescerLogger(logcatLogBuffer());
    @Captor private ArgumentCaptor<NotificationHandler> mListenerCaptor;

    private final NoManSimulator mNoMan = new NoManSimulator();
    private final FakeSystemClock mClock = new FakeSystemClock();
    private final FakeExecutor mExecutor = new FakeExecutor(mClock);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCoalescer =
                new GroupCoalescer(
                        mExecutor,
                        mClock,
                        mLogger,
                        MIN_LINGER_DURATION,
                        MAX_LINGER_DURATION);
        mCoalescer.setNotificationHandler(mListener);
        mCoalescer.attach(mListenerService);

        verify(mListenerService).addNotificationHandler(mListenerCaptor.capture());
        NotificationHandler serviceListener = requireNonNull(mListenerCaptor.getValue());
        mNoMan.addListener(serviceListener);
    }

    @Test
    public void testUngroupedNotificationsAreNotCoalesced() {
        // WHEN a notification that doesn't have a group key is posted
        NotifEvent notif1 = mNoMan.postNotif(
                new NotificationEntryBuilder()
                        .setId(0)
                        .setPkg(TEST_PACKAGE_A));
        mClock.advanceTime(MIN_LINGER_DURATION);

        // THEN the event is passed through to the handler
        verify(mListener).onNotificationPosted(notif1.sbn, notif1.rankingMap);

        // Then the event isn't emitted in a batch
        verify(mListener, never()).onNotificationBatchPosted(anyList());
    }

    @Test
    public void testGroupedNotificationsAreCoalesced() {
        // WHEN a notification that has a group key is posted
        NotifEvent notif1 = mNoMan.postNotif(
                new NotificationEntryBuilder()
                        .setId(0)
                        .setPkg(TEST_PACKAGE_A)
                        .setGroup(mContext, GROUP_1));

        // THEN the event is not passed on to the handler
        verify(mListener, never()).onNotificationPosted(
                any(StatusBarNotification.class),
                any(RankingMap.class));

        // Then the event isn't (yet) emitted in a batch
        verify(mListener, never()).onNotificationBatchPosted(anyList());
    }

    @Test
    public void testCoalescedNotificationsStillPassThroughRankingUpdate() {
        // WHEN a notification that has a group key is posted
        NotifEvent notif1 = mNoMan.postNotif(
                new NotificationEntryBuilder()
                        .setId(0)
                        .setPkg(TEST_PACKAGE_A)
                        .setGroup(mContext, GROUP_1));

        // THEN the listener receives a ranking update instead of an add
        verify(mListener).onNotificationRankingUpdate(notif1.rankingMap);
    }

    @Test
    public void testCoalescedNotificationsArePosted() {
        // GIVEN three notifs are posted that are part of the same group
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setGroup(mContext, GROUP_1));

        mClock.advanceTime(2);

        NotifEvent notif2 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setGroup(mContext, GROUP_1)
                .setGroupSummary(mContext, true));

        mClock.advanceTime(3);

        NotifEvent notif3 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(3)
                .setGroup(mContext, GROUP_1));

        verify(mListener, never()).onNotificationPosted(
                any(StatusBarNotification.class),
                any(RankingMap.class));
        verify(mListener, never()).onNotificationBatchPosted(anyList());

        // WHEN enough time passes
        mClock.advanceTime(MIN_LINGER_DURATION);

        // THEN the coalesced notifs are applied. The summary is sorted to the front.
        verify(mListener).onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(notif2.key, 1, notif2.sbn, notif2.ranking, null),
                new CoalescedEvent(notif1.key, 0, notif1.sbn, notif1.ranking, null),
                new CoalescedEvent(notif3.key, 2, notif3.sbn, notif3.ranking, null)
        ));
    }

    @Test
    public void testCoalescedEventsThatAreLaterUngroupedAreEmittedImmediatelyAndNotLater() {
        // GIVEN a few newly posted notifications in the same group
        NotifEvent notif1a = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setContentTitle(mContext, "Grouped message")
                .setGroup(mContext, GROUP_1));
        NotifEvent notif2 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setGroup(mContext, GROUP_1));
        NotifEvent notif3 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(3)
                .setGroup(mContext, GROUP_1));

        verify(mListener, never()).onNotificationPosted(
                any(StatusBarNotification.class),
                any(RankingMap.class));
        verify(mListener, never()).onNotificationBatchPosted(anyList());

        // WHEN one of them is updated to no longer be in the group
        NotifEvent notif1b = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setContentTitle(mContext, "Oops no longer grouped"));

        // THEN the pre-existing batch is first emitted
        InOrder inOrder = inOrder(mListener);
        inOrder.verify(mListener).onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(notif1a.key, 0, notif1a.sbn, notif1a.ranking, null),
                new CoalescedEvent(notif2.key, 1, notif2.sbn, notif2.ranking, null),
                new CoalescedEvent(notif3.key, 2, notif3.sbn, notif3.ranking, null)
        ));

        // THEN the updated notif is emitted
        inOrder.verify(mListener).onNotificationPosted(notif1b.sbn, notif1b.rankingMap);

        // WHEN the time runs out on the remainder of the group
        clearInvocations(mListener);
        mClock.advanceTime(MIN_LINGER_DURATION);

        // THEN no lingering batch is applied
        verify(mListener, never()).onNotificationBatchPosted(anyList());
    }

    @Test
    public void testUpdatingCoalescedNotifTriggersBatchEmit() {
        // GIVEN two grouped, coalesced notifications
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(2);
        NotifEvent notif2a = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setContentTitle(mContext, "Version 1")
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(4);

        // WHEN one of them gets updated
        NotifEvent notif2b = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setContentTitle(mContext, "Version 2")
                .setGroup(mContext, GROUP_1));

        // THEN first, the coalesced group is emitted
        verify(mListener).onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(notif1.key, 0, notif1.sbn, notif1.ranking, null),
                new CoalescedEvent(notif2a.key, 1, notif2a.sbn, notif2a.ranking, null)
        ));
        verify(mListener, never()).onNotificationPosted(
                any(StatusBarNotification.class),
                any(RankingMap.class));

        // THEN second, the update is emitted
        mClock.advanceTime(MIN_LINGER_DURATION);
        verify(mListener).onNotificationBatchPosted(Collections.singletonList(
                new CoalescedEvent(notif2b.key, 0, notif2b.sbn, notif2b.ranking, null)
        ));
    }

    @Test
    public void testRemovingCoalescedNotifTriggersBatchEmit() {
        // GIVEN two grouped, coalesced notifications
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setGroup(mContext, GROUP_1));
        NotifEvent notif2a = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setGroup(mContext, GROUP_1));

        // WHEN one of them gets retracted
        NotifEvent notif2b = mNoMan.retractNotif(notif2a.sbn, 0);

        // THEN first, the coalesced group is emitted
        InOrder inOrder = inOrder(mListener);
        inOrder.verify(mListener).onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(notif1.key, 0, notif1.sbn, notif1.ranking, null),
                new CoalescedEvent(notif2a.key, 1, notif2a.sbn, notif2a.ranking, null)
        ));

        // THEN second, the removal is emitted
        inOrder.verify(mListener).onNotificationRemoved(notif2b.sbn, notif2b.rankingMap, 0);
    }

    @Test
    public void testRankingsAreUpdated() {
        // GIVEN a couple coalesced notifications
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setGroup(mContext, GROUP_1));
        NotifEvent notif2 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setGroup(mContext, GROUP_1));

        // WHEN an update to an unrelated notification comes in that updates their rankings
        Ranking ranking1b = new RankingBuilder()
                .setKey(notif1.key)
                .setLastAudiblyAlertedMs(4747)
                .build();
        Ranking ranking2b = new RankingBuilder()
                .setKey(notif2.key)
                .setLastAudiblyAlertedMs(3333)
                .build();
        mNoMan.setRanking(notif1.key, ranking1b);
        mNoMan.setRanking(notif2.key, ranking2b);
        mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_B)
                .setId(17));

        // THEN they have the new rankings when they are eventually emitted
        mClock.advanceTime(MIN_LINGER_DURATION);
        verify(mListener).onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(notif1.key, 0, notif1.sbn, ranking1b, null),
                new CoalescedEvent(notif2.key, 1, notif2.sbn, ranking2b, null)
        ));
    }

    @Test
    public void testMaxLingerDuration() {
        // GIVEN five coalesced notifications that have collectively taken 20ms to arrive, 2ms
        // longer than the max linger duration
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(1)
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(4);
        NotifEvent notif2 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(2)
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(4);
        NotifEvent notif3 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(3)
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(4);
        NotifEvent notif4 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(4)
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(4);
        NotifEvent notif5 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(5)
                .setGroup(mContext, GROUP_1));
        mClock.advanceTime(4);

        // WHEN a sixth notification arrives
        NotifEvent notif6 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_A)
                .setId(6)
                .setGroup(mContext, GROUP_1));

        // THEN the first five notifications are emitted in a batch
        verify(mListener).onNotificationBatchPosted(Arrays.asList(
                new CoalescedEvent(notif1.key, 0, notif1.sbn, notif1.ranking, null),
                new CoalescedEvent(notif2.key, 1, notif2.sbn, notif2.ranking, null),
                new CoalescedEvent(notif3.key, 2, notif3.sbn, notif3.ranking, null),
                new CoalescedEvent(notif4.key, 3, notif4.sbn, notif4.ranking, null),
                new CoalescedEvent(notif5.key, 4, notif5.sbn, notif5.ranking, null)
        ));
    }

    private static final long MIN_LINGER_DURATION = 5;
    private static final long MAX_LINGER_DURATION = 18;

    private static final String TEST_PACKAGE_A = "com.test.package_a";
    private static final String TEST_PACKAGE_B = "com.test.package_b";
    private static final String GROUP_1 = "group_1";
}
