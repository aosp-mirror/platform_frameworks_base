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

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationInflater;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationEntryManagerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationPresenter mPresenter;
    @Mock private KeyguardEnvironment mEnvironment;
    @Mock private ExpandableNotificationRow mRow;
    @Mock private NotificationListContainer mListContainer;
    @Mock
    private NotificationEntryListener mEntryListener;
    @Mock
    private NotificationRowBinder.BindRowCallback mBindCallback;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationListenerService.RankingMap mRankingMap;
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

    private NotificationEntry mEntry;
    private StatusBarNotification mSbn;
    private TestableNotificationEntryManager mEntryManager;
    private CountDownLatch mCountDownLatch;

    private class TestableNotificationEntryManager extends NotificationEntryManager {
        private final CountDownLatch mCountDownLatch;

        TestableNotificationEntryManager(Context context) {
            super(context);
            mCountDownLatch = new CountDownLatch(1);
        }

        public void setNotificationData(NotificationData data) {
            mNotificationData = data;
        }

        @Override
        public void onAsyncInflationFinished(NotificationEntry entry,
                @NotificationInflater.InflationFlag int inflatedFlags) {
            super.onAsyncInflationFinished(entry, inflatedFlags);

            mCountDownLatch.countDown();
        }

        public CountDownLatch getCountDownLatch() {
            return mCountDownLatch;
        }

        public ArrayList<NotificationLifetimeExtender> getLifetimeExtenders() {
            return mNotificationLifetimeExtenders;
        }
    }

    private void setUserSentiment(String key, int sentiment) {
        doAnswer(invocationOnMock -> {
            NotificationListenerService.Ranking ranking = (NotificationListenerService.Ranking)
                    invocationOnMock.getArguments()[1];
            ranking.populate(
                    key,
                    0,
                    false,
                    0,
                    0,
                    NotificationManager.IMPORTANCE_DEFAULT,
                    null, null,
                    null, null, null, true, sentiment, false, -1, false, null, null);
            return true;
        }).when(mRankingMap).getRanking(eq(key), any(NotificationListenerService.Ranking.class));
    }

    private void setSmartActions(String key, ArrayList<Notification.Action> smartActions) {
        doAnswer(invocationOnMock -> {
            NotificationListenerService.Ranking ranking = (NotificationListenerService.Ranking)
                    invocationOnMock.getArguments()[1];
            ranking.populate(
                    key,
                    0,
                    false,
                    0,
                    0,
                    NotificationManager.IMPORTANCE_DEFAULT,
                    null, null,
                    null, null, null, true,
                    NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL, false, -1,
                    false, smartActions, null);
            return true;
        }).when(mRankingMap).getRanking(eq(key), any(NotificationListenerService.Ranking.class));
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

        mCountDownLatch = new CountDownLatch(1);

        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
                Handler.createAsync(Looper.myLooper()));
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);
        when(mListContainer.getViewParentForNotification(any())).thenReturn(
                new FrameLayout(mContext));

        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, n.build(), new UserHandle(ActivityManager.getCurrentUser()), null, 0);
        mEntry = new NotificationEntry(mSbn);
        mEntry.expandedIcon = mock(StatusBarIconView.class);

        mEntryManager = new TestableNotificationEntryManager(mContext);
        Dependency.get(InitController.class).executePostInitTasks();
        mEntryManager.setUpWithPresenter(mPresenter, mListContainer, mHeadsUpManager);
        mEntryManager.addNotificationEntryListener(mEntryListener);

        NotificationRowBinder notificationRowBinder = Dependency.get(NotificationRowBinder.class);
        notificationRowBinder.setUpWithPresenter(
                mPresenter, mListContainer, mHeadsUpManager, mEntryManager, mBindCallback);
        notificationRowBinder.setNotificationClicker(mock(NotificationClicker.class));

        setUserSentiment(mEntry.key, NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL);
    }

    @Test
    public void testAddNotification() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();
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

        assertEquals(mEntryManager.getNotificationData().get(mSbn.getKey()), entry);
        assertNotNull(entry.getRow());
        assertEquals(mEntry.userSentiment,
                NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL);
    }

    @Test
    public void testUpdateNotification() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();
        TestableLooper.get(this).processAllMessages();

        mEntryManager.getNotificationData().add(mEntry);

        setUserSentiment(mEntry.key, NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE);

        mEntryManager.updateNotification(mSbn, mRankingMap);
        TestableLooper.get(this).processMessages(1);
        // Wait for content update.
        assertTrue(mEntryManager.getCountDownLatch().await(10, TimeUnit.SECONDS));

        verify(mEntryListener, never()).onInflationError(any(), any());

        verify(mEntryListener).onPreEntryUpdated(mEntry);
        verify(mPresenter).updateNotificationViews();
        verify(mEntryListener).onPostEntryUpdated(mEntry);

        assertNotNull(mEntry.getRow());
        assertEquals(mEntry.userSentiment,
                NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE);
    }

    @Test
    public void testUpdateNotification_prePostEntryOrder() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();
        TestableLooper.get(this).processAllMessages();

        NotificationData notifData = mock(NotificationData.class);
        when(notifData.get(mEntry.key)).thenReturn(mEntry);

        mEntryManager.setNotificationData(notifData);

        mEntryManager.updateNotification(mSbn, mRankingMap);
        TestableLooper.get(this).processMessages(1);
        // Wait for content update.
        assertTrue(mEntryManager.getCountDownLatch().await(10, TimeUnit.SECONDS));

        verify(mEntryListener, never()).onInflationError(any(), any());

        // Ensure that update callbacks happen in correct order
        InOrder order = inOrder(mEntryListener, notifData, mPresenter, mEntryListener);
        order.verify(mEntryListener).onPreEntryUpdated(mEntry);
        order.verify(notifData).filterAndSort();
        order.verify(mPresenter).updateNotificationViews();
        order.verify(mEntryListener).onPostEntryUpdated(mEntry);

        assertNotNull(mEntry.getRow());
    }

    @Test
    public void testRemoveNotification() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();

        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);

        mEntryManager.removeNotification(mSbn.getKey(), mRankingMap);

        verify(mEntryListener, never()).onInflationError(any(), any());

        verify(mListContainer).cleanUpViewStateForEntry(mEntry);
        verify(mPresenter).updateNotificationViews();
        verify(mEntryListener).onEntryRemoved(
                mEntry, null, false /* removedByUser */);
        verify(mRow).setRemoved();

        assertNull(mEntryManager.getNotificationData().get(mSbn.getKey()));
    }

    @Test
    public void testRemoveNotification_blockedByLifetimeExtender() {
        com.android.systemui.util.Assert.isNotMainThread();

        NotificationLifetimeExtender extender = mock(NotificationLifetimeExtender.class);
        when(extender.shouldExtendLifetime(mEntry)).thenReturn(true);

        ArrayList<NotificationLifetimeExtender> extenders = mEntryManager.getLifetimeExtenders();
        extenders.clear();
        extenders.add(extender);

        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);

        mEntryManager.removeNotification(mSbn.getKey(), mRankingMap);

        assertNotNull(mEntryManager.getNotificationData().get(mSbn.getKey()));
        verify(extender).setShouldManageLifetime(mEntry, true /* shouldManage */);
        verify(mEntryListener, never()).onEntryRemoved(
                mEntry, null, false /* removedByUser */);
    }

    @Test
    public void testRemoveNotification_onEntryRemoveNotFiredIfEntryDoesntExist() {
        com.android.systemui.util.Assert.isNotMainThread();

        mEntryManager.removeNotification("not_a_real_key", mRankingMap);

        verify(mEntryListener, never()).onEntryRemoved(
                mEntry, null, false /* removedByUser */);
    }

    @Test
    public void testRemoveNotification_whilePending() throws InterruptedException {
        com.android.systemui.util.Assert.isNotMainThread();

        mEntryManager.setRowBinder(mMockedRowBinder);

        mEntryManager.addNotification(mSbn, mRankingMap);
        mEntryManager.removeNotification(mSbn.getKey(), mRankingMap);

        verify(mEntryListener, never()).onEntryRemoved(
                mEntry, null, false /* removedByUser */);
    }

    @Test
    public void testUpdateAppOps_foregroundNoti() {
        com.android.systemui.util.Assert.isNotMainThread();

        when(mForegroundServiceController.getStandardLayoutKey(anyInt(), anyString()))
                .thenReturn(mEntry.key);
        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);

        mEntryManager.updateNotificationsForAppOp(
                AppOpsManager.OP_CAMERA, mEntry.notification.getUid(),
                mEntry.notification.getPackageName(), true);

        verify(mPresenter, times(1)).updateNotificationViews();
        assertTrue(mEntryManager.getNotificationData().get(mEntry.key).mActiveAppOps.contains(
                AppOpsManager.OP_CAMERA));
    }

    @Test
    public void testUpdateAppOps_otherNoti() {
        com.android.systemui.util.Assert.isNotMainThread();

        when(mForegroundServiceController.getStandardLayoutKey(anyInt(), anyString()))
                .thenReturn(null);
        mEntryManager.updateNotificationsForAppOp(AppOpsManager.OP_CAMERA, 1000, "pkg", true);

        verify(mPresenter, never()).updateNotificationViews();
    }

    @Test
    public void testAddNotificationExistingAppOps() {
        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);
        ArraySet<Integer> expected = new ArraySet<>();
        expected.add(3);
        expected.add(235);
        expected.add(1);

        when(mForegroundServiceController.getAppOps(mEntry.notification.getUserId(),
                mEntry.notification.getPackageName())).thenReturn(expected);
        when(mForegroundServiceController.getStandardLayoutKey(
                mEntry.notification.getUserId(),
                mEntry.notification.getPackageName())).thenReturn(mEntry.key);

        mEntryManager.tagForeground(mEntry.notification);

        Assert.assertEquals(expected.size(), mEntry.mActiveAppOps.size());
        for (int op : expected) {
            assertTrue("Entry missing op " + op, mEntry.mActiveAppOps.contains(op));
        }
    }

    @Test
    public void testAdd_noExistingAppOps() {
        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);
        when(mForegroundServiceController.getStandardLayoutKey(
                mEntry.notification.getUserId(),
                mEntry.notification.getPackageName())).thenReturn(mEntry.key);
        when(mForegroundServiceController.getAppOps(mEntry.notification.getUserId(),
                mEntry.notification.getPackageName())).thenReturn(null);

        mEntryManager.tagForeground(mEntry.notification);
        Assert.assertEquals(0, mEntry.mActiveAppOps.size());
    }

    @Test
    public void testAdd_existingAppOpsNotForegroundNoti() {
        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(3);
        ops.add(235);
        ops.add(1);
        when(mForegroundServiceController.getAppOps(mEntry.notification.getUserId(),
                mEntry.notification.getPackageName())).thenReturn(ops);
        when(mForegroundServiceController.getStandardLayoutKey(
                mEntry.notification.getUserId(),
                mEntry.notification.getPackageName())).thenReturn("something else");

        mEntryManager.tagForeground(mEntry.notification);
        Assert.assertEquals(0, mEntry.mActiveAppOps.size());
    }

    @Test
    public void testUpdateNotificationRanking() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(mRow);
        mEntry.setInflationTask(mAsyncInflationTask);
        mEntryManager.getNotificationData().add(mEntry);
        setSmartActions(mEntry.key, new ArrayList<>(Arrays.asList(createAction())));

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow).setEntry(eq(mEntry));
        assertEquals(1, mEntry.systemGeneratedSmartActions.size());
        assertEquals("action", mEntry.systemGeneratedSmartActions.get(0).title);
    }

    @Test
    public void testUpdateNotificationRanking_noChange() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(mRow);
        mEntryManager.getNotificationData().add(mEntry);
        setSmartActions(mEntry.key, null);

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow, never()).setEntry(eq(mEntry));
        assertEquals(0, mEntry.systemGeneratedSmartActions.size());
    }

    @Test
    public void testUpdateNotificationRanking_rowNotInflatedYet() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(null);
        mEntryManager.getNotificationData().add(mEntry);
        setSmartActions(mEntry.key, new ArrayList<>(Arrays.asList(createAction())));

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow, never()).setEntry(eq(mEntry));
        assertEquals(1, mEntry.systemGeneratedSmartActions.size());
        assertEquals("action", mEntry.systemGeneratedSmartActions.get(0).title);
    }

    @Test
    public void testUpdateNotificationRanking_pendingNotification() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);

        mEntry.setRow(null);
        mEntryManager.mPendingNotifications.put(mEntry.key, mEntry);
        setSmartActions(mEntry.key, new ArrayList<>(Arrays.asList(createAction())));

        mEntryManager.updateNotificationRanking(mRankingMap);
        verify(mRow, never()).setEntry(eq(mEntry));
        assertEquals(1, mEntry.systemGeneratedSmartActions.size());
        assertEquals("action", mEntry.systemGeneratedSmartActions.get(0).title);
    }

    private Notification.Action createAction() {
        return new Notification.Action.Builder(
                Icon.createWithResource(getContext(), android.R.drawable.sym_def_app_icon),
                "action",
                PendingIntent.getBroadcast(getContext(), 0, new Intent("Action"), 0)).build();
    }
}
