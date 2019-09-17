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

package com.android.systemui.statusbar.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationListControllerTest extends SysuiTestCase {
    private NotificationListController mController;

    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationListContainer mListContainer;
    @Mock private ForegroundServiceController mForegroundServiceController;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;

    @Captor private ArgumentCaptor<NotificationEntryListener> mEntryListenerCaptor;
    @Captor private ArgumentCaptor<DeviceProvisionedListener> mProvisionedCaptor;

    private NotificationEntryListener mEntryListener;
    private DeviceProvisionedListener mProvisionedListener;

    // TODO: Remove this once EntryManager no longer needs to be mocked
    private NotificationData mNotificationData = new NotificationData(mContext);

    private int mNextNotifId = 0;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mEntryManager.getNotificationData()).thenReturn(mNotificationData);

        mController = new NotificationListController(
                mEntryManager,
                mListContainer,
                mForegroundServiceController,
                mDeviceProvisionedController);
        mController.bind();

        // Capture callbacks passed to mocks
        verify(mEntryManager).addNotificationEntryListener(mEntryListenerCaptor.capture());
        mEntryListener = mEntryListenerCaptor.getValue();
        verify(mDeviceProvisionedController).addCallback(mProvisionedCaptor.capture());
        mProvisionedListener = mProvisionedCaptor.getValue();
    }

    @Test
    public void testCleanUpViewStateOnEntryRemoved() {
        final NotificationEntry entry = buildEntry();
        mEntryListener.onEntryRemoved(
                entry,
                NotificationVisibility.obtain(entry.key, 0, 0, true),
                false);
        verify(mListContainer).cleanUpViewStateForEntry(entry);
    }

    @Test
    public void testCallUpdateNotificationsOnDeviceProvisionedChange() {
        mProvisionedListener.onDeviceProvisionedChanged();
        verify(mEntryManager).updateNotifications();
    }

    @Test
    public void testAppOps_appOpAddedToForegroundNotif() {
        // GIVEN a notification associated with a foreground service
        final NotificationEntry entry = buildEntry();
        mNotificationData.add(entry);
        when(mForegroundServiceController.getStandardLayoutKey(anyInt(), anyString()))
                .thenReturn(entry.key);

        // WHEN we are notified of a new app op
        mController.updateNotificationsForAppOp(
                AppOpsManager.OP_CAMERA,
                entry.notification.getUid(),
                entry.notification.getPackageName(),
                true);

        // THEN the app op is added to the entry
        assertTrue(entry.mActiveAppOps.contains(AppOpsManager.OP_CAMERA));
        // THEN updateNotifications() is called
        verify(mEntryManager, times(1)).updateNotifications();
    }

    @Test
    public void testAppOps_appOpAddedToUnrelatedNotif() {
        // GIVEN No current foreground notifs
        when(mForegroundServiceController.getStandardLayoutKey(anyInt(), anyString()))
                .thenReturn(null);

        // WHEN An unrelated notification gets a new app op
        mController.updateNotificationsForAppOp(AppOpsManager.OP_CAMERA, 1000, "pkg", true);

        // THEN We never call updateNotifications()
        verify(mEntryManager, never()).updateNotifications();
    }

    @Test
    public void testAppOps_addNotificationWithExistingAppOps() {
        // GIVEN a notification with three associated app ops that is associated with a foreground
        // service
        final NotificationEntry entry = buildEntry();
        mNotificationData.add(entry);
        ArraySet<Integer> expected = new ArraySet<>();
        expected.add(3);
        expected.add(235);
        expected.add(1);
        when(mForegroundServiceController.getStandardLayoutKey(
                entry.notification.getUserId(),
                entry.notification.getPackageName())).thenReturn(entry.key);
        when(mForegroundServiceController.getAppOps(entry.notification.getUserId(),
                entry.notification.getPackageName())).thenReturn(expected);

        // WHEN the notification is added
        mEntryListener.onBeforeNotificationAdded(entry);

        // THEN the entry is tagged with all three app ops
        assertEquals(expected.size(), entry.mActiveAppOps.size());
        for (int op : expected) {
            assertTrue("Entry missing op " + op, entry.mActiveAppOps.contains(op));
        }
    }

    @Test
    public void testAdd_addNotificationWithNoExistingAppOps() {
        // GIVEN a notification with NO associated app ops
        final NotificationEntry entry = buildEntry();

        mNotificationData.add(entry);
        when(mForegroundServiceController.getStandardLayoutKey(
                entry.notification.getUserId(),
                entry.notification.getPackageName())).thenReturn(entry.key);
        when(mForegroundServiceController.getAppOps(entry.notification.getUserId(),
                entry.notification.getPackageName())).thenReturn(null);

        // WHEN the notification is added
        mEntryListener.onBeforeNotificationAdded(entry);

        // THEN the entry doesn't have any app ops associated with it
        assertEquals(0, entry.mActiveAppOps.size());
    }

    @Test
    public void testAdd_addNonForegroundNotificationWithExistingAppOps() {
        // GIVEN a notification with app ops that isn't associated with a foreground service
        final NotificationEntry entry = buildEntry();
        mNotificationData.add(entry);
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(3);
        ops.add(235);
        ops.add(1);
        when(mForegroundServiceController.getAppOps(entry.notification.getUserId(),
                entry.notification.getPackageName())).thenReturn(ops);
        when(mForegroundServiceController.getStandardLayoutKey(
                entry.notification.getUserId(),
                entry.notification.getPackageName())).thenReturn("something else");

        // WHEN the notification is added
        mEntryListener.onBeforeNotificationAdded(entry);

        // THEN the entry doesn't have any app ops associated with it
        assertEquals(0, entry.mActiveAppOps.size());
    }

    private NotificationEntry buildEntry() {
        mNextNotifId++;

        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");

        return new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setId(mNextNotifId)
                .setUid(TEST_UID)
                .setNotification(n.build())
                .setUser(new UserHandle(ActivityManager.getCurrentUser()))
                .build();
    }

    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;
}
