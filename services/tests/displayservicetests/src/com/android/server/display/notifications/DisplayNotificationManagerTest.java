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

package com.android.server.display.notifications;

import static android.app.Notification.FLAG_ONGOING_EVENT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.notifications.DisplayNotificationManager.Injector;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link DisplayNotificationManager}
 * Run: atest DisplayNotificationManagerTest
 */
@SmallTest
@RunWith(TestParameterInjector.class)
public class DisplayNotificationManagerTest {
    @Mock
    private Injector mMockedInjector;
    @Mock
    private NotificationManager mMockedNotificationManager;
    @Mock
    private DisplayManagerFlags mMockedFlags;
    @Captor
    private ArgumentCaptor<String> mNotifyTagCaptor;
    @Captor
    private ArgumentCaptor<Integer> mNotifyNoteIdCaptor;
    @Captor
    private ArgumentCaptor<Notification> mNotifyAsUserNotificationCaptor;

    /** Setup tests. */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testNotificationOnHighTemperatureExternalDisplayNotAllowed() {
        var dnm = createDisplayNotificationManager(/*isNotificationManagerAvailable=*/ true,
                /*isErrorHandlingEnabled=*/ true);
        dnm.onHighTemperatureExternalDisplayNotAllowed();
        assertExpectedNotification();
    }

    @Test
    public void testNotificationOnHotplugConnectionError() {
        var dnm = createDisplayNotificationManager(/*isNotificationManagerAvailable=*/ true,
                /*isErrorHandlingEnabled=*/ true);
        dnm.onHotplugConnectionError();
        assertExpectedNotification();
    }

    @Test
    public void testNotificationOnDisplayPortLinkTrainingFailure() {
        var dnm = createDisplayNotificationManager(/*isNotificationManagerAvailable=*/ true,
                /*isErrorHandlingEnabled=*/ true);
        dnm.onDisplayPortLinkTrainingFailure();
        assertExpectedNotification();
    }

    @Test
    public void testNotificationOnCableNotCapableDisplayPort() {
        var dnm = createDisplayNotificationManager(/*isNotificationManagerAvailable=*/ true,
                /*isErrorHandlingEnabled=*/ true);
        dnm.onCableNotCapableDisplayPort();
        assertExpectedNotification();
    }

    @Test
    public void testNoErrorNotification(
            @TestParameter final boolean isNotificationManagerAvailable,
            @TestParameter final boolean isErrorHandlingEnabled) {
        /* This case is tested by #testNotificationOnHotplugConnectionError,
            #testNotificationOnDisplayPortLinkTrainingFailure,
            #testNotificationOnCableNotCapableDisplayPort */
        assumeFalse(isNotificationManagerAvailable && isErrorHandlingEnabled);
        var dnm = createDisplayNotificationManager(isNotificationManagerAvailable,
                isErrorHandlingEnabled);
        // None of these methods should trigger a notification now.
        dnm.onHotplugConnectionError();
        dnm.onDisplayPortLinkTrainingFailure();
        dnm.onCableNotCapableDisplayPort();
        dnm.onHighTemperatureExternalDisplayNotAllowed();
        verify(mMockedNotificationManager, never()).notify(anyString(), anyInt(), any());
    }

    private DisplayNotificationManager createDisplayNotificationManager(
            final boolean isNotificationManagerAvailable,
            final boolean isErrorHandlingEnabled) {
        when(mMockedFlags.isConnectedDisplayErrorHandlingEnabled()).thenReturn(
                isErrorHandlingEnabled);
        when(mMockedInjector.getNotificationManager()).thenReturn(
                (isNotificationManagerAvailable) ? mMockedNotificationManager : null);
        // Usb errors detector is tested in ConnectedDisplayUsbErrorsDetectorTest
        when(mMockedInjector.getUsbErrorsDetector()).thenReturn(/* usbErrorsDetector= */ null);
        final var displayNotificationManager = new DisplayNotificationManager(mMockedFlags,
                ApplicationProvider.getApplicationContext(), mMockedInjector);
        displayNotificationManager.onBootCompleted();
        return displayNotificationManager;
    }

    private void assertExpectedNotification() {
        verify(mMockedNotificationManager).notify(
                mNotifyTagCaptor.capture(),
                mNotifyNoteIdCaptor.capture(),
                mNotifyAsUserNotificationCaptor.capture());
        assertThat(mNotifyTagCaptor.getValue()).isEqualTo("DisplayNotificationManager");
        assertThat((int) mNotifyNoteIdCaptor.getValue()).isEqualTo(1);
        final var notification = mNotifyAsUserNotificationCaptor.getValue();
        assertThat(notification.getChannelId()).isEqualTo("ALERTS");
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ERROR);
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PUBLIC);
        assertThat(notification.flags & FLAG_ONGOING_EVENT).isEqualTo(0);
        assertThat(notification.when).isEqualTo(0);
        assertThat(notification.getTimeoutAfter()).isEqualTo(30000L);
    }
}
