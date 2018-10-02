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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.NotificationChannels;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PowerNotificationWarningsTest extends SysuiTestCase {

    public static final String FORMATTED_45M = "0h 45m";
    public static final String FORMATTED_HOUR = "1h 0m";
    private final NotificationManager mMockNotificationManager = mock(NotificationManager.class);
    private PowerNotificationWarnings mPowerNotificationWarnings, mSpyPowerNotificationWarnings;

    @Before
    public void setUp() throws Exception {
        // Test Instance.
        mContext.addMockSystemService(NotificationManager.class, mMockNotificationManager);
        mPowerNotificationWarnings = new PowerNotificationWarnings(mContext);
        mSpyPowerNotificationWarnings = spy(mPowerNotificationWarnings);
    }

    @After
    public void tearDown() throws Exception {
        if (mSpyPowerNotificationWarnings.mAlarmDialog != null) {
            mSpyPowerNotificationWarnings.mAlarmDialog.dismiss();
            mSpyPowerNotificationWarnings.mAlarmDialog = null;
        }
        if (mSpyPowerNotificationWarnings.mAlarmDialogAllowDismiss != null) {
            mSpyPowerNotificationWarnings.mAlarmDialogAllowDismiss.dismiss();
            mSpyPowerNotificationWarnings.mAlarmDialogAllowDismiss = null;
        }
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
                .notifyAsUser(anyString(), eq(SystemMessage.NOTE_BAD_CHARGER), any(), any());
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(),
                eq(SystemMessage.NOTE_POWER_LOW), any());
    }

    @Test
    public void testDismissInvalidChargerNotification_CancelAsUser() {
        mPowerNotificationWarnings.showInvalidChargerWarning();
        mPowerNotificationWarnings.dismissInvalidChargerWarning();
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(),
                eq(SystemMessage.NOTE_BAD_CHARGER), any());
    }

    @Test
    public void testShowLowBatteryNotification_NotifyAsUser() {
        mPowerNotificationWarnings.showLowBatteryWarning(false);
        verify(mMockNotificationManager, times(1))
                .notifyAsUser(anyString(), eq(SystemMessage.NOTE_POWER_LOW), any(), any());
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(),
                eq(SystemMessage.NOTE_BAD_CHARGER), any());
    }

    @Test
    public void testDismissLowBatteryNotification_CancelAsUser() {
        mPowerNotificationWarnings.showLowBatteryWarning(false);
        mPowerNotificationWarnings.dismissLowBatteryWarning();
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(),
                eq(SystemMessage.NOTE_POWER_LOW), any());
    }

    @Test
    public void testShowLowBatteryNotification_BatteryChannel() {
        mPowerNotificationWarnings.showLowBatteryWarning(true);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mMockNotificationManager)
                .notifyAsUser(anyString(), eq(SystemMessage.NOTE_POWER_LOW),
                        captor.capture(), any());
        assertTrue(captor.getValue().getChannelId() == NotificationChannels.BATTERY);
    }

    @Test
    public void testShowHighTemperatureWarning_NotifyAsUser() {
        mPowerNotificationWarnings.showHighTemperatureWarning();
        verify(mMockNotificationManager, times(1))
                .notifyAsUser(anyString(), eq(SystemMessage.NOTE_HIGH_TEMP), any(), any());
    }

    @Test
    public void testDismissHighTemperatureWarning_CancelAsUser() {
        mPowerNotificationWarnings.showHighTemperatureWarning();
        mPowerNotificationWarnings.dismissHighTemperatureWarning();
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(),
                eq(SystemMessage.NOTE_HIGH_TEMP), any());
    }

    @Test
    public void testShowThermalShutdownWarning_NotifyAsUser() {
        mPowerNotificationWarnings.showThermalShutdownWarning();
        verify(mMockNotificationManager, times(1))
                .notifyAsUser(anyString(), eq(SystemMessage.NOTE_THERMAL_SHUTDOWN), any(), any());
    }

    @Test
    public void testDismissThermalShutdownWarning_CancelAsUser() {
        mPowerNotificationWarnings.showThermalShutdownWarning();
        mPowerNotificationWarnings.dismissThermalShutdownWarning();
        verify(mMockNotificationManager, times(1)).cancelAsUser(anyString(),
                eq(SystemMessage.NOTE_THERMAL_SHUTDOWN), any());
    }

    @Test
    public void testSetUndismissibleDialogShowing_Overheat_ShouldShowing() {
        boolean overheat = true;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(1)).setUndismissibleDialogShowing(overheat);
    }

    @Test
    public void testSetDismissibleDialogShowing_Overheat_ShouldNotShowing() {
        boolean overheat = true;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(1)).setDismissibleDialogShowing(!overheat);
    }

    @Test
    public void testSetAlarmShouldSound_Overheat_ShouldSound() {
        boolean overheat = true;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(1)).setAlarmShouldSound(overheat);
    }

    @Test
    public void testSetUndismissibleDialogShowing_NotOverheat_ShouldNotShowing() {
        boolean overheat = false;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, never()).setUndismissibleDialogShowing(
                overheat);
    }

    @Test
    public void testSetDismissibleDialogShowing_NotOverheat_ShouldShowing() {
        boolean overheat = false;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, never()).setDismissibleDialogShowing(!overheat);
    }

    @Test
    public void testSetAlarmShouldSound_NotOverheat_ShouldNotSound() {
        boolean overheat = false;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, never()).setAlarmShouldSound(overheat);
    }

    @Test
    public void testSetAlarmShouldSound_Overheat_CoolDown_ShouldNotSound() {
        boolean overheat = true;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        // First time overheat, show UndismissibleDialog and alarm beep sound
        verify(mSpyPowerNotificationWarnings, times(1)).setUndismissibleDialogShowing(overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setDismissibleDialogShowing(!overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setAlarmShouldSound(overheat);

        // After disconnected cable and cooler temperature
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(!overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(1)).setUndismissibleDialogShowing(!overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setDismissibleDialogShowing(overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setAlarmShouldSound(!overheat);
    }

    @Test
    public void testSetAlarmShouldSound_Overheat_Twice_ShouldShowUndismissibleDialog() {
        boolean overheat = true;
        // First time overheat, show mAlarmDialog and alarm beep sound
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(1)).setUndismissibleDialogShowing(overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setDismissibleDialogShowing(!overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setAlarmShouldSound(overheat);

        // After disconnected cable, cooler temperature, switch to mAlarmDialogAllowDismiss
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(!overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(1)).setUndismissibleDialogShowing(!overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setDismissibleDialogShowing(overheat);
        verify(mSpyPowerNotificationWarnings, times(1)).setAlarmShouldSound(!overheat);

        // Overheat again, switch to mAlarmDialog again, 3 functions should go through twice,
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.notifyHighTemperatureAlarm(overheat));
        waitForIdleSync();
        verify(mSpyPowerNotificationWarnings, times(2)).setUndismissibleDialogShowing(overheat);
        verify(mSpyPowerNotificationWarnings, times(2)).setDismissibleDialogShowing(!overheat);
        verify(mSpyPowerNotificationWarnings, times(2)).setAlarmShouldSound(overheat);
    }

    @Test
    public void testUndismissibleDialogShowing_ShouldShow() {
        boolean showing = true;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.setUndismissibleDialogShowing(showing));
        waitForIdleSync();
        assertThat(mSpyPowerNotificationWarnings.mAlarmDialog).isNotNull();
    }

    @Test
    public void testUndismissibleDialogShowing_ShouldNotShow() {
        boolean showing = false;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.setUndismissibleDialogShowing(showing));
        waitForIdleSync();
        assertThat(mSpyPowerNotificationWarnings.mAlarmDialog).isNull();
    }

    @Test
    public void testDismissibleDialogShowing_ShouldShow() {
        boolean showing = true;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.setDismissibleDialogShowing(showing));
        waitForIdleSync();
        assertThat(mSpyPowerNotificationWarnings.mAlarmDialogAllowDismiss).isNotNull();
    }

    @Test
    public void testDismissibleDialogShowing_ShouldNotShow() {
        boolean showing = false;
        mContext.getMainThreadHandler().post(
                () -> mSpyPowerNotificationWarnings.setDismissibleDialogShowing(showing));
        waitForIdleSync();
        assertThat(mSpyPowerNotificationWarnings.mAlarmDialogAllowDismiss).isNull();
    }
}
