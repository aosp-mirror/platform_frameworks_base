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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BrightnessUtilsTest {

    private static final int MIN = 1;
    private static final int MAX = 255;

    @Test
    public void linearToGamma_minValue_shouldReturn0() {
        assertThat(BrightnessUtils.convertLinearToGamma(MIN, MIN, MAX)).isEqualTo(0);
    }

    @Test
    public void linearToGamma_maxValue_shouldReturnGammaSpaceMax() {
        assertThat(BrightnessUtils.convertLinearToGamma(MAX, MIN, MAX)).isEqualTo(GAMMA_SPACE_MAX);
    }

    @Test
    public void gammaToLinear_minValue_shouldReturnMin() {
        assertThat(BrightnessUtils.convertGammaToLinear(MIN, MIN, MAX)).isEqualTo(MIN);
    }

    @Test
    public void gammaToLinear_gammaSpaceValue_shouldReturnMax() {
        assertThat(BrightnessUtils.convertGammaToLinear(GAMMA_SPACE_MAX, MIN, MAX)).isEqualTo(MAX);
    }
}
