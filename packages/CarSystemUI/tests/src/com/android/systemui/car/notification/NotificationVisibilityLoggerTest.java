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

package com.android.systemui.car.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.car.notification.AlertEntry;
import com.android.car.notification.NotificationDataManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class NotificationVisibilityLoggerTest extends SysuiTestCase {

    private static final String PKG = "package_1";
    private static final String OP_PKG = "OpPackage";
    private static final int ID = 1;
    private static final String TAG = "Tag";
    private static final int UID = 2;
    private static final int INITIAL_PID = 3;
    private static final String CHANNEL_ID = "CHANNEL_ID";
    private static final String CONTENT_TITLE = "CONTENT_TITLE";
    private static final String OVERRIDE_GROUP_KEY = "OVERRIDE_GROUP_KEY";
    private static final long POST_TIME = 12345L;
    private static final UserHandle USER_HANDLE = new UserHandle(12);

    @Mock
    private IStatusBarService mBarService;
    @Mock
    private NotificationDataManager mNotificationDataManager;

    private NotificationVisibilityLogger mNotificationVisibilityLogger;
    private FakeExecutor mUiBgExecutor;
    private AlertEntry mMessageNotification;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */this);

        mUiBgExecutor = new FakeExecutor(new FakeSystemClock());
        Notification.Builder mNotificationBuilder1 = new Notification.Builder(mContext, CHANNEL_ID)
                .setContentTitle(CONTENT_TITLE);
        mMessageNotification = new AlertEntry(new StatusBarNotification(PKG, OP_PKG,
                ID, TAG, UID, INITIAL_PID, mNotificationBuilder1.build(), USER_HANDLE,
                OVERRIDE_GROUP_KEY, POST_TIME));

        when(mNotificationDataManager.getVisibleNotifications()).thenReturn(
                Collections.singletonList(mMessageNotification));

        mNotificationVisibilityLogger = new NotificationVisibilityLogger(
                mUiBgExecutor, mBarService, mNotificationDataManager);
    }

    @Test
    public void log_notifiesStatusBarService() throws RemoteException {
        mNotificationVisibilityLogger.log(/* isVisible= */ true);
        mUiBgExecutor.runNextReady();

        verify(mBarService).onNotificationVisibilityChanged(
                any(NotificationVisibility[].class), any(NotificationVisibility[].class));
    }

    @Test
    public void log_isVisibleIsTrue_notifiesOfNewlyVisibleItems() throws RemoteException {
        ArgumentCaptor<NotificationVisibility[]> newlyVisibleCaptor =
                ArgumentCaptor.forClass(NotificationVisibility[].class);
        ArgumentCaptor<NotificationVisibility[]> previouslyVisibleCaptor =
                ArgumentCaptor.forClass(NotificationVisibility[].class);

        mNotificationVisibilityLogger.log(/* isVisible= */ true);
        mUiBgExecutor.runNextReady();

        verify(mBarService).onNotificationVisibilityChanged(
                newlyVisibleCaptor.capture(), previouslyVisibleCaptor.capture());
        assertThat(newlyVisibleCaptor.getValue().length).isEqualTo(1);
        assertThat(previouslyVisibleCaptor.getValue().length).isEqualTo(0);
    }

    @Test
    public void log_isVisibleIsFalse_notifiesOfPreviouslyVisibleItems() throws RemoteException {
        ArgumentCaptor<NotificationVisibility[]> newlyVisibleCaptor =
                ArgumentCaptor.forClass(NotificationVisibility[].class);
        ArgumentCaptor<NotificationVisibility[]> previouslyVisibleCaptor =
                ArgumentCaptor.forClass(NotificationVisibility[].class);
        mNotificationVisibilityLogger.log(/* isVisible= */ true);
        mUiBgExecutor.runNextReady();
        reset(mBarService);

        mNotificationVisibilityLogger.log(/* isVisible= */ false);
        mUiBgExecutor.runNextReady();

        verify(mBarService).onNotificationVisibilityChanged(
                newlyVisibleCaptor.capture(), previouslyVisibleCaptor.capture());
        assertThat(previouslyVisibleCaptor.getValue().length).isEqualTo(1);
        assertThat(newlyVisibleCaptor.getValue().length).isEqualTo(0);
    }
}
