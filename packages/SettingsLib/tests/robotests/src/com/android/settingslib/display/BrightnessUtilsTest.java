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
 * limitations under the License.
 */

package com.android.settingslib.display;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MIN;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BrightnessUtilsTest {

    private static final int MIN_INT = 1;
    private static final int MAX_INT = 255;
    private static final float MIN_FLOAT = 0.0f;
    private static final float MAX_FLOAT = 1.0f;

    @Test
    public void linearToGamma_minValue_shouldReturnMin() {
        assertThat(BrightnessUtils.convertLinearToGamma(MIN_INT, MIN_INT, MAX_INT))
                .isEqualTo(GAMMA_SPACE_MIN);
        assertThat(BrightnessUtils.convertLinearToGammaFloat(MIN_FLOAT, MIN_FLOAT, MAX_FLOAT))
                .isEqualTo(GAMMA_SPACE_MIN);
    }

    @Test
    public void linearToGamma_maxValue_shouldReturnGammaSpaceMax() {
        assertThat(BrightnessUtils.convertLinearToGamma(MAX_INT, MIN_INT, MAX_INT))
                .isEqualTo(GAMMA_SPACE_MAX);
        assertThat(BrightnessUtils.convertLinearToGammaFloat(MAX_FLOAT, MIN_FLOAT, MAX_FLOAT))
                .isEqualTo(GAMMA_SPACE_MAX);
    }

    @Test
    public void gammaToLinear_minValue_shouldReturnMin() {
        assertThat(BrightnessUtils.convertGammaToLinear(GAMMA_SPACE_MIN, MIN_INT, MAX_INT))
                .isEqualTo(MIN_INT);
        assertThat(BrightnessUtils.convertGammaToLinearFloat(GAMMA_SPACE_MIN, MIN_FLOAT, MAX_FLOAT))
                .isEqualTo(MIN_FLOAT);
    }

    @Test
    public void gammaToLinear_gammaSpaceValue_shouldReturnMax() {
        assertThat(BrightnessUtils.convertGammaToLinear(GAMMA_SPACE_MAX, MIN_INT, MAX_INT))
                .isEqualTo(MAX_INT);
        assertThat(BrightnessUtils.convertGammaToLinearFloat(GAMMA_SPACE_MAX, MIN_FLOAT, MAX_FLOAT))
                .isEqualTo(MAX_FLOAT);
    }
}
