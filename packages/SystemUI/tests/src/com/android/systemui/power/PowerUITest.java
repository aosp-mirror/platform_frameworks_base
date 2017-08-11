/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.power;

import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN;
import static android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT;
import static android.os.HardwarePropertiesManager.TEMPERATURE_SHUTDOWN;
import static android.provider.Settings.Global.SHOW_TEMPERATURE_WARNING;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.HardwarePropertiesManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.power.PowerUI.WarningsUI;
import com.android.systemui.statusbar.phone.StatusBar;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class PowerUITest extends SysuiTestCase {

    private HardwarePropertiesManager mHardProps;
    private WarningsUI mMockWarnings;
    private PowerUI mPowerUI;

    @Before
    public void setup() {
        mMockWarnings = mDependency.injectMockDependency(WarningsUI.class);
        mHardProps = mock(HardwarePropertiesManager.class);
        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.addMockSystemService(Context.HARDWARE_PROPERTIES_SERVICE, mHardProps);

        createPowerUi();
    }

    @Test
    public void testNoConfig_NoWarnings() {
        setOverThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
    }

    @Test
    public void testConfig_NoWarnings() {
        setUnderThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
    }

    @Test
    public void testConfig_Warnings() {
        setOverThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testSettingOverrideConfig() {
        setOverThreshold();
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testShutdownBasedThreshold() {
        int tolerance = 2;
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, -1);
        resources.addOverride(R.integer.config_warningTemperatureTolerance, tolerance);
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_SHUTDOWN))
                .thenReturn(new float[] { 55 + tolerance });

        setCurrentTemp(54); // Below threshold.
        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();

        setCurrentTemp(56); // Above threshold.
        mPowerUI.updateTemperatureWarning();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    private void setCurrentTemp(float temp) {
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_CURRENT))
                .thenReturn(new float[] { temp });
    }

    private void setOverThreshold() {
        setCurrentTemp(50000);
    }

    private void setUnderThreshold() {
        setCurrentTemp(5);
    }

    private void createPowerUi() {
        mPowerUI = new PowerUI();
        mPowerUI.mContext = mContext;
        mPowerUI.mComponents = mContext.getComponents();
    }
}
