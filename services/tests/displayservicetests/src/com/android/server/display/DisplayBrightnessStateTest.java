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

package com.android.server.display;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.brightness.BrightnessReason;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayBrightnessStateTest {
    private static final float FLOAT_DELTA = 0.001f;

    private DisplayBrightnessState.Builder mDisplayBrightnessStateBuilder;

    @Before
    public void before() {
        mDisplayBrightnessStateBuilder = new DisplayBrightnessState.Builder();
    }

    @Test
    public void validateAllDisplayBrightnessStateFieldsAreSetAsExpected() {
        float brightness = 0.3f;
        float sdrBrightness = 0.2f;
        boolean shouldUseAutoBrightness = true;
        boolean shouldUpdateScreenBrightnessSetting = true;
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_AUTOMATIC);
        brightnessReason.setModifier(BrightnessReason.MODIFIER_DIMMED);
        DisplayBrightnessState displayBrightnessState = mDisplayBrightnessStateBuilder
                .setBrightness(brightness)
                .setSdrBrightness(sdrBrightness)
                .setBrightnessReason(brightnessReason)
                .setShouldUseAutoBrightness(shouldUseAutoBrightness)
                .setShouldUpdateScreenBrightnessSetting(shouldUpdateScreenBrightnessSetting)
                .build();

        assertEquals(displayBrightnessState.getBrightness(), brightness, FLOAT_DELTA);
        assertEquals(displayBrightnessState.getSdrBrightness(), sdrBrightness, FLOAT_DELTA);
        assertEquals(displayBrightnessState.getBrightnessReason(), brightnessReason);
        assertEquals(displayBrightnessState.getShouldUseAutoBrightness(), shouldUseAutoBrightness);
        assertEquals(shouldUpdateScreenBrightnessSetting,
                displayBrightnessState.shouldUpdateScreenBrightnessSetting());
        assertEquals(displayBrightnessState.toString(), getString(displayBrightnessState));
    }

    @Test
    public void testFrom() {
        BrightnessReason reason = new BrightnessReason();
        reason.setReason(BrightnessReason.REASON_MANUAL);
        reason.setModifier(BrightnessReason.MODIFIER_DIMMED);
        DisplayBrightnessState state1 = new DisplayBrightnessState.Builder()
                .setBrightnessReason(reason)
                .setBrightness(0.26f)
                .setSdrBrightness(0.23f)
                .setShouldUseAutoBrightness(false)
                .setShouldUpdateScreenBrightnessSetting(true)
                .build();
        DisplayBrightnessState state2 = DisplayBrightnessState.Builder.from(state1).build();
        assertEquals(state1, state2);
    }

    private String getString(DisplayBrightnessState displayBrightnessState) {
        StringBuilder sb = new StringBuilder();
        sb.append("DisplayBrightnessState:")
                .append("\n    brightness:")
                .append(displayBrightnessState.getBrightness())
                .append("\n    sdrBrightness:")
                .append(displayBrightnessState.getSdrBrightness())
                .append("\n    brightnessReason:")
                .append(displayBrightnessState.getBrightnessReason())
                .append("\n    shouldUseAutoBrightness:")
                .append(displayBrightnessState.getShouldUseAutoBrightness())
                .append("\n    isSlowChange:")
                .append(displayBrightnessState.isSlowChange())
                .append("\n    maxBrightness:")
                .append(displayBrightnessState.getMaxBrightness())
                .append("\n    customAnimationRate:")
                .append(displayBrightnessState.getCustomAnimationRate())
                .append("\n    shouldUpdateScreenBrightnessSetting:")
                .append(displayBrightnessState.shouldUpdateScreenBrightnessSetting());
        return sb.toString();
    }
}
