package com.android.systemui.statusbar;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationRemoteInputManager.RemoteInputActiveExtender;
import com.android.systemui.statusbar.NotificationRemoteInputManager.RemoteInputHistoryExtender;
import com.android.systemui.statusbar.NotificationRemoteInputManager.SmartReplyHistoryExtender;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.ShadeController;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import dagger.Lazy;

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
    @Mock private SmartReplyController mSmartReplyController;
    @Mock private NotificationListenerService.RankingMap mRanking;
    @Mock private ExpandableNotificationRow mRow;

    // Dependency mocks:
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;

    private TestableNotificationRemoteInputManager mRemoteInputManager;
    private StatusBarNotification mSbn;
    private NotificationEntry mEntry;
    private RemoteInputHistoryExtender mRemoteInputHistoryExtender;
    private SmartReplyHistoryExtender mSmartReplyHistoryExtender;
    private RemoteInputActiveExtender mRemoteInputActiveExtender;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mRemoteInputManager = new TestableNotificationRemoteInputManager(mContext,
                mLockscreenUserManager, mSmartReplyController, mEntryManager,
                () -> mock(ShadeController.class),
                Handler.createAsync(Looper.myLooper()));
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, new Notification(), UserHandle.CURRENT, null, 0);
        mEntry = new NotificationEntry(mSbn);
        mEntry.setRow(mRow);

        mRemoteInputManager.setUpWithPresenterForTest(mCallback,
                mDelegate, mController);
        for (NotificationLifetimeExtender extender : mRemoteInputManager.getLifetimeExtenders()) {
            extender.setCallback(
                    mock(NotificationLifetimeExtender.NotificationSafeToRemoveCallback.class));
        }
    }

    @Test
    public void testPerformOnRemoveNotification() {
        when(mController.isRemoteInputActive(mEntry)).thenReturn(true);
        mRemoteInputManager.onPerformRemoveNotification(mEntry, mSbn.getKey());

        verify(mController).removeRemoteInput(mEntry, null);
    }

    @Test
    public void testShouldExtendLifetime_remoteInputActive() {
        when(mController.isRemoteInputActive(mEntry)).thenReturn(true);

        assertTrue(mRemoteInputActiveExtender.shouldExtendLifetime(mEntry));
    }

    @Test
    public void testShouldExtendLifetime_isSpinning() {
        NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY = true;
        when(mController.isSpinning(mEntry.key)).thenReturn(true);

        assertTrue(mRemoteInputHistoryExtender.shouldExtendLifetime(mEntry));
    }

    @Test
    public void testShouldExtendLifetime_recentRemoteInput() {
        NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY = true;
        mEntry.lastRemoteInputSent = SystemClock.elapsedRealtime();

        assertTrue(mRemoteInputHistoryExtender.shouldExtendLifetime(mEntry));
    }

    @Test
    public void testShouldExtendLifetime_smartReplySending() {
        NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY = true;
        when(mSmartReplyController.isSendingSmartReply(mEntry.key)).thenReturn(true);

        assertTrue(mSmartReplyHistoryExtender.shouldExtendLifetime(mEntry));
    }

    @Test
    public void testNotificationWithRemoteInputActiveIsRemovedOnCollapse() {
        mRemoteInputActiveExtender.setShouldManageLifetime(mEntry, true /* shouldManage */);

        assertEquals(mRemoteInputManager.getEntriesKeptForRemoteInputActive(),
                Sets.newArraySet(mEntry));

        mRemoteInputManager.onPanelCollapsed();

        assertTrue(mRemoteInputManager.getEntriesKeptForRemoteInputActive().isEmpty());
    }

    @Test
    public void testRebuildWithRemoteInput_noExistingInputNoSpinner() {
        StatusBarNotification newSbn =
                mRemoteInputManager.rebuildNotificationWithRemoteInput(mEntry, "A Reply", false);
        CharSequence[] messages = newSbn.getNotification().extras
                .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY);
        assertEquals(1, messages.length);
        assertEquals("A Reply", messages[0]);
        assertFalse(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false));
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false));
    }

    @Test
    public void testRebuildWithRemoteInput_noExistingInputWithSpinner() {
        StatusBarNotification newSbn =
                mRemoteInputManager.rebuildNotificationWithRemoteInput(mEntry, "A Reply", true);
        CharSequence[] messages = newSbn.getNotification().extras
                .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY);
        assertEquals(1, messages.length);
        assertEquals("A Reply", messages[0]);
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false));
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false));
    }

    @Test
    public void testRebuildWithRemoteInput_withExistingInput() {
        // Setup a notification entry with 1 remote input.
        StatusBarNotification newSbn =
                mRemoteInputManager.rebuildNotificationWithRemoteInput(mEntry, "A Reply", false);
        NotificationEntry entry = new NotificationEntry(newSbn);

        // Try rebuilding to add another reply.
        newSbn = mRemoteInputManager.rebuildNotificationWithRemoteInput(entry, "Reply 2", true);
        CharSequence[] messages = newSbn.getNotification().extras
                .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY);
        assertEquals(2, messages.length);
        assertEquals("Reply 2", messages[0]);
        assertEquals("A Reply", messages[1]);
    }

    @Test
    public void testRebuildNotificationForCanceledSmartReplies() {
        // Try rebuilding to remove spinner and hide buttons.
        StatusBarNotification newSbn =
                mRemoteInputManager.rebuildNotificationForCanceledSmartReplies(mEntry);
        assertFalse(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false));
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false));
    }


    private class TestableNotificationRemoteInputManager extends NotificationRemoteInputManager {


        TestableNotificationRemoteInputManager(Context context,
                NotificationLockscreenUserManager lockscreenUserManager,
                SmartReplyController smartReplyController,
                NotificationEntryManager notificationEntryManager,
                Lazy<ShadeController> shadeController,
                Handler mainHandler) {
            super(context, lockscreenUserManager, smartReplyController, notificationEntryManager,
                    shadeController, mainHandler);
        }

        public void setUpWithPresenterForTest(Callback callback,
                RemoteInputController.Delegate delegate,
                RemoteInputController controller) {
            super.setUpWithCallback(callback, delegate);
            mRemoteInputController = controller;
        }

        @Override
        protected void addLifetimeExtenders() {
            mRemoteInputActiveExtender = new RemoteInputActiveExtender();
            mRemoteInputHistoryExtender = new RemoteInputHistoryExtender();
            mSmartReplyHistoryExtender = new SmartReplyHistoryExtender();
            mLifetimeExtenders.add(mRemoteInputHistoryExtender);
            mLifetimeExtenders.add(mSmartReplyHistoryExtender);
            mLifetimeExtenders.add(mRemoteInputActiveExtender);
        }
    }
}
