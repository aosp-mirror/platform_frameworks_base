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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import static android.os.BatteryManager.EXTRA_PRESENT;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BatteryControllerTest extends SysuiTestCase {

    @Mock
    private PowerManager mPowerManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    private BatteryControllerImpl mBatteryController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mBatteryController = new BatteryControllerImpl(getContext(), mock(EnhancedEstimates.class),
                mPowerManager, mBroadcastDispatcher, new Handler(), new Handler());
        mBatteryController.init();
    }

    @Test
    public void testBatteryInitialized() {
        Assert.assertTrue(mBatteryController.mHasReceivedBattery);
    }

    @Test
    public void testIndependentAODBatterySaver_true() {
        PowerSaveState state = new PowerSaveState.Builder()
                .setBatterySaverEnabled(true)
                .build();
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        when(mPowerManager.getPowerSaveState(PowerManager.ServiceType.AOD)).thenReturn(state);
        when(mPowerManager.isPowerSaveMode()).thenReturn(true);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isPowerSave());
        Assert.assertTrue(mBatteryController.isAodPowerSave());
    }

    @Test
    public void testIndependentAODBatterySaver_false() {
        PowerSaveState state = new PowerSaveState.Builder()
                .setBatterySaverEnabled(false)
                .build();
        Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        when(mPowerManager.getPowerSaveState(PowerManager.ServiceType.AOD)).thenReturn(state);
        when(mPowerManager.isPowerSaveMode()).thenReturn(true);

        mBatteryController.onReceive(getContext(), intent);

        Assert.assertTrue(mBatteryController.isPowerSave());
        Assert.assertFalse(mBatteryController.isAodPowerSave());
    }

    @Test
    public void testBatteryPresentState_notPresent() {
        // GIVEN a battery state callback listening for changes
        BatteryStateChangeCallback cb = mock(BatteryStateChangeCallback.class);
        mBatteryController.addCallback(cb);

        // WHEN the state of the battery becomes unknown
        Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED);
        i.putExtra(EXTRA_PRESENT, false);
        mBatteryController.onReceive(getContext(), i);

        // THEN the callback is notified
        verify(cb, atLeastOnce()).onBatteryUnknownStateChanged(true);
    }

    @Test
    public void testBatteryPresentState_callbackAddedAfterStateChange() {
        // GIVEN a battery state callback
        BatteryController.BatteryStateChangeCallback cb =
                mock(BatteryController.BatteryStateChangeCallback.class);

        // GIVEN the state has changed before adding a new callback
        Intent i = new Intent(Intent.ACTION_BATTERY_CHANGED);
        i.putExtra(EXTRA_PRESENT, false);
        mBatteryController.onReceive(getContext(), i);

        // WHEN a callback is added
        mBatteryController.addCallback(cb);

        // THEN it is informed about the battery state
        verify(cb, atLeastOnce()).onBatteryUnknownStateChanged(true);
    }
}
