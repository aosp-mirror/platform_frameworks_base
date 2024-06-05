/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.devicestate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Locale;

/**
 * Unit tests for {@link DeviceStateNotificationController}.
 * <p/>
 * Run with <code>atest com.android.server.devicestate.DeviceStateNotificationControllerTest</code>.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DeviceStateNotificationControllerTest {

    private static final int STATE_WITHOUT_NOTIFICATION = 1;
    private static final int STATE_WITH_ACTIVE_NOTIFICATION = 2;
    private static final int STATE_WITH_ALL_NOTIFICATION = 3;

    private static final int VALID_APP_UID = 1000;
    private static final int INVALID_APP_UID = 2000;
    private static final String VALID_APP_NAME = "Valid app name";
    private static final String INVALID_APP_NAME = "Invalid app name";
    private static final String VALID_APP_LABEL = "Valid app label";

    private static final String NAME_1 = "name1";
    private static final String TITLE_1 = "title1";
    private static final String CONTENT_1 = "content1:%1$s";
    private static final String NAME_2 = "name2";
    private static final String TITLE_2 = "title2";
    private static final String CONTENT_2 = "content2:%1$s";
    private static final String THERMAL_TITLE_2 = "thermal_title2";
    private static final String THERMAL_CONTENT_2 = "thermal_content2";
    private static final String POWER_SAVE_TITLE_2 = "power_save_title2";
    private static final String POWER_SAVE_CONTENT_2 = "power_save_content2";

    private DeviceStateNotificationController mController;

    private final ArgumentCaptor<Notification> mNotificationCaptor = ArgumentCaptor.forClass(
            Notification.class);
    private final NotificationManager mNotificationManager = mock(NotificationManager.class);

    private DeviceStateNotificationController.NotificationInfoProvider mNotificationInfoProvider;

    @Before
    public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        PackageManager packageManager = mock(PackageManager.class);
        Runnable cancelStateRunnable = mock(Runnable.class);
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);

        Handler handler = new DeviceStateNotificationControllerTestHandler(Looper.getMainLooper());

        final SparseArray<DeviceStateNotificationController.NotificationInfo> notificationInfos =
                new SparseArray<>();
        notificationInfos.put(STATE_WITH_ACTIVE_NOTIFICATION,
                new DeviceStateNotificationController.NotificationInfo(
                        NAME_1, TITLE_1, CONTENT_1,
                        "", "", "", ""));
        notificationInfos.put(STATE_WITH_ALL_NOTIFICATION,
                new DeviceStateNotificationController.NotificationInfo(
                        NAME_2, TITLE_2, CONTENT_2,
                        THERMAL_TITLE_2, THERMAL_CONTENT_2,
                        POWER_SAVE_TITLE_2, POWER_SAVE_CONTENT_2));

        mNotificationInfoProvider =
                new DeviceStateNotificationController.NotificationInfoProvider(context);
        mNotificationInfoProvider = spy(mNotificationInfoProvider);
        doReturn(notificationInfos).when(mNotificationInfoProvider).loadNotificationInfos();

        when(packageManager.getNameForUid(VALID_APP_UID)).thenReturn(VALID_APP_NAME);
        when(packageManager.getNameForUid(INVALID_APP_UID)).thenReturn(INVALID_APP_NAME);
        when(packageManager.getApplicationInfo(eq(VALID_APP_NAME), ArgumentMatchers.any()))
                .thenReturn(applicationInfo);
        when(packageManager.getApplicationInfo(eq(INVALID_APP_NAME), ArgumentMatchers.any()))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(applicationInfo.loadLabel(eq(packageManager))).thenReturn(VALID_APP_LABEL);

        mController = new DeviceStateNotificationController(
                context, handler, cancelStateRunnable, mNotificationInfoProvider,
                packageManager, mNotificationManager);
    }

    @Test
    public void test_activeNotification() {
        mController.showStateActiveNotificationIfNeeded(
                STATE_WITH_ACTIVE_NOTIFICATION, VALID_APP_UID);

        // Verify that the notification manager is called with correct notification information.
        verify(mNotificationManager).notify(
                eq(DeviceStateNotificationController.NOTIFICATION_TAG),
                eq(DeviceStateNotificationController.NOTIFICATION_ID),
                mNotificationCaptor.capture());
        Notification notification = mNotificationCaptor.getValue();
        assertEquals(TITLE_1, notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(String.format(CONTENT_1, VALID_APP_LABEL),
                notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notification.FLAG_ONGOING_EVENT,
                notification.flags & Notification.FLAG_ONGOING_EVENT);

        // Verify that the notification action is as expected.
        Notification.Action[] actions = notification.actions;
        assertEquals(1, actions.length);
        Notification.Action action = actions[0];
        assertEquals(DeviceStateNotificationController.INTENT_ACTION_CANCEL_STATE,
                action.actionIntent.getIntent().getAction());

        // Verify that the notification is properly canceled.
        mController.cancelNotification(STATE_WITH_ACTIVE_NOTIFICATION);
        verify(mNotificationManager).cancel(
                DeviceStateNotificationController.NOTIFICATION_TAG,
                DeviceStateNotificationController.NOTIFICATION_ID);
    }

    @Test
    public void test_powerSaveNotification() {
        // Verify that the active notification is created.
        mController.showStateActiveNotificationIfNeeded(
                STATE_WITH_ALL_NOTIFICATION, VALID_APP_UID);
        verify(mNotificationManager).notify(
                eq(DeviceStateNotificationController.NOTIFICATION_TAG),
                eq(DeviceStateNotificationController.NOTIFICATION_ID),
                mNotificationCaptor.capture());
        Notification notification = mNotificationCaptor.getValue();
        assertEquals(TITLE_2, notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(String.format(CONTENT_2, VALID_APP_LABEL),
                notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notification.FLAG_ONGOING_EVENT,
                notification.flags & Notification.FLAG_ONGOING_EVENT);
        Mockito.clearInvocations(mNotificationManager);

        // Verify that the thermal critical notification is created.
        mController.showPowerSaveNotificationIfNeeded(
                STATE_WITH_ALL_NOTIFICATION);
        verify(mNotificationManager).notify(
                eq(DeviceStateNotificationController.NOTIFICATION_TAG),
                eq(DeviceStateNotificationController.NOTIFICATION_ID),
                mNotificationCaptor.capture());
        notification = mNotificationCaptor.getValue();
        assertEquals(POWER_SAVE_TITLE_2, notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(POWER_SAVE_CONTENT_2, notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(0, notification.flags & Notification.FLAG_ONGOING_EVENT);

        // Verify that the notification is canceled.
        mController.cancelNotification(STATE_WITH_ALL_NOTIFICATION);
        verify(mNotificationManager).cancel(
                DeviceStateNotificationController.NOTIFICATION_TAG,
                DeviceStateNotificationController.NOTIFICATION_ID);
    }

    @Test
    public void test_thermalNotification() {
        // Verify that the active notification is created.
        mController.showStateActiveNotificationIfNeeded(
                STATE_WITH_ALL_NOTIFICATION, VALID_APP_UID);
        verify(mNotificationManager).notify(
                eq(DeviceStateNotificationController.NOTIFICATION_TAG),
                eq(DeviceStateNotificationController.NOTIFICATION_ID),
                mNotificationCaptor.capture());
        Notification notification = mNotificationCaptor.getValue();
        assertEquals(TITLE_2, notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(String.format(CONTENT_2, VALID_APP_LABEL),
                notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notification.FLAG_ONGOING_EVENT,
                notification.flags & Notification.FLAG_ONGOING_EVENT);
        Mockito.clearInvocations(mNotificationManager);

        // Verify that the thermal critical notification is created.
        mController.showThermalCriticalNotificationIfNeeded(
                STATE_WITH_ALL_NOTIFICATION);
        verify(mNotificationManager).notify(
                eq(DeviceStateNotificationController.NOTIFICATION_TAG),
                eq(DeviceStateNotificationController.NOTIFICATION_ID),
                mNotificationCaptor.capture());
        notification = mNotificationCaptor.getValue();
        assertEquals(THERMAL_TITLE_2, notification.extras.getString(Notification.EXTRA_TITLE));
        assertEquals(THERMAL_CONTENT_2, notification.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(0, notification.flags & Notification.FLAG_ONGOING_EVENT);

        // Verify that the notification is canceled.
        mController.cancelNotification(STATE_WITH_ALL_NOTIFICATION);
        verify(mNotificationManager).cancel(
                DeviceStateNotificationController.NOTIFICATION_TAG,
                DeviceStateNotificationController.NOTIFICATION_ID);
    }

    @Test
    public void test_deviceStateWithoutNotification() {
        // Verify that no notification is created.
        mController.showStateActiveNotificationIfNeeded(
                STATE_WITHOUT_NOTIFICATION, VALID_APP_UID);
        verify(mNotificationManager, Mockito.never()).notify(
                eq(DeviceStateNotificationController.NOTIFICATION_TAG),
                eq(DeviceStateNotificationController.NOTIFICATION_ID),
                mNotificationCaptor.capture());
    }

    @Test
    public void test_notificationInfoProvider() {
        assertNull(mNotificationInfoProvider.getCachedLocale());

        mNotificationInfoProvider.getNotificationInfos(Locale.ENGLISH);
        verify(mNotificationInfoProvider).refreshNotificationInfos(eq(Locale.ENGLISH));
        assertEquals(Locale.ENGLISH, mNotificationInfoProvider.getCachedLocale());
        clearInvocations(mNotificationInfoProvider);

        // If the same locale is used again, the provider uses the cached value, so it won't refresh
        mNotificationInfoProvider.getNotificationInfos(Locale.ENGLISH);
        verify(mNotificationInfoProvider, never()).refreshNotificationInfos(eq(Locale.ENGLISH));
        assertEquals(Locale.ENGLISH, mNotificationInfoProvider.getCachedLocale());
        clearInvocations(mNotificationInfoProvider);

        // If a different locale is used, the provider refreshes.
        mNotificationInfoProvider.getNotificationInfos(Locale.ITALY);
        verify(mNotificationInfoProvider).refreshNotificationInfos(eq(Locale.ITALY));
        assertEquals(Locale.ITALY, mNotificationInfoProvider.getCachedLocale());
        clearInvocations(mNotificationInfoProvider);
    }

    private static class DeviceStateNotificationControllerTestHandler extends Handler {
        DeviceStateNotificationControllerTestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            msg.getCallback().run();
            return true;
        }
    }
}
