/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.graph;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class BatteryMeterDrawableBaseTest {
    private static final int CRITICAL_LEVEL = 5;
    private static final int PADDING = 5;
    private static final int HEIGHT = 80;
    private static final int WIDTH = 40;
    @Mock
    private Canvas mCanvas;
    private Context mContext;
    private BatteryMeterDrawableBase mBatteryMeterDrawableBase;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = RuntimeEnvironment.application;
        mBatteryMeterDrawableBase = spy(new BatteryMeterDrawableBase(mContext, 0 /* frameColor */));
        ReflectionHelpers.setField(mBatteryMeterDrawableBase, "mCriticalLevel", CRITICAL_LEVEL);
    }

    @Test
    public void testDraw_hasPaddingAndBounds_drawWarningInCorrectPosition() {
        mBatteryMeterDrawableBase.setPadding(PADDING, PADDING, PADDING, PADDING);
        mBatteryMeterDrawableBase.setBounds(0, 0, WIDTH + 2 * PADDING, HEIGHT + 2 * PADDING);
        mBatteryMeterDrawableBase.setBatteryLevel(3);

        mBatteryMeterDrawableBase.draw(mCanvas);

        // WIDTH * 0.5 + PADDING = 25
        // (HEIGHT + TEXT_HEIGHT) * 0.48 + PADDING = 43.3999998
        verify(mCanvas).drawText(eq("!"), eq(25f), eq(43.399998f), any(Paint.class));
    }

    @Test
    public void testDraw_hasPaddingAndBounds_drawBatteryLevelInCorrectPosition() {
        mBatteryMeterDrawableBase.setPadding(PADDING, PADDING, PADDING, PADDING);
        mBatteryMeterDrawableBase.setBounds(0, 0, WIDTH + 2 * PADDING, HEIGHT + 2 * PADDING);
        mBatteryMeterDrawableBase.setBatteryLevel(20);
        mBatteryMeterDrawableBase.setShowPercent(true);

        mBatteryMeterDrawableBase.draw(mCanvas);

        // WIDTH * 0.5 + PADDING = 25
        // (HEIGHT + TEXT_HEIGHT) * 0.47 + PADDING = 42.6
        verify(mCanvas).drawText(eq("20"), eq(25f), eq(42.6f), any(Paint.class));
    }
}
