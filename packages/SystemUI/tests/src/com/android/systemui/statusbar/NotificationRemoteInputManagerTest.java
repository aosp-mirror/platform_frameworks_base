package com.android.systemui.statusbar;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationRemoteInputManagerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    @Mock private NotificationPresenter mPresenter;
    @Mock private RemoteInputController.Delegate mDelegate;
    @Mock private NotificationRemoteInputManager.Callback mCallback;
    @Mock private RemoteInputController mController;
    @Mock private NotificationListenerService.RankingMap mRanking;
    @Mock private ExpandableNotificationRow mRow;

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;

    private TestableNotificationRemoteInputManager mRemoteInputManager;
    private StatusBarNotification mSbn;
    private NotificationData.Entry mEntry;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);

        when(mPresenter.getHandler()).thenReturn(Handler.createAsync(Looper.myLooper()));
        when(mEntryManager.getLatestRankingMap()).thenReturn(mRanking);

        mRemoteInputManager = new TestableNotificationRemoteInputManager(mContext);
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, new Notification(), UserHandle.CURRENT, null, 0);
        mEntry = new NotificationData.Entry(mSbn);
        mEntry.row = mRow;

        mRemoteInputManager.setUpWithPresenterForTest(mPresenter, mEntryManager, mCallback,
                mDelegate, mController);
    }

    @Test
    public void testOnRemoveNotificationNotKept() {
        assertFalse(mRemoteInputManager.onRemoveNotification(mEntry));
        assertTrue(mRemoteInputManager.getRemoteInputEntriesToRemoveOnCollapse().isEmpty());
    }

    @Test
    public void testOnRemoveNotificationKept() {
        when(mController.isRemoteInputActive(mEntry)).thenReturn(true);
        assertTrue(mRemoteInputManager.onRemoveNotification(mEntry));
        assertTrue(mRemoteInputManager.getRemoteInputEntriesToRemoveOnCollapse().equals(
                Sets.newArraySet(mEntry)));
    }

    @Test
    public void testPerformOnRemoveNotification() {
        when(mController.isRemoteInputActive(mEntry)).thenReturn(true);
        mRemoteInputManager.onPerformRemoveNotification(mSbn, mEntry);

        verify(mController).removeRemoteInput(mEntry, null);
    }

    @Test
    public void testRemoveRemoteInputEntriesKeptUntilCollapsed() {
        mRemoteInputManager.getRemoteInputEntriesToRemoveOnCollapse().add(mEntry);
        mRemoteInputManager.removeRemoteInputEntriesKeptUntilCollapsed();

        assertTrue(mRemoteInputManager.getRemoteInputEntriesToRemoveOnCollapse().isEmpty());
        verify(mController).removeRemoteInput(mEntry, null);
        verify(mEntryManager).removeNotification(mEntry.key, mRanking);
    }

    private class TestableNotificationRemoteInputManager extends NotificationRemoteInputManager {

        public TestableNotificationRemoteInputManager(Context context) {
            super(context);
        }

        public void setUpWithPresenterForTest(NotificationPresenter presenter,
                NotificationEntryManager entryManager,
                Callback callback,
                RemoteInputController.Delegate delegate,
                RemoteInputController controller) {
            super.setUpWithPresenter(presenter, entryManager, callback, delegate);
            mRemoteInputController = controller;
        }
    }
}
