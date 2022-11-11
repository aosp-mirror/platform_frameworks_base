/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display.brightness;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.display.brightness.strategy.DozeBrightnessStrategy;
import com.android.server.display.brightness.strategy.InvalidBrightnessStrategy;
import com.android.server.display.brightness.strategy.OverrideBrightnessStrategy;
import com.android.server.display.brightness.strategy.ScreenOffBrightnessStrategy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayBrightnessStrategySelectorTest {
    private static final boolean DISALLOW_AUTO_BRIGHTNESS_WHILE_DOZING = false;
    private static final int DISPLAY_ID = 1;

    @Mock
    private ScreenOffBrightnessStrategy mScreenOffBrightnessModeStrategy;
    @Mock
    private DozeBrightnessStrategy mDozeBrightnessModeStrategy;
    @Mock
    private OverrideBrightnessStrategy mOverrideBrightnessStrategy;
    @Mock
    private InvalidBrightnessStrategy mInvalidBrightnessStrategy;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;

    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        DisplayBrightnessStrategySelector.Injector injector =
                new DisplayBrightnessStrategySelector.Injector() {
                    @Override
                    ScreenOffBrightnessStrategy getScreenOffBrightnessStrategy() {
                        return mScreenOffBrightnessModeStrategy;
                    }

                    @Override
                    DozeBrightnessStrategy getDozeBrightnessStrategy() {
                        return mDozeBrightnessModeStrategy;
                    }

                    @Override
                    OverrideBrightnessStrategy getOverrideBrightnessStrategy() {
                        return mOverrideBrightnessStrategy;
                    }

                    @Override
                    InvalidBrightnessStrategy getInvalidBrightnessStrategy() {
                        return mInvalidBrightnessStrategy;
                    }
                };
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                injector, DISPLAY_ID);

    }

    @Test
    public void selectStrategySelectsDozeStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        when(mResources.getBoolean(R.bool.config_allowAutoBrightnessWhileDozing)).thenReturn(
                DISALLOW_AUTO_BRIGHTNESS_WHILE_DOZING);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                Display.STATE_DOZE), mDozeBrightnessModeStrategy);
    }

    @Test
    public void selectStrategySelectsScreenOffStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                Display.STATE_OFF), mScreenOffBrightnessModeStrategy);
    }

    @Test
    public void selectStrategySelectsOverrideStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = 0.4f;
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                Display.STATE_ON), mOverrideBrightnessStrategy);
    }

    @Test
    public void selectStrategySelectsInvalidStrategyWhenNoStrategyIsValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                Display.STATE_ON), mInvalidBrightnessStrategy);
    }
}
