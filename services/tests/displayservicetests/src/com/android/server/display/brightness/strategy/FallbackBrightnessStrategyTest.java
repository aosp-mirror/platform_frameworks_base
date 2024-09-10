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

package com.android.server.display.brightness.strategy;


import static org.junit.Assert.assertEquals;

import android.hardware.display.DisplayManagerInternal;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.StrategyExecutionRequest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)

public class FallbackBrightnessStrategyTest {
    private FallbackBrightnessStrategy mFallbackBrightnessStrategy;

    @Before
    public void before() {
        mFallbackBrightnessStrategy = new FallbackBrightnessStrategy();
    }

    @Test
    public void updateBrightness_currentBrightnessIsSet() {
        DisplayManagerInternal.DisplayPowerRequest
                displayPowerRequest = new DisplayManagerInternal.DisplayPowerRequest();
        float currentBrightness = 0.2f;
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_MANUAL);
        DisplayBrightnessState expectedDisplayBrightnessState =
                new DisplayBrightnessState.Builder()
                        .setBrightness(currentBrightness)
                        .setBrightnessReason(brightnessReason)
                        .setDisplayBrightnessStrategyName(mFallbackBrightnessStrategy.getName())
                        .setShouldUpdateScreenBrightnessSetting(true)
                        .setIsUserInitiatedChange(true)
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mFallbackBrightnessStrategy.updateBrightness(
                        new StrategyExecutionRequest(displayPowerRequest, currentBrightness,
                                /* userSetBrightnessChanged= */ true));
        assertEquals(updatedDisplayBrightnessState, expectedDisplayBrightnessState);
    }
}
