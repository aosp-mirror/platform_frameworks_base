/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SmartReplyControllerTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;
    private static final String TEST_CHOICE_TEXT = "A Reply";
    private static final int TEST_CHOICE_INDEX = 2;
    private static final int TEST_CHOICE_COUNT = 4;

    private Notification mNotification;
    private NotificationData.Entry mEntry;
    private SmartReplyController mSmartReplyController;
    private NotificationRemoteInputManager mRemoteInputManager;

    @Mock private NotificationPresenter mPresenter;
    @Mock private RemoteInputController.Delegate mDelegate;
    @Mock private NotificationRemoteInputManager.Callback mCallback;
    @Mock private StatusBarNotification mSbn;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Mock private IStatusBarService mIStatusBarService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class,
                mNotificationEntryManager);
        mDependency.injectTestDependency(IStatusBarService.class, mIStatusBarService);

        mSmartReplyController = new SmartReplyController();
        mDependency.injectTestDependency(SmartReplyController.class,
                mSmartReplyController);

        mRemoteInputManager = new NotificationRemoteInputManager(mContext);
        mRemoteInputManager.setUpWithPresenter(mPresenter, mCallback, mDelegate);
        mNotification = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text").build();

        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
                0, mNotification, new UserHandle(ActivityManager.getCurrentUser()), null, 0);
        mEntry = new NotificationData.Entry(mSbn);
    }

    @Test
    public void testSendSmartReply_updatesRemoteInput() {
        mSmartReplyController.smartReplySent(mEntry, TEST_CHOICE_INDEX, TEST_CHOICE_TEXT);

        // Sending smart reply should make calls to NotificationEntryManager
        // to update the notification with reply and spinner.
        verify(mNotificationEntryManager).updateNotification(
                argThat(sbn -> sbn.getKey().equals(mSbn.getKey())), isNull());
    }

    @Test
    public void testSendSmartReply_logsToStatusBar() throws RemoteException {
        mSmartReplyController.smartReplySent(mEntry, TEST_CHOICE_INDEX, TEST_CHOICE_TEXT);

        // Check we log the result to the status bar service.
        verify(mIStatusBarService).onNotificationSmartReplySent(mSbn.getKey(),
                TEST_CHOICE_INDEX);
    }

    @Test
    public void testShowSmartReply_logsToStatusBar() throws RemoteException {
        mSmartReplyController.smartRepliesAdded(mEntry, TEST_CHOICE_COUNT);

        // Check we log the result to the status bar service.
        verify(mIStatusBarService).onNotificationSmartRepliesAdded(mSbn.getKey(),
                TEST_CHOICE_COUNT);
    }

    @Test
    public void testSendSmartReply_reportsSending() {
        mSmartReplyController.smartReplySent(mEntry, TEST_CHOICE_INDEX, TEST_CHOICE_TEXT);

        assertTrue(mSmartReplyController.isSendingSmartReply(mSbn.getKey()));
    }

    @Test
    public void testSendingSmartReply_afterRemove_shouldReturnFalse() {
        mSmartReplyController.smartReplySent(mEntry, TEST_CHOICE_INDEX, TEST_CHOICE_TEXT);
        mSmartReplyController.stopSending(mEntry);

        assertFalse(mSmartReplyController.isSendingSmartReply(mSbn.getKey()));
    }
}
