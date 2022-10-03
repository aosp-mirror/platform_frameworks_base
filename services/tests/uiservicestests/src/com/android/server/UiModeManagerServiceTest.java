/*
 * Copyright (C) 20019 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.MODIFY_DAY_NIGHT_MODE;
import static android.app.UiModeManager.MODE_NIGHT_AUTO;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_BEDTIME;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN;
import static android.app.UiModeManager.MODE_NIGHT_NO;
import static android.app.UiModeManager.MODE_NIGHT_YES;
import static android.app.UiModeManager.PROJECTION_TYPE_ALL;
import static android.app.UiModeManager.PROJECTION_TYPE_AUTOMOTIVE;
import static android.app.UiModeManager.PROJECTION_TYPE_NONE;

import static com.android.server.UiModeManagerService.SUPPORTED_NIGHT_MODE_CUSTOM_TYPES;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.IOnProjectionStateChangedListener;
import android.app.IUiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.test.mock.MockContentResolver;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class UiModeManagerServiceTest extends UiServiceTestCase {
    private static final String PACKAGE_NAME = "Diane Coffee";
    private UiModeManagerService mUiManagerService;
    private IUiModeManager mService;
    private MockContentResolver mContentResolver;
    @Mock
    private WindowManagerInternal mWindowManager;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private TwilightManager mTwilightManager;
    @Mock
    private PowerManager.WakeLock mWakeLock;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private TwilightState mTwilightState;
    @Mock
    PowerManagerInternal mLocalPowerManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IBinder mBinder;
    @Mock
    private DreamManagerInternal mDreamManager;
    @Captor
    private ArgumentCaptor<Intent> mOrderedBroadcastIntent;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mOrderedBroadcastReceiver;

    private BroadcastReceiver mScreenOffCallback;
    private BroadcastReceiver mTimeChangedCallback;
    private BroadcastReceiver mDockStateChangedCallback;
    private AlarmManager.OnAlarmListener mCustomListener;
    private Consumer<PowerSaveState> mPowerSaveConsumer;
    private TwilightListener mTwilightListener;

    @Before
    public void setUp() {
        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        doAnswer(inv -> {
            mTwilightListener = (TwilightListener) inv.getArgument(0);
            return null;
        }).when(mTwilightManager).registerListener(any(), any());
        doAnswer(inv -> {
            mPowerSaveConsumer = (Consumer<PowerSaveState>) inv.getArgument(1);
            return null;
        }).when(mLocalPowerManager).registerLowPowerModeObserver(anyInt(), any());
        when(mLocalPowerManager.getLowPowerState(anyInt()))
                .thenReturn(new PowerSaveState.Builder().setBatterySaverEnabled(false).build());
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(com.android.internal.R.string.config_somnambulatorComponent))
                .thenReturn("somnambulator");
        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.newWakeLock(anyInt(), anyString())).thenReturn(mWakeLock);
        when(mTwilightManager.getLastTwilightState()).thenReturn(mTwilightState);
        when(mTwilightState.isNight()).thenReturn(true);
        when(mContext.registerReceiver(notNull(), notNull())).then(inv -> {
            IntentFilter filter = inv.getArgument(1);
            if (filter.hasAction(Intent.ACTION_TIMEZONE_CHANGED)) {
                mTimeChangedCallback = inv.getArgument(0);
            }
            if (filter.hasAction(Intent.ACTION_SCREEN_OFF)) {
                mScreenOffCallback = inv.getArgument(0);
            }
            if (filter.hasAction(Intent.ACTION_DOCK_EVENT)) {
                mDockStateChangedCallback = inv.getArgument(0);
            }
            return null;
        });
        doAnswer(inv -> {
            mCustomListener = inv.getArgument(3);
            return null;
        }).when(mAlarmManager).setExact(anyInt(), anyLong(), anyString(),
                any(AlarmManager.OnAlarmListener.class), any(Handler.class));

        doAnswer(inv -> {
            mCustomListener = () -> {};
            return null;
        }).when(mAlarmManager).cancel(eq(mCustomListener));
        when(mContext.getSystemService(eq(Context.POWER_SERVICE)))
                .thenReturn(mPowerManager);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        when(mContext.getSystemService(eq(Context.ALARM_SERVICE)))
                .thenReturn(mAlarmManager);
        addLocalService(WindowManagerInternal.class, mWindowManager);
        addLocalService(PowerManagerInternal.class, mLocalPowerManager);
        addLocalService(TwilightManager.class, mTwilightManager);
        addLocalService(DreamManagerInternal.class, mDreamManager);
        
        mUiManagerService = new UiModeManagerService(mContext, /* setupWizardComplete= */ true,
                mTwilightManager, new TestInjector());
        try {
            mUiManagerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        } catch (SecurityException e) {/* ignore for permission denial */}
        mService = mUiManagerService.getService();
    }

    private <T> void addLocalService(Class<T> clazz, T service) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, service);
    }

    @Ignore // b/152719290 - Fails on stage-aosp-master
    @Test
    public void setNightModeActivated_overridesFunctionCorrectly() throws RemoteException {
        // set up
        when(mPowerManager.isInteractive()).thenReturn(false);
        mService.setNightMode(MODE_NIGHT_NO);
        assertFalse(mUiManagerService.getConfiguration().isNightModeActive());

        // assume it is day time
        doReturn(false).when(mTwilightState).isNight();

        // set mode to auto
        mService.setNightMode(MODE_NIGHT_AUTO);

        // set night mode on overriding current config
        mService.setNightModeActivated(true);

        assertTrue(mUiManagerService.getConfiguration().isNightModeActive());

        // now it is night time
        doReturn(true).when(mTwilightState).isNight();
        mTwilightListener.onTwilightStateChanged(mTwilightState);

        assertTrue(mUiManagerService.getConfiguration().isNightModeActive());

        // now it is next day mid day
        doReturn(false).when(mTwilightState).isNight();
        mTwilightListener.onTwilightStateChanged(mTwilightState);

        assertFalse(mUiManagerService.getConfiguration().isNightModeActive());
    }

    @Test
    public void setNightModeActivated_true_withCustomModeBedtime_shouldOverrideNightModeCorrectly()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        assertFalse(mUiManagerService.getConfiguration().isNightModeActive());

        mService.setNightModeActivated(true);

        assertThat(mUiManagerService.getConfiguration().isNightModeActive()).isTrue();
    }

    @Test
    public void setNightModeActivated_false_withCustomModeBedtime_shouldOverrideNightModeCorrectly()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        assertFalse(mUiManagerService.getConfiguration().isNightModeActive());

        mService.setNightModeActivated(true);
        mService.setNightModeActivated(false);

        assertThat(mUiManagerService.getConfiguration().isNightModeActive()).isFalse();
    }

    @Test
    public void setAutoMode_screenOffRegistered() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        mService.setNightMode(MODE_NIGHT_AUTO);
        verify(mContext, atLeastOnce()).registerReceiver(any(BroadcastReceiver.class), any());
    }

    @Ignore // b/152719290 - Fails on stage-aosp-master
    @Test
    public void setAutoMode_screenOffUnRegistered() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_AUTO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /*we should ignore this update config exception*/ }
        given(mContext.registerReceiver(any(), any())).willThrow(SecurityException.class);
        verify(mContext, atLeastOnce()).unregisterReceiver(any(BroadcastReceiver.class));
    }

    @Test
    public void setNightModeCustomType_bedtime_shouldNotActivateNightMode() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void setNightModeCustomType_noPermission_shouldThrow() throws RemoteException {
        when(mContext.checkCallingOrSelfPermission(eq(MODIFY_DAY_NIGHT_MODE)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME));
    }

    @Test
    public void setNightModeCustomType_customTypeUnknown_shouldThrow() throws RemoteException {
        assertThrows(IllegalArgumentException.class,
                () -> mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_UNKNOWN));
    }

    @Test
    public void setNightModeCustomType_customTypeUnsupported_shouldThrow() throws RemoteException {
        assertThrows(IllegalArgumentException.class,
                () -> {
                    int maxSupportedCustomType = 0;
                    for (Integer supportedType : SUPPORTED_NIGHT_MODE_CUSTOM_TYPES) {
                        maxSupportedCustomType = Math.max(maxSupportedCustomType, supportedType);
                    }
                    mService.setNightModeCustomType(maxSupportedCustomType + 1);
                });
    }

    @Test
    public void setNightModeCustomType_bedtime_shouldHaveNoScreenOffRegistered()
            throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        ArgumentCaptor<IntentFilter> intentFiltersCaptor = ArgumentCaptor.forClass(
                IntentFilter.class);
        verify(mContext, atLeastOnce()).registerReceiver(any(BroadcastReceiver.class),
                intentFiltersCaptor.capture());

        List<IntentFilter> intentFilters = intentFiltersCaptor.getAllValues();
        for (IntentFilter intentFilter : intentFilters) {
            assertThat(intentFilter.hasAction(Intent.ACTION_SCREEN_OFF)).isFalse();
        }
    }

    @Test
    public void setNightModeActivated_fromNoToYesAndBack() throws RemoteException {
        mService.setNightMode(MODE_NIGHT_NO);
        mService.setNightModeActivated(true);
        assertTrue(isNightModeActivated());
        mService.setNightModeActivated(false);
        assertFalse(isNightModeActivated());
    }

    @Test
    public void setNightModeActivated_permissionToChangeOtherUsers() throws RemoteException {
        SystemService.TargetUser user = mock(SystemService.TargetUser.class);
        doReturn(9).when(user).getUserIdentifier();
        mUiManagerService.onUserSwitching(user, user);
        when(mContext.checkCallingOrSelfPermission(
                eq(Manifest.permission.INTERACT_ACROSS_USERS)))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertFalse(mService.setNightModeActivated(true));
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeBedtime_withParamOnAndBedtime_shouldActivate()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeBedtime_withParamOffAndBedtime_shouldDeactivate()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, false /* active */);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeBedtime_withParamOnAndSchedule_shouldNotActivate()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_SCHEDULE, true /* active */);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeSchedule_withParamOnAndBedtime_shouldNotActivate()
            throws RemoteException {
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeSchedule_withParamOnAndBedtime_thenCustomTypeBedtime_shouldActivate()
            throws RemoteException {
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeBedtime_withParamOnAndBedtime_thenCustomTypeSchedule_shouldKeepNightModeActivate()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mService.setNightMode(MODE_NIGHT_CUSTOM);
        LocalTime now = LocalTime.now();
        mService.setCustomNightModeStart(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(2L).toNanoOfDay() / 1000);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void setNightModeActivatedForCustomMode_customTypeBedtime_withParamOnAndBedtime_thenCustomTypeScheduleAndScreenOff_shouldDeactivateNightMode()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mService.setNightMode(MODE_NIGHT_CUSTOM);
        LocalTime now = LocalTime.now();
        mService.setCustomNightModeStart(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(2L).toNanoOfDay() / 1000);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void autoNightModeSwitch_batterySaverOn() throws RemoteException {
        mService.setNightMode(MODE_NIGHT_NO);
        when(mTwilightState.isNight()).thenReturn(false);
        mService.setNightMode(MODE_NIGHT_AUTO);

        // night NO
        assertFalse(isNightModeActivated());

        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(true).build());

        // night YES
        assertTrue(isNightModeActivated());
    }

    @Test
    public void nightModeCustomBedtime_batterySaverOn_notInBedtime_shouldActivateNightMode()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);

        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(true).build());

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeCustomBedtime_batterySaverOn_afterBedtime_shouldKeepNightModeActivated()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(true).build());

        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, false /* active */);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeBedtime_duringBedtime_batterySaverOnThenOff_shouldKeepNightModeActivated()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(true).build());
        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(false).build());

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeCustomBedtime_duringBedtime_batterySaverOnThenOff_finallyAfterBedtime_shouldDeactivateNightMode()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);
        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(true).build());
        mPowerSaveConsumer.accept(
                new PowerSaveState.Builder().setBatterySaverEnabled(false).build());

        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, false /* active */);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void nightModeCustomBedtime_duringBedtime_changeModeToNo_shouldDeactivateNightMode()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mService.setNightMode(MODE_NIGHT_NO);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void nightModeCustomBedtime_duringBedtime_changeModeToNoAndThenExitBedtime_shouldKeepNightModeDeactivated()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);
        mService.setNightMode(MODE_NIGHT_NO);

        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, false /* active */);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void nightModeCustomBedtime_duringBedtime_changeModeToYes_shouldKeepNightModeActivated()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mService.setNightMode(MODE_NIGHT_YES);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeCustomBedtime_duringBedtime_changeModeToYesAndThenExitBedtime_shouldKeepNightModeActivated()
            throws RemoteException {
        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        mService.setNightMode(MODE_NIGHT_YES);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, false /* active */);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeNo_duringBedtime_shouldKeepNightModeDeactivated()
            throws RemoteException {
        mService.setNightMode(MODE_NIGHT_NO);

        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void nightModeNo_thenChangeToCustomTypeBedtimeAndActivate_shouldActivateNightMode()
            throws RemoteException {
        mService.setNightMode(MODE_NIGHT_NO);

        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeYes_thenChangeToCustomTypeBedtime_shouldDeactivateNightMode()
            throws RemoteException {
        mService.setNightMode(MODE_NIGHT_YES);

        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void nightModeYes_thenChangeToCustomTypeBedtimeAndActivate_shouldActivateNightMode()
            throws RemoteException {
        mService.setNightMode(MODE_NIGHT_YES);

        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void nightModeAuto_thenChangeToCustomTypeBedtime_notInBedtime_shouldDeactivateNightMode()
            throws RemoteException {
        // set mode to auto
        mService.setNightMode(MODE_NIGHT_AUTO);
        mService.setNightModeActivated(true);
        // now it is night time
        doReturn(true).when(mTwilightState).isNight();
        mTwilightListener.onTwilightStateChanged(mTwilightState);

        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);

        assertThat(isNightModeActivated()).isFalse();
    }

    @Test
    public void nightModeAuto_thenChangeToCustomTypeBedtime_duringBedtime_shouldActivateNightMode()
            throws RemoteException {
        // set mode to auto
        mService.setNightMode(MODE_NIGHT_AUTO);
        mService.setNightModeActivated(true);
        // now it is night time
        doReturn(true).when(mTwilightState).isNight();
        mTwilightListener.onTwilightStateChanged(mTwilightState);

        mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        mService.setNightModeActivatedForCustomMode(
                MODE_NIGHT_CUSTOM_TYPE_BEDTIME, true /* active */);

        assertThat(isNightModeActivated()).isTrue();
    }

    @Test
    public void setAutoMode_clearCache() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_AUTO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        verify(mWindowManager).clearSnapshotCache();
    }

    @Test
    public void setNightModeActive_fromNightModeYesToNoWhenFalse() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_YES);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightModeActivated(false);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertEquals(MODE_NIGHT_NO, mService.getNightMode());
    }

    @Test
    public void setNightModeActive_fromNightModeNoToYesWhenTrue() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightModeActivated(true);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertEquals(MODE_NIGHT_YES, mService.getNightMode());
    }

    @Test
    public void setNightModeActive_autoNightModeNoChanges() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_AUTO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        try {
            mService.setNightModeActivated(true);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertEquals(MODE_NIGHT_AUTO, mService.getNightMode());
    }

    @Test
    public void getNightModeCustomType_nightModeNo_shouldReturnUnknown() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }

        assertThat(mService.getNightModeCustomType()).isEqualTo(MODE_NIGHT_CUSTOM_TYPE_UNKNOWN);
    }

    @Test
    public void getNightModeCustomType_nightModeYes_shouldReturnUnknown() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_YES);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }

        assertThat(mService.getNightModeCustomType()).isEqualTo(MODE_NIGHT_CUSTOM_TYPE_UNKNOWN);
    }

    @Test
    public void getNightModeCustomType_nightModeAuto_shouldReturnUnknown() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_AUTO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }

        assertThat(mService.getNightModeCustomType()).isEqualTo(MODE_NIGHT_CUSTOM_TYPE_UNKNOWN);
    }

    @Test
    public void getNightModeCustomType_nightModeCustom_shouldReturnSchedule()
            throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_CUSTOM);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }

        assertThat(mService.getNightModeCustomType()).isEqualTo(MODE_NIGHT_CUSTOM_TYPE_SCHEDULE);
    }

    @Test
    public void getNightModeCustomType_nightModeCustomBedtime_shouldReturnBedtime()
            throws RemoteException {
        try {
            mService.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }

        assertThat(mService.getNightModeCustomType()).isEqualTo(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
    }

    @Test
    public void getNightModeCustomType_permissionNotGranted_shouldThrow()
            throws RemoteException {
        when(mContext.checkCallingOrSelfPermission(eq(MODIFY_DAY_NIGHT_MODE)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class, () -> mService.getNightModeCustomType());
    }

    @Test
    public void isNightModeActive_nightModeYes() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_YES);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertTrue(isNightModeActivated());
    }

    @Test
    public void isNightModeActive_nightModeNo() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        assertFalse(isNightModeActivated());
    }

    @Test
    public void customTime_darkThemeOn() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_NO);
        mService.setCustomNightModeStart(now.minusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertTrue(isNightModeActivated());
    }

    @Test
    public void customTime_darkThemeOff() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_YES);
        mService.setCustomNightModeStart(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.minusHours(1L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertFalse(isNightModeActivated());
    }

    @Test
    public void customTime_darkThemeOff_afterStartEnd() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_YES);
        mService.setCustomNightModeStart(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(2L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertFalse(isNightModeActivated());
    }

    @Test
    public void customTime_darkThemeOn_afterStartEnd() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_YES);
        mService.setCustomNightModeStart(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(2L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertFalse(isNightModeActivated());
    }


    @Test
    public void customTime_darkThemeOn_beforeStartEnd() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_YES);
        mService.setCustomNightModeStart(now.minusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.minusHours(2L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertTrue(isNightModeActivated());
    }

    @Test
    public void customTime_darkThemeOff_beforeStartEnd() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_YES);
        mService.setCustomNightModeStart(now.minusHours(2L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.minusHours(1L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertFalse(isNightModeActivated());
    }

    @Test
    public void customTIme_customAlarmSetWhenScreenTimeChanges() throws RemoteException {
        when(mPowerManager.isInteractive()).thenReturn(false);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        verify(mAlarmManager, times(1))
                .setExact(anyInt(), anyLong(), anyString(), any(), any());
        mTimeChangedCallback.onReceive(mContext, new Intent(Intent.ACTION_TIME_CHANGED));
        verify(mAlarmManager, atLeast(2))
                .setExact(anyInt(), anyLong(), anyString(), any(), any());
    }

    @Test
    public void customTime_alarmSetInTheFutureWhenOn() throws RemoteException {
        LocalDateTime now = LocalDateTime.now();
        when(mPowerManager.isInteractive()).thenReturn(false);
        mService.setNightMode(MODE_NIGHT_YES);
        mService.setCustomNightModeStart(now.toLocalTime().minusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.toLocalTime().plusHours(1L).toNanoOfDay() / 1000);
        LocalDateTime next = now.plusHours(1L);
        final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        verify(mAlarmManager)
                .setExact(anyInt(), eq(millis), anyString(), any(), any());
    }

    @Test
    public void customTime_appliesImmediatelyWhenScreenOff() throws RemoteException {
        when(mPowerManager.isInteractive()).thenReturn(false);
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_NO);
        mService.setCustomNightModeStart(now.minusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        assertTrue(isNightModeActivated());
    }

    @Test
    public void customTime_appliesOnlyWhenScreenOff() throws RemoteException {
        LocalTime now = LocalTime.now();
        mService.setNightMode(MODE_NIGHT_NO);
        mService.setCustomNightModeStart(now.minusHours(1L).toNanoOfDay() / 1000);
        mService.setCustomNightModeEnd(now.plusHours(1L).toNanoOfDay() / 1000);
        mService.setNightMode(MODE_NIGHT_CUSTOM);
        assertFalse(isNightModeActivated());
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertTrue(isNightModeActivated());
    }

    @Test
    public void nightAuto_appliesOnlyWhenScreenOff() throws RemoteException {
        when(mTwilightState.isNight()).thenReturn(true);
        mService.setNightMode(MODE_NIGHT_NO);
        mService.setNightMode(MODE_NIGHT_AUTO);
        assertFalse(isNightModeActivated());
        mScreenOffCallback.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        assertTrue(isNightModeActivated());
    }

    private boolean isNightModeActivated() {
        return (mUiManagerService.getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_YES) != 0;
    }

    @Test
    public void requestProjection_failsForBogusPackageName() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID + 1);

        assertThrows(SecurityException.class, () -> mService.requestProjection(mBinder,
                PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void requestProjection_failsIfNameNotFound() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());

        assertThrows(SecurityException.class, () -> mService.requestProjection(mBinder,
                PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void requestProjection_failsIfNoProjectionTypes() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);

        assertThrows(IllegalArgumentException.class,
                () -> mService.requestProjection(mBinder, PROJECTION_TYPE_NONE, PACKAGE_NAME));
        verify(mContext, never()).enforceCallingPermission(
                eq(Manifest.permission.TOGGLE_AUTOMOTIVE_PROJECTION), any());
        verifyZeroInteractions(mBinder);
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void requestProjection_failsIfMultipleProjectionTypes() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);

        // Don't use PROJECTION_TYPE_ALL because that's actually == -1 and will fail the > 0 check.
        int multipleProjectionTypes = PROJECTION_TYPE_AUTOMOTIVE | 0x0002 | 0x0004;

        assertThrows(IllegalArgumentException.class,
                () -> mService.requestProjection(mBinder, multipleProjectionTypes, PACKAGE_NAME));
        verify(mContext, never()).enforceCallingPermission(
                eq(Manifest.permission.TOGGLE_AUTOMOTIVE_PROJECTION), any());
        verifyZeroInteractions(mBinder);
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void requestProjection_enforcesToggleAutomotiveProjectionPermission() throws Exception {
        doThrow(new SecurityException())
                .when(mPackageManager).getPackageUidAsUser(eq(PACKAGE_NAME), anyInt());

        assertThrows(SecurityException.class, () -> mService.requestProjection(mBinder,
                PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void requestProjection_automotive_failsIfAlreadySetByOtherPackage() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());

        String otherPackage = "Raconteurs";
        when(mPackageManager.getPackageUidAsUser(eq(otherPackage), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        assertFalse(mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, otherPackage));
        assertThat(mService.getProjectingPackages(PROJECTION_TYPE_AUTOMOTIVE),
                contains(PACKAGE_NAME));
    }

    @Test
    public void requestProjection_failsIfCannotLinkToDeath() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        doThrow(new RemoteException()).when(mBinder).linkToDeath(any(), anyInt());

        assertFalse(mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void requestProjection() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        // Should work for all powers of two.
        for (int i = 0; i < Integer.SIZE; ++i) {
            int projectionType = 1 << i;
            assertTrue(mService.requestProjection(mBinder, projectionType, PACKAGE_NAME));
            assertTrue((mService.getActiveProjectionTypes() & projectionType) != 0);
            assertThat(mService.getProjectingPackages(projectionType), contains(PACKAGE_NAME));
            // Subsequent calls should still succeed.
            assertTrue(mService.requestProjection(mBinder, projectionType, PACKAGE_NAME));
        }
        assertEquals(PROJECTION_TYPE_ALL, mService.getActiveProjectionTypes());
    }

    @Test
    public void releaseProjection_failsForBogusPackageName() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());

        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID + 1);

        assertThrows(SecurityException.class, () -> mService.releaseProjection(
                PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());
    }

    @Test
    public void releaseProjection_failsIfNameNotFound() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());

        assertThrows(SecurityException.class, () -> mService.releaseProjection(
                PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());
    }

    @Test
    public void releaseProjection_enforcesToggleAutomotiveProjectionPermission() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());
        doThrow(new SecurityException()).when(mContext).enforceCallingPermission(
                eq(Manifest.permission.TOGGLE_AUTOMOTIVE_PROJECTION), any());

        // Should not be enforced for other types of projection.
        int nonAutomotiveProjectionType = PROJECTION_TYPE_AUTOMOTIVE * 2;
        mService.releaseProjection(nonAutomotiveProjectionType, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());

        assertThrows(SecurityException.class, () -> mService.requestProjection(mBinder,
                PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());
    }

    @Test
    public void releaseProjection() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        requestAllPossibleProjectionTypes();
        assertEquals(PROJECTION_TYPE_ALL, mService.getActiveProjectionTypes());

        assertTrue(mService.releaseProjection(PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME));
        int everythingButAutomotive = PROJECTION_TYPE_ALL & ~PROJECTION_TYPE_AUTOMOTIVE;
        assertEquals(everythingButAutomotive, mService.getActiveProjectionTypes());

        for (int i = 0; i < Integer.SIZE; ++i) {
            int projectionType = 1 << i;
            assertEquals(projectionType != PROJECTION_TYPE_AUTOMOTIVE,
                    (boolean) mService.releaseProjection(projectionType, PACKAGE_NAME));
        }

        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void binderDeath_releasesProjection() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        requestAllPossibleProjectionTypes();
        assertEquals(PROJECTION_TYPE_ALL, mService.getActiveProjectionTypes());
        ArgumentCaptor<IBinder.DeathRecipient> deathRecipientCaptor = ArgumentCaptor.forClass(
                IBinder.DeathRecipient.class);
        verify(mBinder, atLeastOnce()).linkToDeath(deathRecipientCaptor.capture(), anyInt());

        // Wipe them out. All of them.
        deathRecipientCaptor.getAllValues().forEach(IBinder.DeathRecipient::binderDied);
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void getActiveProjectionTypes() throws Exception {
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());
        mService.releaseProjection(PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_NONE, mService.getActiveProjectionTypes());
    }

    @Test
    public void getProjectingPackages() throws Exception {
        assertTrue(mService.getProjectingPackages(PROJECTION_TYPE_ALL).isEmpty());
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(1, mService.getProjectingPackages(PROJECTION_TYPE_AUTOMOTIVE).size());
        assertEquals(1, mService.getProjectingPackages(PROJECTION_TYPE_ALL).size());
        assertThat(mService.getProjectingPackages(PROJECTION_TYPE_AUTOMOTIVE),
                contains(PACKAGE_NAME));
        assertThat(mService.getProjectingPackages(PROJECTION_TYPE_ALL), contains(PACKAGE_NAME));
        mService.releaseProjection(PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertThat(mService.getProjectingPackages(PROJECTION_TYPE_ALL), empty());
    }

    @Test
    public void addOnProjectionStateChangedListener_enforcesReadProjStatePermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.READ_PROJECTION_STATE), any());
        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);

        assertThrows(SecurityException.class, () -> mService.addOnProjectionStateChangedListener(
                listener, PROJECTION_TYPE_ALL));
    }

    @Test
    public void addOnProjectionStateChangedListener_callsListenerIfProjectionActive()
            throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        assertEquals(PROJECTION_TYPE_AUTOMOTIVE, mService.getActiveProjectionTypes());

        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);
        when(listener.asBinder()).thenReturn(mBinder);  // Any binder will do
        mService.addOnProjectionStateChangedListener(listener, PROJECTION_TYPE_ALL);
        verify(listener).onProjectionStateChanged(eq(PROJECTION_TYPE_AUTOMOTIVE),
                eq(List.of(PACKAGE_NAME)));
    }

    @Test
    public void removeOnProjectionStateChangedListener_enforcesReadProjStatePermission() {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.READ_PROJECTION_STATE), any());
        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);

        assertThrows(SecurityException.class, () -> mService.removeOnProjectionStateChangedListener(
                listener));
    }

    @Test
    public void removeOnProjectionStateChangedListener() throws Exception {
        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);
        when(listener.asBinder()).thenReturn(mBinder); // Any binder will do.
        mService.addOnProjectionStateChangedListener(listener, PROJECTION_TYPE_ALL);

        mService.removeOnProjectionStateChangedListener(listener);
        // Now set automotive projection, should not call back.
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        verify(listener, never()).onProjectionStateChanged(anyInt(), any());
    }

    @Test
    public void projectionStateChangedListener_calledWhenStateChanges() throws Exception {
        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);
        when(listener.asBinder()).thenReturn(mBinder); // Any binder will do.
        mService.addOnProjectionStateChangedListener(listener, PROJECTION_TYPE_ALL);
        verify(listener, atLeastOnce()).asBinder(); // Called twice during register.

        // No calls initially, no projection state set.
        verifyNoMoreInteractions(listener);

        // Now set automotive projection, should call back.
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        verify(listener).onProjectionStateChanged(eq(PROJECTION_TYPE_AUTOMOTIVE),
                eq(List.of(PACKAGE_NAME)));

        // Subsequent calls that are noops do nothing.
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        int unsetProjectionType = 0x0002;
        mService.releaseProjection(unsetProjectionType, PACKAGE_NAME);
        verifyNoMoreInteractions(listener);

        // Release should call back though.
        mService.releaseProjection(PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        verify(listener).onProjectionStateChanged(eq(PROJECTION_TYPE_NONE),
                eq(List.of()));

        // But only the first time.
        mService.releaseProjection(PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void projectionStateChangedListener_calledForAnyRelevantStateChange() throws Exception {
        int fakeProjectionType = 0x0002;
        int otherFakeProjectionType = 0x0004;
        String otherPackageName = "Internet Arms";
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        when(mPackageManager.getPackageUidAsUser(eq(otherPackageName), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);
        when(listener.asBinder()).thenReturn(mBinder); // Any binder will do.
        IOnProjectionStateChangedListener listener2 = mock(IOnProjectionStateChangedListener.class);
        when(listener2.asBinder()).thenReturn(mBinder); // Any binder will do.
        mService.addOnProjectionStateChangedListener(listener, fakeProjectionType);
        mService.addOnProjectionStateChangedListener(listener2,
                fakeProjectionType | otherFakeProjectionType);
        verify(listener, atLeastOnce()).asBinder(); // Called twice during register.
        verify(listener2, atLeastOnce()).asBinder(); // Called twice during register.

        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        verifyNoMoreInteractions(listener, listener2);

        // fakeProjectionType should trigger both.
        mService.requestProjection(mBinder, fakeProjectionType, PACKAGE_NAME);
        verify(listener).onProjectionStateChanged(eq(fakeProjectionType),
                eq(List.of(PACKAGE_NAME)));
        verify(listener2).onProjectionStateChanged(eq(fakeProjectionType),
                eq(List.of(PACKAGE_NAME)));

        // otherFakeProjectionType should only trigger the second listener.
        mService.requestProjection(mBinder, otherFakeProjectionType, otherPackageName);
        verifyNoMoreInteractions(listener);
        verify(listener2).onProjectionStateChanged(
                eq(fakeProjectionType | otherFakeProjectionType),
                eq(List.of(PACKAGE_NAME, otherPackageName)));

        // Turning off fakeProjectionType should trigger both again.
        mService.releaseProjection(fakeProjectionType, PACKAGE_NAME);
        verify(listener).onProjectionStateChanged(eq(PROJECTION_TYPE_NONE), eq(List.of()));
        verify(listener2).onProjectionStateChanged(eq(otherFakeProjectionType),
                eq(List.of(otherPackageName)));

        // Turning off otherFakeProjectionType should only trigger the second listener.
        mService.releaseProjection(otherFakeProjectionType, otherPackageName);
        verifyNoMoreInteractions(listener);
        verify(listener2).onProjectionStateChanged(eq(PROJECTION_TYPE_NONE), eq(List.of()));
    }

    @Test
    public void projectionStateChangedListener_unregisteredOnDeath() throws Exception {
        IOnProjectionStateChangedListener listener = mock(IOnProjectionStateChangedListener.class);
        IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        mService.addOnProjectionStateChangedListener(listener, PROJECTION_TYPE_ALL);
        ArgumentCaptor<IBinder.DeathRecipient> listenerDeathRecipient = ArgumentCaptor.forClass(
                IBinder.DeathRecipient.class);
        verify(listenerBinder).linkToDeath(listenerDeathRecipient.capture(), anyInt());

        // Now kill the binder for the listener. This should remove it from the list of listeners.
        listenerDeathRecipient.getValue().binderDied();
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.requestProjection(mBinder, PROJECTION_TYPE_AUTOMOTIVE, PACKAGE_NAME);
        verify(listener, never()).onProjectionStateChanged(anyInt(), any());
    }

    @Test
    public void enableCarMode_failsForBogusPackageName() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
            .thenReturn(TestInjector.DEFAULT_CALLING_UID + 1);

        assertThrows(SecurityException.class, () -> mService.enableCarMode(0, 0, PACKAGE_NAME));
        assertThat(mService.getCurrentModeType()).isNotEqualTo(Configuration.UI_MODE_TYPE_CAR);
    }

    @Test
    public void enableCarMode_shell() throws Exception {
        mUiManagerService = new UiModeManagerService(mContext, /* setupWizardComplete= */ true,
                mTwilightManager, new TestInjector(Process.SHELL_UID));
        try {
            mUiManagerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        } catch (SecurityException e) {/* ignore for permission denial */}
        mService = mUiManagerService.getService();

        mService.enableCarMode(0, 0, PACKAGE_NAME);
        assertThat(mService.getCurrentModeType()).isEqualTo(Configuration.UI_MODE_TYPE_CAR);
    }

    @Test
    public void disableCarMode_failsForBogusPackageName() throws Exception {
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
            .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.enableCarMode(0, 0, PACKAGE_NAME);
        assertThat(mService.getCurrentModeType()).isEqualTo(Configuration.UI_MODE_TYPE_CAR);
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
            .thenReturn(TestInjector.DEFAULT_CALLING_UID + 1);

        assertThrows(SecurityException.class,
            () -> mService.disableCarModeByCallingPackage(0, PACKAGE_NAME));
        assertThat(mService.getCurrentModeType()).isEqualTo(Configuration.UI_MODE_TYPE_CAR);

        // Clean up
        when(mPackageManager.getPackageUidAsUser(eq(PACKAGE_NAME), anyInt()))
            .thenReturn(TestInjector.DEFAULT_CALLING_UID);
        mService.disableCarModeByCallingPackage(0, PACKAGE_NAME);
        assertThat(mService.getCurrentModeType()).isNotEqualTo(Configuration.UI_MODE_TYPE_CAR);
    }

    @Test
    public void disableCarMode_shell() throws Exception {
        mUiManagerService = new UiModeManagerService(mContext, /* setupWizardComplete= */ true,
                mTwilightManager, new TestInjector(Process.SHELL_UID));
        try {
            mUiManagerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        } catch (SecurityException e) {/* ignore for permission denial */}
        mService = mUiManagerService.getService();

        mService.enableCarMode(0, 0, PACKAGE_NAME);
        assertThat(mService.getCurrentModeType()).isEqualTo(Configuration.UI_MODE_TYPE_CAR);

        mService.disableCarModeByCallingPackage(0, PACKAGE_NAME);
        assertThat(mService.getCurrentModeType()).isNotEqualTo(Configuration.UI_MODE_TYPE_CAR);
    }

    @Test
    public void dreamWhenDocked() {
        setScreensaverActivateOnDock(true);
        setScreensaverEnabled(true);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager).requestDream();
    }

    @Test
    public void noDreamWhenDocked_dreamsDisabled() {
        setScreensaverActivateOnDock(true);
        setScreensaverEnabled(false);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager, never()).requestDream();
    }

    @Test
    public void noDreamWhenDocked_dreamsWhenDockedDisabled() {
        setScreensaverActivateOnDock(false);
        setScreensaverEnabled(true);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager, never()).requestDream();
    }

    @Test
    public void noDreamWhenDocked_keyguardNotShowing_interactive() {
        setScreensaverActivateOnDock(true);
        setScreensaverEnabled(true);
        mUiManagerService.setStartDreamImmediatelyOnDock(false);
        when(mWindowManager.isKeyguardShowingAndNotOccluded()).thenReturn(false);
        when(mPowerManager.isInteractive()).thenReturn(true);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager, never()).requestDream();
    }

    @Test
    public void dreamWhenDocked_keyguardShowing_interactive() {
        setScreensaverActivateOnDock(true);
        setScreensaverEnabled(true);
        mUiManagerService.setStartDreamImmediatelyOnDock(false);
        when(mWindowManager.isKeyguardShowingAndNotOccluded()).thenReturn(true);
        when(mPowerManager.isInteractive()).thenReturn(false);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager).requestDream();
    }

    @Test
    public void dreamWhenDocked_keyguardNotShowing_notInteractive() {
        setScreensaverActivateOnDock(true);
        setScreensaverEnabled(true);
        mUiManagerService.setStartDreamImmediatelyOnDock(false);
        when(mWindowManager.isKeyguardShowingAndNotOccluded()).thenReturn(false);
        when(mPowerManager.isInteractive()).thenReturn(false);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager).requestDream();
    }

    @Test
    public void dreamWhenDocked_keyguardShowing_notInteractive() {
        setScreensaverActivateOnDock(true);
        setScreensaverEnabled(true);
        mUiManagerService.setStartDreamImmediatelyOnDock(false);
        when(mWindowManager.isKeyguardShowingAndNotOccluded()).thenReturn(true);
        when(mPowerManager.isInteractive()).thenReturn(false);

        triggerDockIntent();
        verifyAndSendResultBroadcast();
        verify(mDreamManager).requestDream();
    }

    private void triggerDockIntent() {
        final Intent dockedIntent =
                new Intent(Intent.ACTION_DOCK_EVENT)
                        .putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_DESK);
        mDockStateChangedCallback.onReceive(mContext, dockedIntent);
    }

    private void verifyAndSendResultBroadcast() {
        verify(mContext).sendOrderedBroadcastAsUser(
                mOrderedBroadcastIntent.capture(),
                any(UserHandle.class),
                nullable(String.class),
                mOrderedBroadcastReceiver.capture(),
                nullable(Handler.class),
                anyInt(),
                nullable(String.class),
                nullable(Bundle.class));

        mOrderedBroadcastReceiver.getValue().setPendingResult(
                new BroadcastReceiver.PendingResult(
                        Activity.RESULT_OK,
                        /* resultData= */ "",
                        /* resultExtras= */ null,
                        /* type= */ 0,
                        /* ordered= */ true,
                        /* sticky= */ false,
                        /* token= */ null,
                        /* userId= */ 0,
                        /* flags= */ 0));
        mOrderedBroadcastReceiver.getValue().onReceive(
                mContext,
                mOrderedBroadcastIntent.getValue());
    }

    private void setScreensaverEnabled(boolean enable) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.SCREENSAVER_ENABLED,
                enable ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    private void setScreensaverActivateOnDock(boolean enable) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                enable ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    private void requestAllPossibleProjectionTypes() throws RemoteException {
        for (int i = 0; i < Integer.SIZE; ++i) {
            mService.requestProjection(mBinder, 1 << i, PACKAGE_NAME);
        }
    }

    private static class TestInjector extends UiModeManagerService.Injector {
        private static final int DEFAULT_CALLING_UID = 8675309;

        private final int callingUid;

        public TestInjector() {
          this(DEFAULT_CALLING_UID);
        }

        public TestInjector(int callingUid) {
          this.callingUid = callingUid;
        }

        public int getCallingUid() {
            return callingUid;
        }
    }
}
