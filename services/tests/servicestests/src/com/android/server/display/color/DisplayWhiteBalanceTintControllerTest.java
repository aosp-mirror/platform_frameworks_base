/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.color;

import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Before;
import org.junit.Test;

public class DisplayWhiteBalanceTintControllerTest {

    private DisplayWhiteBalanceTintController mDisplayWhiteBalanceTintController;

    @Before
    public void setUp() {
        mDisplayWhiteBalanceTintController = new DisplayWhiteBalanceTintController();
    }

    @Test
    public void displayWhiteBalance_setTemperatureOverMax() {
        final int max = mDisplayWhiteBalanceTintController.mTemperatureMax;
        mDisplayWhiteBalanceTintController.setMatrix(max + 1);
        assertWithMessage("Unexpected temperature set")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(max);
    }

    @Test
    public void displayWhiteBalance_setTemperatureBelowMin() {
        final int min = mDisplayWhiteBalanceTintController.mTemperatureMin;
        mDisplayWhiteBalanceTintController.setMatrix(min - 1);
        assertWithMessage("Unexpected temperature set")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(min);
    }

    @Test
    public void displayWhiteBalance_setValidTemperature() {
        final int colorTemperature = (mDisplayWhiteBalanceTintController.mTemperatureMin
                + mDisplayWhiteBalanceTintController.mTemperatureMax) / 2;
        mDisplayWhiteBalanceTintController.setMatrix(colorTemperature);

        assertWithMessage("Unexpected temperature set")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(colorTemperature);
    }

}
