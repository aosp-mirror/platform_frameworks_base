/*
 * Copyright (C) 2018 The Android Open Source Project
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


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.IPowerManager;
import android.os.OsProtoEnums;
import android.os.PowerManager;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.CachedDeviceState;
import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CachedDeviceStateService}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class CachedDeviceStateServiceTest {
    @Mock private BatteryManagerInternal mBatteryManager;
    @Mock private IPowerManager mPowerManager;
    private BroadcastInterceptingContext mContext;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getContext();
        PowerManager powerManager = new PowerManager(context, mPowerManager, null);
        mContext = new BroadcastInterceptingContext(context) {
            @Override
            public Object getSystemService(String name) {
                switch (name) {
                    case Context.POWER_SERVICE:
                        return powerManager;
                    default:
                        return super.getSystemService(name);
                }
            }
        };

        LocalServices.addService(BatteryManagerInternal.class, mBatteryManager);

        when(mBatteryManager.getPlugType()).thenReturn(OsProtoEnums.BATTERY_PLUGGED_NONE);
        when(mPowerManager.isInteractive()).thenReturn(true);
    }

    @After
    public void tearDown() {
        // Added by the CachedDeviceStateService.onStart().
        LocalServices.removeServiceForTest(CachedDeviceState.Readonly.class);

        // Added in @Before.
        LocalServices.removeServiceForTest(BatteryManagerInternal.class);
    }

    @Test
    public void correctlyReportsScreenInteractive() throws RemoteException {
        CachedDeviceStateService service = new CachedDeviceStateService(mContext);
        when(mPowerManager.isInteractive()).thenReturn(true); // Screen on.

        service.onStart();
        CachedDeviceState.Readonly deviceState =
                LocalServices.getService(CachedDeviceState.Readonly.class);

        // State can be initialized correctly only after PHASE_SYSTEM_SERVICES_READY.
        assertThat(deviceState.isScreenInteractive()).isFalse();

        service.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        assertThat(deviceState.isScreenInteractive()).isTrue();

        mContext.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        assertThat(deviceState.isScreenInteractive()).isFalse();

        mContext.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        assertThat(deviceState.isScreenInteractive()).isTrue();
    }

    @Test
    public void correctlyReportsCharging() {
        CachedDeviceStateService service = new CachedDeviceStateService(mContext);
        when(mBatteryManager.getPlugType()).thenReturn(OsProtoEnums.BATTERY_PLUGGED_NONE);

        service.onStart();
        CachedDeviceState.Readonly deviceState =
                LocalServices.getService(CachedDeviceState.Readonly.class);

        // State can be initialized correctly only after PHASE_SYSTEM_SERVICES_READY.
        assertThat(deviceState.isCharging()).isTrue();

        service.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        assertThat(deviceState.isCharging()).isFalse();

        Intent intentPluggedIn = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intentPluggedIn.putExtra(BatteryManager.EXTRA_PLUGGED, OsProtoEnums.BATTERY_PLUGGED_AC);
        mContext.sendBroadcast(intentPluggedIn);
        assertThat(deviceState.isCharging()).isTrue();

        Intent intentUnplugged = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intentUnplugged.putExtra(BatteryManager.EXTRA_PLUGGED, OsProtoEnums.BATTERY_PLUGGED_NONE);
        mContext.sendBroadcast(intentUnplugged);
        assertThat(deviceState.isCharging()).isFalse();
    }

    @Test
    public void correctlyTracksTimeOnBattery() throws Exception {
        CachedDeviceStateService service = new CachedDeviceStateService(mContext);
        when(mBatteryManager.getPlugType()).thenReturn(OsProtoEnums.BATTERY_PLUGGED_NONE);

        service.onStart();
        CachedDeviceState.Readonly deviceState =
                LocalServices.getService(CachedDeviceState.Readonly.class);

        CachedDeviceState.TimeInStateStopwatch stopwatch =
                deviceState.createTimeOnBatteryStopwatch();

        // State can be initialized correctly only after PHASE_SYSTEM_SERVICES_READY.
        assertThat(stopwatch.isRunning()).isFalse();
        service.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        assertThat(stopwatch.isRunning()).isTrue();
        stopwatch.reset();

        Thread.sleep(100);
        assertThat(stopwatch.isRunning()).isTrue();
        assertThat(stopwatch.getMillis()).isAtLeast(100L);

        long timeOnBatteryBeforePluggedIn = stopwatch.getMillis();
        Intent intentPluggedIn = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intentPluggedIn.putExtra(BatteryManager.EXTRA_PLUGGED, OsProtoEnums.BATTERY_PLUGGED_AC);
        mContext.sendBroadcast(intentPluggedIn);

        assertThat(stopwatch.getMillis()).isAtLeast(timeOnBatteryBeforePluggedIn);
        assertThat(stopwatch.isRunning()).isFalse();

        long timeOnBatteryAfterPluggedIn = stopwatch.getMillis();
        Thread.sleep(20);
        assertThat(stopwatch.getMillis()).isEqualTo(timeOnBatteryAfterPluggedIn);

        stopwatch.reset();
        assertThat(stopwatch.getMillis()).isEqualTo(0L);
        assertThat(stopwatch.isRunning()).isFalse();
    }
}
