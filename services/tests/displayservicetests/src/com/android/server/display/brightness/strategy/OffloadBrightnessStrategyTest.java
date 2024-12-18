/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;
import com.android.server.display.feature.DisplayManagerFlags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class OffloadBrightnessStrategyTest {

    @Mock
    private DisplayManagerFlags mDisplayManagerFlags;

    private OffloadBrightnessStrategy mOffloadBrightnessStrategy;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mOffloadBrightnessStrategy = new OffloadBrightnessStrategy(mDisplayManagerFlags);
    }

    @Test
    public void testUpdateBrightnessWhenOffloadBrightnessIsSet() {
        when(mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()).thenReturn(true);
        DisplayManagerInternal.DisplayPowerRequest
                displayPowerRequest = new DisplayManagerInternal.DisplayPowerRequest();
        float brightness = 0.2f;
        mOffloadBrightnessStrategy.setOffloadScreenBrightness(brightness);
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_OFFLOAD);
        DisplayBrightnessState expectedDisplayBrightnessState =
                new DisplayBrightnessState.Builder()
                        .setBrightness(brightness)
                        .setBrightnessReason(brightnessReason)
                        .setDisplayBrightnessStrategyName(mOffloadBrightnessStrategy.getName())
                        .setShouldUpdateScreenBrightnessSetting(true)
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mOffloadBrightnessStrategy.updateBrightness(
                        new StrategyExecutionRequest(displayPowerRequest, 0.2f,
                                /* userSetBrightnessChanged= */ false));
        assertEquals(updatedDisplayBrightnessState, expectedDisplayBrightnessState);
        assertEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT, mOffloadBrightnessStrategy
                .getOffloadScreenBrightness(), 0.0f);
    }

    @Test
    public void strategySelectionPostProcessor_resetsOffloadBrightness() {
        StrategySelectionNotifyRequest strategySelectionNotifyRequest =
                mock(StrategySelectionNotifyRequest.class);
        DisplayBrightnessStrategy displayBrightnessStrategy = mock(DisplayBrightnessStrategy.class);
        when(strategySelectionNotifyRequest.getSelectedDisplayBrightnessStrategy())
                .thenReturn(displayBrightnessStrategy);

        float offloadBrightness = 0.3f;
        mOffloadBrightnessStrategy.setOffloadScreenBrightness(offloadBrightness);

        // Brightness is not reset if offload strategy is selected
        when(displayBrightnessStrategy.getName())
                .thenReturn(mOffloadBrightnessStrategy.getName());
        mOffloadBrightnessStrategy.strategySelectionPostProcessor(strategySelectionNotifyRequest);
        assertEquals(offloadBrightness,
                mOffloadBrightnessStrategy.getOffloadScreenBrightness(), 0.0f);

        // Brightness is not reset if invalid strategy is selected
        when(displayBrightnessStrategy.getName())
                .thenReturn(DisplayBrightnessStrategyConstants.INVALID_BRIGHTNESS_STRATEGY_NAME);
        mOffloadBrightnessStrategy.strategySelectionPostProcessor(strategySelectionNotifyRequest);
        assertEquals(offloadBrightness,
                mOffloadBrightnessStrategy.getOffloadScreenBrightness(), 0.0f);


        // Brightness is reset if neither of invalid or offload strategy is selected
        when(displayBrightnessStrategy.getName())
                .thenReturn("DisplayBrightnessStrategy");
        mOffloadBrightnessStrategy.strategySelectionPostProcessor(strategySelectionNotifyRequest);
        assertEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT,
                mOffloadBrightnessStrategy.getOffloadScreenBrightness(), 0.0f);
    }
}
