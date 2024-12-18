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

package com.android.server.display;

import static org.junit.Assert.assertEquals;

import android.os.PowerManager;

import androidx.test.filters.SmallTest;

import com.android.internal.annotations.Keep;
import com.android.server.display.DisplayDeviceConfig.BrightnessLimitMapType;

import com.google.common.collect.ImmutableMap;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

@SmallTest
@RunWith(JUnitParamsRunner.class)
public class NormalBrightnessModeControllerTest {
    private static final float FLOAT_TOLERANCE = 0.001f;

    private final NormalBrightnessModeController mController = new NormalBrightnessModeController();

    // AutoBrightnessController sends ambientLux values *only* when auto brightness enabled.
    // NormalBrightnessModeController is temporary disabled  if auto brightness is off,
    // to avoid capping brightness based on stale ambient lux. Temporary disabling tests with
    // auto brightness off and default config pres
    // The issue is tracked here: b/322445088
    @Keep
    private static Object[][] brightnessData() {
        return new Object[][]{
                // no brightness config
                {0, AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED, new HashMap<>(),
                        PowerManager.BRIGHTNESS_MAX},
                {0, AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED, new HashMap<>(),
                        PowerManager.BRIGHTNESS_MAX},
                {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED, new HashMap<>(),
                        PowerManager.BRIGHTNESS_MAX},
                // Auto brightness - on, config only for default
                {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED, ImmutableMap.of(
                        BrightnessLimitMapType.DEFAULT,
                        ImmutableMap.of(99f, 0.1f, 101f, 0.2f)
                ), 0.2f},
                // Auto brightness - off, config only for default
                // {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED, ImmutableMap.of(
                //        BrightnessLimitMapType.DEFAULT,
                //        ImmutableMap.of(99f, 0.1f, 101f, 0.2f)
                // ), 0.2f},
                // Auto brightness - off, config only for adaptive
                {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED, ImmutableMap.of(
                        BrightnessLimitMapType.ADAPTIVE,
                        ImmutableMap.of(99f, 0.1f, 101f, 0.2f)
                ), PowerManager.BRIGHTNESS_MAX},
                // Auto brightness - on, config only for adaptive
                {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED, ImmutableMap.of(
                        BrightnessLimitMapType.ADAPTIVE,
                        ImmutableMap.of(99f, 0.1f, 101f, 0.2f)
                ), 0.2f},
                // Auto brightness - on, config for both
                {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED, ImmutableMap.of(
                        BrightnessLimitMapType.DEFAULT,
                        ImmutableMap.of(99f, 0.1f, 101f, 0.2f),
                        BrightnessLimitMapType.ADAPTIVE,
                        ImmutableMap.of(99f, 0.3f, 101f, 0.4f)
                ), 0.4f},
                // Auto brightness - off, config for both
                // {100, AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED, ImmutableMap.of(
                //        BrightnessLimitMapType.DEFAULT,
                //        ImmutableMap.of(99f, 0.1f, 101f, 0.2f),
                //        BrightnessLimitMapType.ADAPTIVE,
                //        ImmutableMap.of(99f, 0.3f, 101f, 0.4f)
                // ), 0.2f},
                // Auto brightness - on, config for both, ambient high
                {1000, AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED, ImmutableMap.of(
                        BrightnessLimitMapType.DEFAULT,
                        ImmutableMap.of(1000f, 0.1f, 2000f, 0.2f),
                        BrightnessLimitMapType.ADAPTIVE,
                        ImmutableMap.of(99f, 0.3f, 101f, 0.4f)
                ), PowerManager.BRIGHTNESS_MAX},
        };
    }

    @Test
    @Parameters(method = "brightnessData")
    public void testReturnsCorrectMaxBrightness(float ambientLux, int autoBrightnessState,
            Map<BrightnessLimitMapType, Map<Float, Float>> maxBrightnessConfig,
            float expectedBrightness) {
        setupController(ambientLux, autoBrightnessState, maxBrightnessConfig);

        assertEquals(expectedBrightness, mController.getCurrentBrightnessMax(), FLOAT_TOLERANCE);
    }

    private void setupController(float ambientLux, int autoBrightnessState,
            Map<BrightnessLimitMapType, Map<Float, Float>> maxBrightnessConfig) {
        mController.onAmbientLuxChange(ambientLux);
        mController.setAutoBrightnessState(autoBrightnessState);
        mController.resetNbmData(maxBrightnessConfig);
    }
}
