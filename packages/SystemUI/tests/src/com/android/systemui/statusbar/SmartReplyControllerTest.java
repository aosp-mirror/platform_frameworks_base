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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SmartReplyControllerTest extends SysuiTestCase {
    private static final String TEST_NOTIFICATION_KEY = "akey";
    private static final String TEST_CHOICE_TEXT = "A Reply";
    private static final int TEST_CHOICE_INDEX = 2;
    private static final int TEST_CHOICE_COUNT = 4;

    private Notification mNotification;
    private NotificationData.Entry mEntry;

    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private IStatusBarService mIStatusBarService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(NotificationEntryManager.class,
                mNotificationEntryManager);
        mDependency.injectTestDependency(IStatusBarService.class, mIStatusBarService);

        mNotification = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text").build();
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getNotification()).thenReturn(mNotification);
        when(sbn.getKey()).thenReturn(TEST_NOTIFICATION_KEY);
        mEntry = new NotificationData.Entry(sbn);
    }

    @Test
    public void testSendSmartReply_updatesRemoteInput() throws RemoteException {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getKey()).thenReturn(TEST_NOTIFICATION_KEY);
        when(mNotificationEntryManager.rebuildNotificationWithRemoteInput(
                argThat(entry -> entry.notification.getKey().equals(TEST_NOTIFICATION_KEY)),
                eq(TEST_CHOICE_TEXT), eq(true))).thenReturn(sbn);

        SmartReplyController controller = new SmartReplyController();
        controller.smartReplySent(mEntry, TEST_CHOICE_INDEX, TEST_CHOICE_TEXT);

        // Sending smart reply should make calls to NotificationEntryManager
        // to update the notification with reply and spinner.
        verify(mNotificationEntryManager).rebuildNotificationWithRemoteInput(
                argThat(entry -> entry.notification.getKey().equals(TEST_NOTIFICATION_KEY)),
                eq(TEST_CHOICE_TEXT), eq(true));
        verify(mNotificationEntryManager).updateNotification(
                argThat(sbn2 -> sbn2.getKey().equals(TEST_NOTIFICATION_KEY)), isNull());
    }

    @Test
    public void testSendSmartReply_logsToStatusBar() throws RemoteException {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getKey()).thenReturn(TEST_NOTIFICATION_KEY);
        when(mNotificationEntryManager.rebuildNotificationWithRemoteInput(
                argThat(entry -> entry.notification.getKey().equals(TEST_NOTIFICATION_KEY)),
                eq(TEST_CHOICE_TEXT), eq(true))).thenReturn(sbn);

        SmartReplyController controller = new SmartReplyController();
        controller.smartReplySent(mEntry, TEST_CHOICE_INDEX, TEST_CHOICE_TEXT);

        // Check we log the result to the status bar service.
        verify(mIStatusBarService).onNotificationSmartReplySent(TEST_NOTIFICATION_KEY,
                TEST_CHOICE_INDEX);
    }

    @Test
    public void testShowSmartReply_logsToStatusBar() throws RemoteException {
        SmartReplyController controller = new SmartReplyController();
        controller.smartRepliesAdded(mEntry, TEST_CHOICE_COUNT);

        // Check we log the result to the status bar service.
        verify(mIStatusBarService).onNotificationSmartRepliesAdded(TEST_NOTIFICATION_KEY,
                TEST_CHOICE_COUNT);
    }
}
