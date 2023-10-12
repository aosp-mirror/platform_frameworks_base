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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.settings.GlobalSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PowerNotificationWarningsTest extends SysuiTestCase {

    public static final String FORMATTED_45M = "0h 45m";
    public static final String FORMATTED_HOUR = "1h 0m";
    private final NotificationManager mMockNotificationManager = mock(NotificationManager.class);
    private final GlobalSettings mGlobalSettings = new FakeGlobalSettings();
    private PowerNotificationWarnings mPowerNotificationWarnings;

    @Mock
    private BatteryController mBatteryController;
    @Mock
    private DialogLaunchAnimator mDialogLaunchAnimator;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private View mView;

    private BroadcastReceiver mReceiver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context wrapper = new ContextWrapper(mContext) {
            @Override
            public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                    IntentFilter filter, String broadcastPermission, Handler scheduler, int flags) {
                mReceiver = receiver;
                return null;
            }
        };

        // Test Instance.
        mContext.addMockSystemService(NotificationManager.class, mMockNotificationManager);
        ActivityStarter starter = mDependency.injectMockDependency(ActivityStarter.class);
        BroadcastSender broadcastSender = mDependency.injectMockDependency(BroadcastSender.class);
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());
        when(mUserTracker.getUserHandle()).thenReturn(
                UserHandle.of(ActivityManager.getCurrentUser()));
        mPowerNotificationWarnings = new PowerNotificationWarnings(wrapper, starter,
                broadcastSender, () -> mBatteryController, mDialogLaunchAnimator, mUiEventLogger,
                mGlobalSettings, mUserTracker);
        BatteryStateSnapshot snapshot = new BatteryStateSnapshot(100, false, false, 1,
                BatteryManager.BATTERY_HEALTH_GOOD, 5, 15);
        mPowerNotificationWarnings.updateSnapshot(snapshot);
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
    public void testShowUsbHighTemperatureAlarm() {
        mPowerNotificationWarnings.showUsbHighTemperatureAlarm();
        waitForIdleSync(mContext.getMainThreadHandler());
        assertThat(mPowerNotificationWarnings.mUsbHighTempDialog).isNotNull();

        mPowerNotificationWarnings.mUsbHighTempDialog.dismiss();
    }

    @Test
    public void testDialogStartedFromLauncher_viewVisible() {
        when(mBatteryController.getLastPowerSaverStartView())
                .thenReturn(new WeakReference<>(mView));
        when(mView.isAggregatedVisible()).thenReturn(true);

        Intent intent = new Intent(BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION);
        intent.putExtras(new Bundle());

        mReceiver.onReceive(mContext, intent);

        verify(mDialogLaunchAnimator).showFromView(any(), eq(mView), any());

        mPowerNotificationWarnings.getSaverConfirmationDialog().dismiss();
    }

    @Test
    public void testDialogStartedNotFromLauncher_viewNotVisible() {
        when(mBatteryController.getLastPowerSaverStartView())
                .thenReturn(new WeakReference<>(mView));
        when(mView.isAggregatedVisible()).thenReturn(false);

        Intent intent = new Intent(BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION);
        intent.putExtras(new Bundle());

        mReceiver.onReceive(mContext, intent);

        verify(mDialogLaunchAnimator, never()).showFromView(any(), any());

        assertThat(mPowerNotificationWarnings.getSaverConfirmationDialog().isShowing()).isTrue();
        mPowerNotificationWarnings.getSaverConfirmationDialog().dismiss();
    }

    @Test
    public void testDialogShownNotFromLauncher() {
        when(mBatteryController.getLastPowerSaverStartView()).thenReturn(null);

        Intent intent = new Intent(BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION);
        intent.putExtras(new Bundle());

        mReceiver.onReceive(mContext, intent);

        verify(mDialogLaunchAnimator, never()).showFromView(any(), any());

        assertThat(mPowerNotificationWarnings.getSaverConfirmationDialog().isShowing()).isTrue();
        mPowerNotificationWarnings.getSaverConfirmationDialog().dismiss();
    }
}
