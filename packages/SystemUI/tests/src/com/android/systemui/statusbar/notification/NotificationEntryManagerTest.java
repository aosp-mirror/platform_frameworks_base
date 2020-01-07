/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;

import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.NotificationEntryManager.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager;
import com.android.systemui.statusbar.notification.collection.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.logging.NotifLog;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class NotificationEntryManagerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationPresenter mPresenter;
    @Mock private KeyguardEnvironment mEnvironment;
    @Mock private ExpandableNotificationRow mRow;
    @Mock private NotificationListContainer mListContainer;
    @Mock private NotificationEntryListener mEntryListener;
    @Mock private NotificationRemoveInterceptor mRemoveInterceptor;
    @Mock private NotificationRowBinderImpl.BindRowCallback mBindCallback;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private RankingMap mRankingMap;
    @Mock private RemoteInputController mRemoteInputController;

    // Dependency mocks:
    @Mock private ForegroundServiceController mForegroundServiceController;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private NotificationGutsManager mGutsManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private NotificationListener mNotificationListener;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private SmartReplyController mSmartReplyController;
    @Mock private RowInflaterTask mAsyncInflationTask;
    @Mock private NotificationRowBinder mMockedRowBinder;
    @Mock private NotifLog mNotifLog;
    @Mock private FeatureFlags mFeatureFlags;

    private int mId;
    private NotificationEntry mEntry;
    private StatusBarNotification mSbn;
    private TestableNotificationEntryManager mEntryManager;
    private CountDownLatch mCountDownLatch;

    private void setUserSentiment(String key, int sentiment) {
        doAnswer(invocationOnMock -> {
            Ranking ranking = (Ranking)
                    invocationOnMock.getArguments()[1];
            ranking.populate(
                    key,
                    0,
                    false,
                    0,
                    0,
                    IMPORTANCE_DEFAULT,
                    null, null,
                    null, null, null, true, sentiment, false, -1, false, null, null, false, false);
            return true;
        }).when(mRankingMap).getRanking(eq(key), any(Ranking.class));
    }

    private void setSmartActions(String key, ArrayList<Notification.Action> smartActions) {
        doAnswer(invocationOnMock -> {
            Ranking ranking = (Ranking)
                    invocationOnMock.getArguments()[1];
            ranking.populate(
                    key,
                    0,
                    false,
                    0,
                    0,
                    IMPORTANCE_DEFAULT,
                    null, null,
                    null, null, null, true,
                    Ranking.USER_SENTIMENT_NEUTRAL, false, -1,
                    false, smartActions, null, false, false);
            return true;
        }).when(mRankingMap).getRanking(eq(key), any(Ranking.class));
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectTestDependency(ForegroundServiceController.class,
                mForegroundServiceController);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mDependency.injectTestDependency(NotificationGutsManager.class, mGutsManager);
        mDependency.injectTestDependency(NotificationRemoteInputManager.class, mRemoteInputManager);
        mDependency.injectTestDependency(NotificationListener.class, mNotificationListener);
        mDependency.injectTestDependency(DeviceProvisionedController.class,
                mDeviceProvisionedController);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mDependency.injectTestDependency(SmartReplyController.class, mSmartReplyController);
        mDependency.injectTestDependency(KeyguardEnvironment.class, mEnvironment);
        mDependency.injectMockDependency(NotificationMediaManager.class);

        mCountDownLatch = new CountDownLatch(1);

        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                Handler.createAsync(Looper.myLooper()));
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);
        when(mListContainer.getViewParentForNotification(any())).thenReturn(
                new FrameLayout(mContext));

        mEntry = createNotification();
        mSbn = mEntry.getSbn();

        mEntry.expandedIcon = mock(StatusBarIconView.class);

        when(mFeatureFlags.isNewNotifPipelineEnabled()).thenReturn(false);
        when(mFeatureFlags.isNewNotifPipelineRenderingEnabled()).thenReturn(false);
        mEntryManager = new TestableNotificationEntryManager(
                mNotifLog,
                mGroupManager,
                new NotificationRankingManager(
                        () -> mock(NotificationMediaManager.class),
                        mGroupManager,
                        mHeadsUpManager,
                        mock(NotificationFilter.class),
                        mNotifLog,
                        mock(NotificationSectionsFeatureManager.class),
                        mock(PeopleNotificationIdentifier.class),
                        mock(HighPriorityProvider.class)),
                mEnvironment,
                mFeatureFlags
        );
        mEntryManager.setUpWithPresenter(mPresenter, mListContainer, mHeadsUpManager);
        mEntryManager.addNotificationEntryListener(mEntryListener);
        mEntryManager.setNotificationRemoveInterceptor(mRemoveInterceptor);

        NotificationRowBinderImpl notificationRowBinder =
                new NotificationRowBinderImpl(mContext, true, /* allowLongPress */
                        mock(KeyguardBypassController.class),
                        mock(StatusBarStateController.class),
                        mock(NotificationLogger.class));
        notificationRowBinder.setUpWithPresenter(
                mPresenter, mListContainer, mHeadsUpManager, mBindCallback);
        notificationRowBinder.setInflationCallback(mEntryManager);
        notificationRowBinder.setNotificationClicker(mock(NotificationClicker.class));
        mEntryManager.setRowBinder(notificationRowBinder);

        setUserSentiment(
                mEntry.getKey(), Ranking.USER_SENTIMENT_NEUTRAL);
    }

    @Test
    public void testAddNotification() throws Exception {
        TestableLooper.get(this).processAllMessages();

        doAnswer(invocation -> {
            mCountDownLatch.countDown();
            return null;
        }).when(mBindCallback).onBindRow(any(), any(), any(), any());

        // Post on main thread, otherwise we will be stuck waiting here for the inflation finished
        // callback forever, since it won't execute until the tests ends.
        mEntryManager.addNotification(mSbn, mRankingMap);
        TestableLooper.get(this).processMessages(1);
        assertTrue(mCountDownLatch.await(10, TimeUnit.SECONDS));
        assertTrue(mEntryManager.getCountDownLatch().await(10, TimeUnit.SECONDS));

        // Check that no inflation error occurred.
        verify(mEntryListener, never()).onInflationError(any(), any());

        // Row inflation:
        ArgumentCaptor<NotificationEntry> entryCaptor = ArgumentCaptor.forClass(
                NotificationEntry.class);
        verify(mBindCallback).onBindRow(entryCaptor.capture(), any(), eq(mSbn), any());
        NotificationEntry entry = entryCaptor.getValue();
        verify(mRemoteInputManager).bindRow(entry.getRow());

        // Row content inflation:
        verify(mEntryListener).onNotificationAdded(entry);
        verify(mPresenter).updateNotificationViews();

        assertEquals(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()), entry);
        assertNotNull(entry.getRow());
        assertEquals(mEntry.getUserSentiment(),
                Ranking.USER_SENTIMENT_NEUTRAL);
    }

    @Test
    public void testUpdateNotification() throws Exception {
        TestableLooper.get(this).processAllMessages();

        mEntryManager.addActiveNotificationForTest(mEntry);

        setUserSentiment(
                mEntry.getKey(), Ranking.USER_SENTIMENT_NEGATIVE);

        mEntryManager.updateNotification(mSbn, mRankingMap);
        TestableLooper.get(this).processMessages(1);
        // Wait for content update.
        assertTrue(mEntryManager.getCountDownLatch().await(10, TimeUnit.SECONDS));

        verify(mEntryListener, never()).onInflationError(any(), any());

        verify(mEntryListener).onPreEntryUpdated(mEntry);
        verify(mPresenter).updateNotificationViews();
        verify(mEntryListener).onPostEntryUpdated(mEntry);

        assertNotNull(mEntry.getRow());
        assertEquals(Ranking.USER_SENTIMENT_NEGATIVE,
                mEntry.getUserSentiment());
    }

    @Test
    public void testUpdateNotification_prePostEntryOrder() throws Exception {
        TestableLooper.get(this).processAllMessages();

        mEntryManager.addActiveNotificationForTest(mEntry);

        mEntryManager.updateNotification(mSbn, mRankingMap);
        TestableLooper.get(this).processMessages(1);
        // Wait for content update.
        assertTrue(mEntryManager.getCountDownLatch().await(10, TimeUnit.SECONDS));

        verify(mEntryListener, never()).onInflationError(any(), any());

        // Ensure that update callbacks happen in correct order
        InOrder order = inOrder(mEntryListener, mPresenter, mEntryListener);
        order.verify(mEntryListener).onPreEntryUpdated(mEntry);
        order.verify(mPresenter).updateNotificationViews();
        order.verify(mEntryListener).onPostEntryUpdated(mEntry);

        assertNotNull(mEntry.getRow());
    }

    @Test
    public void testRemoveNotification() {
        // Row inflation happens off thread, so pretend that this test looper is main
        Assert.sMainLooper = TestableLooper.get(this).getLooper();

        mEntry.setRow(mRow);
        mEntryManager.addActiveNotificationForTest(mEntry);

        mEntryManager.removeNotification(mSbn.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        verify(mEntryListener, never()).onInflationError(any(), any());

        verify(mPresenter).updateNotificationViews();
        verify(mEntryListener).onEntryRemoved(
                eq(mEntry), any(), eq(false) /* removedByUser */);
        verify(mRow).setRemoved();

        assertNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));
    }

    @Test
    public void testRemoveNotification_onEntryRemoveNotFiredIfEntryDoesntExist() {

        mEntryManager.removeNotification("not_a_real_key", mRankingMap, UNDEFINED_DISMISS_REASON);

        verify(mEntryListener, never()).onEntryRemoved(
                eq(mEntry), any(), eq(false) /* removedByUser */);
    }

    @Test
    public void testRemoveNotification_whilePending() {

        mEntryManager.setRowBinder(mMockedRowBinder);

        mEntryManager.addNotification(mSbn, mRankingMap);
        mEntryManager.removeNotification(mSbn.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        verify(mEntryListener, never()).onEntryRemoved(
                eq(mEntry), any(), eq(false /* removedByUser */));
    }

    @Test
    public void testUpdateNotificationRanking() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(mRow);
        mEntry.setInflationTask(mAsyncInflationTask);
        mEntryManager.addActiveNotificationForTest(mEntry);
        setSmartActions(mEntry.getKey(), new ArrayList<>(Arrays.asList(createAction())));

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow).setEntry(eq(mEntry));
        assertEquals(1, mEntry.getSmartActions().size());
        assertEquals("action", mEntry.getSmartActions().get(0).title);
        verify(mEntryListener).onNotificationRankingUpdated(mRankingMap);
    }

    @Test
    public void testUpdateNotificationRanking_noChange() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(mRow);
        mEntryManager.addActiveNotificationForTest(mEntry);
        setSmartActions(mEntry.getKey(), null);

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow, never()).setEntry(eq(mEntry));
        assertNull(mEntry.getSmartActions());
    }

    @Test
    public void testUpdateNotificationRanking_rowNotInflatedYet() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(null);
        mEntryManager.addActiveNotificationForTest(mEntry);
        setSmartActions(mEntry.getKey(), new ArrayList<>(Arrays.asList(createAction())));

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow, never()).setEntry(eq(mEntry));
        assertEquals(1, mEntry.getSmartActions().size());
        assertEquals("action", mEntry.getSmartActions().get(0).title);
    }

    @Test
    public void testUpdateNotificationRanking_pendingNotification() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(null);
        mEntryManager.mPendingNotifications.put(mEntry.getKey(), mEntry);
        setSmartActions(mEntry.getKey(), new ArrayList<>(Arrays.asList(createAction())));

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow, never()).setEntry(eq(mEntry));
        assertEquals(1, mEntry.getSmartActions().size());
        assertEquals("action", mEntry.getSmartActions().get(0).title);
    }

    @Test
    public void testLifetimeExtenders_ifNotificationIsRetainedItIsntRemoved() {
        // GIVEN an entry manager with a notification
        mEntryManager.setRowBinder(mMockedRowBinder);
        mEntryManager.addActiveNotificationForTest(mEntry);

        // GIVEN a lifetime extender that always tries to extend lifetime
        NotificationLifetimeExtender extender = mock(NotificationLifetimeExtender.class);
        when(extender.shouldExtendLifetime(mEntry)).thenReturn(true);
        mEntryManager.addNotificationLifetimeExtender(extender);

        // WHEN the notification is removed
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        // THEN the extender is asked to manage the lifetime
        verify(extender).setShouldManageLifetime(mEntry, true);
        // THEN the notification is retained
        assertNotNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));
        verify(mEntryListener, never()).onEntryRemoved(eq(mEntry), any(), eq(false));
    }

    @Test
    public void testLifetimeExtenders_whenRetentionEndsNotificationIsRemoved() {
        // Row inflation happens off thread, so pretend that this test looper is main
        Assert.sMainLooper = TestableLooper.get(this).getLooper();

        // GIVEN an entry manager with a notification whose life has been extended
        mEntryManager.setRowBinder(mMockedRowBinder);
        mEntryManager.addActiveNotificationForTest(mEntry);
        final FakeNotificationLifetimeExtender extender = new FakeNotificationLifetimeExtender();
        mEntryManager.addNotificationLifetimeExtender(extender);
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);
        assertTrue(extender.isManaging(mEntry.getKey()));

        // WHEN the extender finishes its extension
        extender.setExtendLifetimes(false);
        extender.getCallback().onSafeToRemove(mEntry.getKey());

        // THEN the notification is removed
        assertNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));
        verify(mEntryListener).onEntryRemoved(eq(mEntry), any(), eq(false));
    }

    @Test
    public void testLifetimeExtenders_whenNotificationUpdatedRetainersAreCanceled() {
        // GIVEN an entry manager with a notification whose life has been extended
        mEntryManager.setRowBinder(mMockedRowBinder);
        mEntryManager.addActiveNotificationForTest(mEntry);
        NotificationLifetimeExtender extender = mock(NotificationLifetimeExtender.class);
        when(extender.shouldExtendLifetime(mEntry)).thenReturn(true);
        mEntryManager.addNotificationLifetimeExtender(extender);
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        // WHEN the notification is updated
        mEntryManager.updateNotification(mEntry.getSbn(), mRankingMap);

        // THEN the lifetime extension is canceled
        verify(extender).setShouldManageLifetime(mEntry, false);
    }

    @Test
    public void testLifetimeExtenders_whenNewExtenderTakesPrecedenceOldExtenderIsCanceled() {
        // GIVEN an entry manager with a notification
        mEntryManager.setRowBinder(mMockedRowBinder);
        mEntryManager.addActiveNotificationForTest(mEntry);

        // GIVEN two lifetime extenders, the first which never extends and the second which
        // always extends
        NotificationLifetimeExtender extender1 = mock(NotificationLifetimeExtender.class);
        when(extender1.shouldExtendLifetime(mEntry)).thenReturn(false);
        NotificationLifetimeExtender extender2 = mock(NotificationLifetimeExtender.class);
        when(extender2.shouldExtendLifetime(mEntry)).thenReturn(true);
        mEntryManager.addNotificationLifetimeExtender(extender1);
        mEntryManager.addNotificationLifetimeExtender(extender2);

        // GIVEN a notification was lifetime-extended and extender2 is managing it
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);
        verify(extender1, never()).setShouldManageLifetime(mEntry, true);
        verify(extender2).setShouldManageLifetime(mEntry, true);

        // WHEN the extender1 changes its mind and wants to extend the lifetime of the notif
        when(extender1.shouldExtendLifetime(mEntry)).thenReturn(true);
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        // THEN extender2 stops managing the notif and extender1 starts managing it
        verify(extender1).setShouldManageLifetime(mEntry, true);
        verify(extender2).setShouldManageLifetime(mEntry, false);
    }

    /**
     * Ensure that calling NotificationEntryManager.performRemoveNotification() doesn't crash when
     * given a notification that has already been removed from NotificationData.
     */
    @Test
    public void testPerformRemoveNotification_removedEntry() {
        mEntryManager.removeNotification(mSbn.getKey(), null, 0);
        mEntryManager.performRemoveNotification(mSbn, REASON_CANCEL);
    }

    @Test
    public void testRemoveInterceptor_interceptsDontGetRemoved() throws InterruptedException {
        // GIVEN an entry manager with a notification
        mEntryManager.setRowBinder(mMockedRowBinder);
        mEntryManager.addActiveNotificationForTest(mEntry);

        // GIVEN interceptor that intercepts that entry
        when(mRemoveInterceptor.onNotificationRemoveRequested(eq(mEntry.getKey()), anyInt()))
                .thenReturn(true);

        // WHEN the notification is removed
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        // THEN the interceptor intercepts & the entry is not removed & no listeners are called
        assertNotNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));
        verify(mEntryListener, never()).onEntryRemoved(eq(mEntry),
                any(NotificationVisibility.class), anyBoolean());
    }

    @Test
    public void testRemoveInterceptor_notInterceptedGetsRemoved() {
        // Row inflation happens off thread, so pretend that this test looper is main
        Assert.sMainLooper = TestableLooper.get(this).getLooper();

        // GIVEN an entry manager with a notification
        mEntryManager.setRowBinder(mMockedRowBinder);
        mEntryManager.addActiveNotificationForTest(mEntry);

        // GIVEN interceptor that doesn't intercept
        when(mRemoveInterceptor.onNotificationRemoveRequested(eq(mEntry.getKey()), anyInt()))
                .thenReturn(false);

        // WHEN the notification is removed
        mEntryManager.removeNotification(mEntry.getKey(), mRankingMap, UNDEFINED_DISMISS_REASON);

        // THEN the interceptor intercepts & the entry is not removed & no listeners are called
        assertNull(mEntryManager.getActiveNotificationUnfiltered(mSbn.getKey()));
        verify(mEntryListener, atLeastOnce()).onEntryRemoved(eq(mEntry),
                any(NotificationVisibility.class), anyBoolean());
    }

    private NotificationEntry createNotification() {
        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");

        return new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setId(mId++)
                .setNotification(n.build())
                .setUser(new UserHandle(ActivityManager.getCurrentUser()))
                .build();
    }

    /* Tests annexed from NotificationDataTest go here */

    @Test
    public void testChannelIsSetWhenAdded() {
        NotificationChannel nc = new NotificationChannel(
                "testId",
                "testName",
                IMPORTANCE_DEFAULT);

        Ranking r = new RankingBuilder()
                .setKey(mEntry.getKey())
                .setChannel(nc)
                .build();

        RankingMap rm = new RankingMap(new Ranking[] { r });

        // GIVEN: a notification is added, and the ranking updated
        mEntryManager.addActiveNotificationForTest(mEntry);
        mEntryManager.updateRanking(rm, "testReason");

        // THEN the notification entry better have a channel on it
        assertEquals(
                "Channel must be set when adding a notification",
                nc.getName(),
                mEntry.getChannel().getName());
    }

    @Test
    public void testGetNotificationsForCurrentUser_shouldFilterNonCurrentUserNotifications() {
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");

        NotificationEntry e2 = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setId(mId++)
                .setNotification(n.build())
                .setUser(new UserHandle(ActivityManager.getCurrentUser()))
                .build();

        mEntryManager.addActiveNotificationForTest(mEntry);
        mEntryManager.addActiveNotificationForTest(e2);

        when(mEnvironment.isNotificationForCurrentProfiles(mEntry.getSbn())).thenReturn(false);
        when(mEnvironment.isNotificationForCurrentProfiles(e2.getSbn())).thenReturn(true);

        List<NotificationEntry> result = mEntryManager.getActiveNotificationsForCurrentUser();
        assertEquals(result.size(), 1);
        junit.framework.Assert.assertEquals(result.get(0), e2);
    }

    /* End annex */

    private Notification.Action createAction() {
        return new Notification.Action.Builder(
                Icon.createWithResource(getContext(), android.R.drawable.sym_def_app_icon),
                "action",
                PendingIntent.getBroadcast(getContext(), 0, new Intent("Action"), 0)).build();
    }

    private static class FakeNotificationLifetimeExtender implements NotificationLifetimeExtender {
        private NotificationSafeToRemoveCallback mCallback;
        private boolean mExtendLifetimes = true;
        private Set<String> mManagedNotifs = new ArraySet<>();

        @Override
        public void setCallback(@NonNull NotificationSafeToRemoveCallback callback) {
            mCallback = callback;
        }

        @Override
        public boolean shouldExtendLifetime(@NonNull NotificationEntry entry) {
            return mExtendLifetimes;
        }

        @Override
        public void setShouldManageLifetime(
                @NonNull NotificationEntry entry,
                boolean shouldManage) {
            final boolean hasEntry = mManagedNotifs.contains(entry.getKey());
            if (shouldManage) {
                if (hasEntry) {
                    throw new RuntimeException("Already managing this entry: " + entry.getKey());
                }
                mManagedNotifs.add(entry.getKey());
            } else {
                if (!hasEntry) {
                    throw new RuntimeException("Not managing this entry: " + entry.getKey());
                }
                mManagedNotifs.remove(entry.getKey());
            }
        }

        public void setExtendLifetimes(boolean extendLifetimes) {
            mExtendLifetimes = extendLifetimes;
        }

        public NotificationSafeToRemoveCallback getCallback() {
            return mCallback;
        }

        public boolean isManaging(String notificationKey) {
            return mManagedNotifs.contains(notificationKey);
        }
    }
}
