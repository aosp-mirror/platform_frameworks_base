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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationEntryBuilder;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotifLog;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.util.DeviceConfigProxyFake;

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
    private NotificationData mNotificationData =
            new NotificationData(
                    new NotificationSectionsFeatureManager(new DeviceConfigProxyFake(), mContext),
                    mock(NotifLog.class),
                    mock(PeopleNotificationIdentifier.class));

    private int mNextNotifId = 0;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(NotificationLockscreenUserManager.class);

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
                NotificationVisibility.obtain(entry.getKey(), 0, 0, true),
                false);
        verify(mListContainer).cleanUpViewStateForEntry(entry);
    }

    @Test
    public void testCallUpdateNotificationsOnDeviceProvisionedChange() {
        mProvisionedListener.onDeviceProvisionedChanged();
        verify(mEntryManager).updateNotifications(anyString());
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
