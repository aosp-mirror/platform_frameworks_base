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
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
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
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationEntryManagerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationPresenter mPresenter;
    @Mock private ExpandableNotificationRow mRow;
    @Mock private NotificationListContainer mListContainer;
    @Mock private NotificationEntryManager.Callback mCallback;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationListenerService.RankingMap mRankingMap;
    @Mock private RemoteInputController mRemoteInputController;
    @Mock private IStatusBarService mBarService;

    // Dependency mocks:
    @Mock private ForegroundServiceController mForegroundServiceController;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private NotificationGutsManager mGutsManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private NotificationMediaManager mMediaManager;
    @Mock private NotificationListener mNotificationListener;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private MetricsLogger mMetricsLogger;

    private NotificationData.Entry mEntry;
    private StatusBarNotification mSbn;
    private Handler mHandler;
    private TestableNotificationEntryManager mEntryManager;
    private CountDownLatch mCountDownLatch;

    private class TestableNotificationEntryManager extends NotificationEntryManager {
        private final CountDownLatch mCountDownLatch;

        public TestableNotificationEntryManager(Context context, IStatusBarService barService) {
            super(context);
            mBarService = barService;
            mCountDownLatch = new CountDownLatch(1);
            mUseHeadsUp = true;
        }

        @Override
        public void onAsyncInflationFinished(NotificationData.Entry entry) {
            super.onAsyncInflationFinished(entry);

            mCountDownLatch.countDown();
        }

        public CountDownLatch getCountDownLatch() {
            return mCountDownLatch;
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
                    null, null, null, true, sentiment, false);
            return true;
        }).when(mRankingMap).getRanking(eq(key), any(NotificationListenerService.Ranking.class));
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(ForegroundServiceController.class,
                mForegroundServiceController);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mDependency.injectTestDependency(NotificationGutsManager.class, mGutsManager);
        mDependency.injectTestDependency(NotificationRemoteInputManager.class, mRemoteInputManager);
        mDependency.injectTestDependency(NotificationMediaManager.class, mMediaManager);
        mDependency.injectTestDependency(NotificationListener.class, mNotificationListener);
        mDependency.injectTestDependency(DeviceProvisionedController.class,
                mDeviceProvisionedController);
        mDependency.injectTestDependency(VisualStabilityManager.class, mVisualStabilityManager);
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);

        mHandler = new Handler(Looper.getMainLooper());
        mCountDownLatch = new CountDownLatch(1);

        when(mPresenter.getHandler()).thenReturn(mHandler);
        when(mPresenter.getNotificationLockscreenUserManager()).thenReturn(mLockscreenUserManager);
        when(mPresenter.getGroupManager()).thenReturn(mGroupManager);
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);
        when(mListContainer.getViewParentForNotification(any())).thenReturn(
                new FrameLayout(mContext));

        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, n.build(), new UserHandle(ActivityManager.getCurrentUser()), null, 0);
        mEntry = new NotificationData.Entry(mSbn);
        mEntry.expandedIcon = mock(StatusBarIconView.class);

        mEntryManager = new TestableNotificationEntryManager(mContext, mBarService);
        mEntryManager.setUpWithPresenter(mPresenter, mListContainer, mCallback, mHeadsUpManager);

        setUserSentiment(mEntry.key, NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL);
    }

    @Test
    public void testAddNotification() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();

        doAnswer(invocation -> {
            mCountDownLatch.countDown();
            return null;
        }).when(mCallback).onBindRow(any(), any(), any(), any());

        // Post on main thread, otherwise we will be stuck waiting here for the inflation finished
        // callback forever, since it won't execute until the tests ends.
        mHandler.post(() -> {
            mEntryManager.addNotification(mSbn, mRankingMap);
        });
        assertTrue(mCountDownLatch.await(1, TimeUnit.MINUTES));
        assertTrue(mEntryManager.getCountDownLatch().await(1, TimeUnit.MINUTES));
        waitForIdleSync(mHandler);

        // Check that no inflation error occurred.
        verify(mBarService, never()).onNotificationError(any(), any(), anyInt(), anyInt(), anyInt(),
                any(), anyInt());
        verify(mForegroundServiceController).addNotification(eq(mSbn), anyInt());

        // Row inflation:
        ArgumentCaptor<NotificationData.Entry> entryCaptor = ArgumentCaptor.forClass(
                NotificationData.Entry.class);
        verify(mCallback).onBindRow(entryCaptor.capture(), any(), eq(mSbn), any());
        NotificationData.Entry entry = entryCaptor.getValue();
        verify(mRemoteInputManager).bindRow(entry.row);

        // Row content inflation:
        verify(mCallback).onNotificationAdded(entry);
        verify(mPresenter).updateNotificationViews();

        assertEquals(mEntryManager.getNotificationData().get(mSbn.getKey()), entry);
        assertNotNull(entry.row);
        assertEquals(mEntry.userSentiment,
                NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL);
    }

    @Test
    public void testUpdateNotification() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();

        mEntryManager.getNotificationData().add(mEntry);

        setUserSentiment(mEntry.key, NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE);

        mHandler.post(() -> {
            mEntryManager.updateNotification(mSbn, mRankingMap);
        });
        // Wait for content update.
        mEntryManager.getCountDownLatch().await(1, TimeUnit.MINUTES);
        waitForIdleSync(mHandler);

        verify(mBarService, never()).onNotificationError(any(), any(), anyInt(), anyInt(), anyInt(),
                any(), anyInt());

        verify(mRemoteInputManager).onUpdateNotification(mEntry);
        verify(mPresenter).updateNotificationViews();
        verify(mForegroundServiceController).updateNotification(eq(mSbn), anyInt());
        verify(mCallback).onNotificationUpdated(mSbn);
        assertNotNull(mEntry.row);
        assertEquals(mEntry.userSentiment,
                NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE);
    }

    @Test
    public void testRemoveNotification() throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();

        mEntry.row = mRow;
        mEntryManager.getNotificationData().add(mEntry);

        mHandler.post(() -> {
            mEntryManager.removeNotification(mSbn.getKey(), mRankingMap);
        });
        waitForIdleSync(mHandler);

        verify(mBarService, never()).onNotificationError(any(), any(), anyInt(), anyInt(), anyInt(),
                any(), anyInt());

        verify(mMediaManager).onNotificationRemoved(mSbn.getKey());
        verify(mRemoteInputManager).onRemoveNotification(mEntry);
        verify(mForegroundServiceController).removeNotification(mSbn);
        verify(mListContainer).cleanUpViewState(mRow);
        verify(mPresenter).updateNotificationViews();
        verify(mCallback).onNotificationRemoved(mSbn.getKey(), mSbn);
        verify(mRow).setRemoved();

        assertNull(mEntryManager.getNotificationData().get(mSbn.getKey()));
    }

    @Test
    public void testUpdateAppOps_foregroundNoti() {
        com.android.systemui.util.Assert.isNotMainThread();

        when(mForegroundServiceController.getStandardLayoutKey(anyInt(), anyString()))
                .thenReturn(mEntry.key);
        mEntry.row = mRow;
        mEntryManager.getNotificationData().add(mEntry);

        mHandler.post(() -> {
            mEntryManager.updateNotificationsForAppOp(
                    AppOpsManager.OP_CAMERA, mEntry.notification.getUid(),
                    mEntry.notification.getPackageName(), true);
        });
        waitForIdleSync(mHandler);

        verify(mPresenter, times(1)).updateNotificationViews();
        assertTrue(mEntryManager.getNotificationData().get(mEntry.key).mActiveAppOps.contains(
                AppOpsManager.OP_CAMERA));
    }

    @Test
    public void testUpdateAppOps_otherNoti() {
        com.android.systemui.util.Assert.isNotMainThread();

        when(mForegroundServiceController.getStandardLayoutKey(anyInt(), anyString()))
                .thenReturn(null);
        mHandler.post(() -> {
            mEntryManager.updateNotificationsForAppOp(AppOpsManager.OP_CAMERA, 1000, "pkg", true);
        });
        waitForIdleSync(mHandler);

        verify(mPresenter, never()).updateNotificationViews();
    }

    @Test
    public void testAddNotificationExistingAppOps() {
        mEntry.row = mRow;
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
        mEntry.row = mRow;
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
        mEntry.row = mRow;
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
}
