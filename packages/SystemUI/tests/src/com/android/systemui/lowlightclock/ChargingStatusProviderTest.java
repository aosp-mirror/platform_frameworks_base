/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lowlightclock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ChargingStatusProviderTest extends SysuiTestCase {
    @Mock
    private Resources mResources;
    @Mock
    private IBatteryStats mBatteryInfo;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private ChargingStatusProvider.Callback mCallback;

    private ChargingStatusProvider mProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mProvider = new ChargingStatusProvider(
                mContext, mResources, mBatteryInfo, mKeyguardUpdateMonitor);
    }

    @Test
    public void testStartUsingReportsStatusToCallback() {
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
    }

    @Test
    public void testStartUsingRegistersCallbackWithKeyguardUpdateMonitor() {
        mProvider.startUsing(mCallback);
        verify(mKeyguardUpdateMonitor).registerCallback(any());
    }

    @Test
    public void testCallbackNotCalledAfterStopUsing() {
        mProvider.startUsing(mCallback);
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        mProvider.stopUsing();
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getChargingBattery());
        verify(mCallback, never()).onChargingStatusChanged(eq(true), any());
    }

    @Test
    public void testKeyguardUpdateMonitorCallbackRemovedAfterStopUsing() {
        mProvider.startUsing(mCallback);
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        mProvider.stopUsing();
        verify(mKeyguardUpdateMonitor)
                .removeCallback(keyguardUpdateMonitorCallbackArgumentCaptor.getValue());
    }

    @Test
    public void testChargingStatusReportsHideWhenNotPluggedIn() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getUnpluggedBattery());
        // Once for init() and once for the status change.
        verify(mCallback, times(2)).onChargingStatusChanged(false, null);
    }

    @Test
    public void testChargingStatusReportsShowWhenBatteryOverheated() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getBatteryDefender());
        verify(mCallback).onChargingStatusChanged(eq(true), any());
    }

    @Test
    public void testChargingStatusReportsShowWhenPluggedIn() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getChargingBattery());
        verify(mCallback).onChargingStatusChanged(eq(true), any());
    }

    @Test
    public void testChargingStatusReportsChargingLimitedWhenOverheated() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getBatteryDefender());
        verify(mResources).getString(eq(R.string.keyguard_plugged_in_charging_limited), any());
    }

    @Test
    public void testChargingStatusReportsChargedWhenCharged() {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getChargedBattery());
        verify(mResources).getString(R.string.keyguard_charged);
    }

    @Test
    public void testChargingStatusReportsPluggedInWhenDockedAndChargingTimeUnknown() throws
            RemoteException {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        when(mBatteryInfo.computeChargeTimeRemaining()).thenReturn(-1L);
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getChargingBattery());
        verify(mResources).getString(
                eq(R.string.keyguard_plugged_in_dock), any());
    }

    @Test
    public void testChargingStatusReportsTimeRemainingWhenDockedAndCharging() throws
            RemoteException {
        ArgumentCaptor<KeyguardUpdateMonitorCallback> keyguardUpdateMonitorCallbackArgumentCaptor =
                ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback.class);
        mProvider.startUsing(mCallback);
        verify(mCallback).onChargingStatusChanged(false, null);
        verify(mKeyguardUpdateMonitor)
                .registerCallback(keyguardUpdateMonitorCallbackArgumentCaptor.capture());
        when(mBatteryInfo.computeChargeTimeRemaining()).thenReturn(1L);
        keyguardUpdateMonitorCallbackArgumentCaptor.getValue()
                .onRefreshBatteryInfo(getChargingBattery());
        verify(mResources).getString(
                eq(R.string.keyguard_indication_charging_time_dock), any(), any());
    }

    private BatteryStatus getUnpluggedBattery() {
        return new BatteryStatus(BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                80, BatteryManager.BATTERY_PLUGGED_ANY, BatteryManager.BATTERY_HEALTH_GOOD,
                0, true);
    }

    private BatteryStatus getChargingBattery() {
        return new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80, BatteryManager.BATTERY_PLUGGED_DOCK,
                BatteryManager.BATTERY_HEALTH_GOOD, 0, true);
    }

    private BatteryStatus getChargedBattery() {
        return new BatteryStatus(BatteryManager.BATTERY_STATUS_FULL,
                100, BatteryManager.BATTERY_PLUGGED_DOCK,
                BatteryManager.BATTERY_HEALTH_GOOD, 0, true);
    }

    private BatteryStatus getBatteryDefender() {
        return new BatteryStatus(BatteryManager.BATTERY_STATUS_CHARGING,
                80, BatteryManager.BATTERY_PLUGGED_DOCK,
                BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE, 0, true);
    }
}
