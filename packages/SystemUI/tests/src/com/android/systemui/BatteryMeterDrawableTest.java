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

package com.android.systemui;

import com.android.settingslib.graph.BatteryMeterDrawableBase;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryMeterDrawableTest extends SysuiTestCase {

    private Resources mResources;
    private BatteryMeterDrawableBase mBatteryMeter;

    @Before
    public void setUp() throws Exception {
        mResources = mContext.getResources();
        mBatteryMeter = new BatteryMeterDrawableBase(mContext, 0);
    }

    @Test
    public void testDrawImageButNoTextIfPluggedIn() {
        mBatteryMeter.setBatteryLevel(0);
        mBatteryMeter.setCharging(true);
        final Canvas canvas = mock(Canvas.class);
        mBatteryMeter.draw(canvas);
        verify(canvas, atLeastOnce()).drawPath(any(), any());
        verify(canvas, never()).drawText(anyString(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void testDrawTextIfNotPluggedIn() {
        mBatteryMeter.setBatteryLevel(0);
        mBatteryMeter.setCharging(false);
        final Canvas canvas = mock(Canvas.class);
        mBatteryMeter.draw(canvas);
        verify(canvas, times(1)).drawText(anyString(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void testDrawNoTextIfPowerSaveEnabled() {
        mBatteryMeter.setBatteryLevel(0);
        mBatteryMeter.setCharging(false);
        mBatteryMeter.setPowerSave(true);
        final Canvas canvas = mock(Canvas.class);
        mBatteryMeter.draw(canvas);
        verify(canvas, never()).drawText(anyString(), anyFloat(), anyFloat(), any());
    }

    @Test
    public void testDrawTextWarningAtCriticalLevel() {
        int criticalLevel = mResources.getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mBatteryMeter.setBatteryLevel(criticalLevel);
        mBatteryMeter.setCharging(false);
        final Canvas canvas = mock(Canvas.class);
        mBatteryMeter.draw(canvas);
        String warningString = mResources.getString(R.string.battery_meter_very_low_overlay_symbol);
        verify(canvas, times(1)).drawText(eq(warningString), anyFloat(), anyFloat(), any());
    }

    @Test
    public void testDrawTextNoWarningAboveCriticalLevel() {
        int criticalLevel = mResources.getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mBatteryMeter.setBatteryLevel(criticalLevel + 1);
        mBatteryMeter.setCharging(false);
        final Canvas canvas = mock(Canvas.class);
        mBatteryMeter.draw(canvas);
        String warningString = mResources.getString(R.string.battery_meter_very_low_overlay_symbol);
        verify(canvas, never()).drawText(eq(warningString), anyFloat(), anyFloat(), any());
    }
}
