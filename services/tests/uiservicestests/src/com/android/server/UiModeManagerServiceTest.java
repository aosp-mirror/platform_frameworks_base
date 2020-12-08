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

import android.Manifest;
import android.app.AlarmManager;
import android.app.IUiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.android.server.wm.WindowManagerInternal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Consumer;

import static android.app.UiModeManager.MODE_NIGHT_AUTO;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM;
import static android.app.UiModeManager.MODE_NIGHT_NO;
import static android.app.UiModeManager.MODE_NIGHT_YES;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class UiModeManagerServiceTest extends UiServiceTestCase {
    private UiModeManagerService mUiManagerService;
    private IUiModeManager mService;
    @Mock
    private ContentResolver mContentResolver;
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

    private BroadcastReceiver mScreenOffCallback;
    private BroadcastReceiver mTimeChangedCallback;
    private AlarmManager.OnAlarmListener mCustomListener;
    private Consumer<PowerSaveState> mPowerSaveConsumer;
    private TwilightListener mTwilightListener;

    @Before
    public void setUp() {
        initMocks(this);
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
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
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
        when(mContext.getSystemService(eq(Context.ALARM_SERVICE)))
                .thenReturn(mAlarmManager);
        addLocalService(WindowManagerInternal.class, mWindowManager);
        addLocalService(PowerManagerInternal.class, mLocalPowerManager);
        addLocalService(TwilightManager.class, mTwilightManager);
        
        mUiManagerService = new UiModeManagerService(mContext, true,
                mTwilightManager);
        try {
            mUiManagerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        } catch (SecurityException e) {/* ignore for permission denial */}
        mService = mUiManagerService.getService();
    }

    private <T> void addLocalService(Class<T> clazz, T service) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, service);
    }

    @Test
    public void setNightMoveActivated_overridesFunctionCorrectly() throws RemoteException {
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
    public void setAutoMode_screenOffRegistered() throws RemoteException {
        try {
            mService.setNightMode(MODE_NIGHT_NO);
        } catch (SecurityException e) { /* we should ignore this update config exception*/ }
        mService.setNightMode(MODE_NIGHT_AUTO);
        verify(mContext, atLeastOnce()).registerReceiver(any(BroadcastReceiver.class), any());
    }

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
    public void setNightModeActivated_fromNoToYesAndBAck() throws RemoteException {
        mService.setNightMode(MODE_NIGHT_NO);
        mService.setNightModeActivated(true);
        assertTrue(isNightModeActivated());
        mService.setNightModeActivated(false);
        assertFalse(isNightModeActivated());
    }

    @Test
    public void setNightModeActivated_permissiontoChangeOtherUsers() throws RemoteException {
        mUiManagerService.onSwitchUser(9);
        when(mContext.checkCallingOrSelfPermission(
                eq(Manifest.permission.INTERACT_ACROSS_USERS)))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertFalse(mService.setNightModeActivated(true));
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
}
