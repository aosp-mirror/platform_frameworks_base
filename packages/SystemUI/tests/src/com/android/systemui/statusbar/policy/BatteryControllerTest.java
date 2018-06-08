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

import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

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
    private BatteryControllerImpl mBatteryController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mBatteryController = new BatteryControllerImpl(getContext(), mPowerManager);
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

}
