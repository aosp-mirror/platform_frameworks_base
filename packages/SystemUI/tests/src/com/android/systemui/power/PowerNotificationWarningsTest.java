/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.power;

import static android.test.MoreAsserts.assertNotEqual;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PowerNotificationWarningsTest {
    private final NotificationManager mMockNotificationManager = mock(NotificationManager.class);
    private PowerNotificationWarnings mPowerNotificationWarnings;

    @Before
    public void setUp() throws Exception {
        // Test Instance.
        mPowerNotificationWarnings = new PowerNotificationWarnings(
                InstrumentationRegistry.getTargetContext(), mMockNotificationManager, null);
    }

    @Test
    public void testIsInvalidChargerWarningShowing_DefaultsToFalse() {
        assertFalse(mPowerNotificationWarnings.isInvalidChargerWarningShowing());
    }

    @Test
    public void testIsInvalidChargerWarningShowing_TrueAfterShow() {
        mPowerNotificationWarnings.showInvalidChargerWarning();
        assertTrue(mPowerNotificationWarnings.isInvalidChargerWarningShowing());
    }

    @Test
    public void testIsInvalidChargerWarningShowing_FalseAfterDismiss() {
        mPowerNotificationWarnings.showInvalidChargerWarning();
        mPowerNotificationWarnings.dismissInvalidChargerWarning();
        assertFalse(mPowerNotificationWarnings.isInvalidChargerWarningShowing());
    }

    @Test
    public void testShowInvalidChargerNotification_NotifyAsUser() {
        mPowerNotificationWarnings.showInvalidChargerWarning();
        verify(mMockNotificationManager, times(1))
                .notifyAsUser(anyString(), anyInt(), any(), any());
    }

    @Test
    public void testDismissInvalidChargerNotification_CancelAsUser() {
        mPowerNotificationWarnings.showInvalidChargerWarning();
        mPowerNotificationWarnings.dismissInvalidChargerWarning();
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(), anyInt(), any());
    }

    @Test
    public void testShowLowBatteryNotification_NotifyAsUser() {
        mPowerNotificationWarnings.showLowBatteryWarning(false);
        verify(mMockNotificationManager, times(1))
                .notifyAsUser(anyString(), anyInt(), any(), any());
    }

    @Test
    public void testDismissLowBatteryNotification_CancelAsUser() {
        mPowerNotificationWarnings.showLowBatteryWarning(false);
        mPowerNotificationWarnings.dismissLowBatteryWarning();
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(), anyInt(), any());
    }

    @Test
    public void testShowLowBatteryNotification_Silent() {
        mPowerNotificationWarnings.showLowBatteryWarning(false);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mMockNotificationManager)
                .notifyAsUser(anyString(), anyInt(), captor.capture(), any());
        assertEquals(null, captor.getValue().sound);
    }

    @Test
    public void testShowLowBatteryNotification_Sound() {
        mPowerNotificationWarnings.showLowBatteryWarning(true);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mMockNotificationManager)
                .notifyAsUser(anyString(), anyInt(), captor.capture(), any());
        assertNotEqual(null, captor.getValue().sound);
    }
}
