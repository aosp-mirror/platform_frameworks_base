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

package com.android.server.display.brightness.strategy;

import static android.os.PowerManager.BRIGHTNESS_INVALID_FLOAT;

import static com.google.common.truth.Truth.assertThat;

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
public class OverrideBrightnessStrategyTest {
    private OverrideBrightnessStrategy mOverrideBrightnessStrategy;

    @Before
    public void before() {
        mOverrideBrightnessStrategy = new OverrideBrightnessStrategy();
    }

    @Test
    public void testUpdateBrightnessWhenScreenDozeStateIsRequested() {
        DisplayManagerInternal.DisplayPowerRequest
                displayPowerRequest = new DisplayManagerInternal.DisplayPowerRequest();
        float overrideBrightness = 0.2f;
        displayPowerRequest.screenBrightnessOverride = overrideBrightness;
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_OVERRIDE);
        DisplayBrightnessState expectedDisplayBrightnessState =
                new DisplayBrightnessState.Builder()
                        .setBrightness(overrideBrightness)
                        .setBrightnessReason(brightnessReason)
                        .setDisplayBrightnessStrategyName(mOverrideBrightnessStrategy.getName())
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mOverrideBrightnessStrategy.updateBrightness(
                        new StrategyExecutionRequest(displayPowerRequest, 0.2f,
                                /* userSetBrightnessChanged= */ false,
                                /* isStylusBeingUsed */ false));
        assertThat(updatedDisplayBrightnessState).isEqualTo(expectedDisplayBrightnessState);
    }

    @Test
    public void testUpdateBrightnessWhenWindowManagerOverrideIsRequested() {
        var overrideRequest = new DisplayManagerInternal.DisplayBrightnessOverrideRequest();
        overrideRequest.brightness = 0.2f;
        mOverrideBrightnessStrategy.updateWindowManagerBrightnessOverride(overrideRequest);
        DisplayManagerInternal.DisplayPowerRequest
                displayPowerRequest = new DisplayManagerInternal.DisplayPowerRequest();
        displayPowerRequest.screenBrightnessOverride = BRIGHTNESS_INVALID_FLOAT;
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_OVERRIDE);
        DisplayBrightnessState expectedDisplayBrightnessState =
                new DisplayBrightnessState.Builder()
                        .setBrightness(overrideRequest.brightness)
                        .setBrightnessReason(brightnessReason)
                        .setDisplayBrightnessStrategyName(mOverrideBrightnessStrategy.getName())
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mOverrideBrightnessStrategy.updateBrightness(
                        new StrategyExecutionRequest(displayPowerRequest, 0.2f,
                                /* userSetBrightnessChanged= */ false,
                                /* isStylusBeingUsed */ false));
        assertThat(updatedDisplayBrightnessState).isEqualTo(expectedDisplayBrightnessState);
    }

    @Test
    public void testUpdateWindowManagerBrightnessOverride() {
        var request = new DisplayManagerInternal.DisplayBrightnessOverrideRequest();
        assertThat(mOverrideBrightnessStrategy.updateWindowManagerBrightnessOverride(request))
                .isFalse();
        assertThat(mOverrideBrightnessStrategy.getWindowManagerBrightnessOverride())
                .isEqualTo(BRIGHTNESS_INVALID_FLOAT);

        request.brightness = 0.2f;
        assertThat(mOverrideBrightnessStrategy.updateWindowManagerBrightnessOverride(request))
                .isTrue();
        assertThat(mOverrideBrightnessStrategy.getWindowManagerBrightnessOverride())
                .isEqualTo(0.2f);

        // Passing the same request doesn't result in an update.
        assertThat(mOverrideBrightnessStrategy.updateWindowManagerBrightnessOverride(request))
                .isFalse();
        assertThat(mOverrideBrightnessStrategy.getWindowManagerBrightnessOverride())
                .isEqualTo(0.2f);

        assertThat(mOverrideBrightnessStrategy.updateWindowManagerBrightnessOverride(null))
                .isTrue();
        assertThat(mOverrideBrightnessStrategy.getWindowManagerBrightnessOverride())
                .isEqualTo(BRIGHTNESS_INVALID_FLOAT);
    }
}
