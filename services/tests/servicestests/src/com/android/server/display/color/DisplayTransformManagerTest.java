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

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;
import static com.android.server.display.color.DisplayTransformManager.PERSISTENT_PROPERTY_DISPLAY_COLOR;
import static com.android.server.display.color.DisplayTransformManager.PERSISTENT_PROPERTY_SATURATION;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.display.ColorDisplayManager;
import android.os.SystemProperties;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DisplayTransformManagerTest {

    private DisplayTransformManager mDtm;
    private float[] mNightDisplayMatrix;

    @Before
    public void setUp() {
        mDtm = new DisplayTransformManager();
        mNightDisplayMatrix = mDtm.getColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY);

        SystemProperties.set(PERSISTENT_PROPERTY_DISPLAY_COLOR, null);
        SystemProperties.set(PERSISTENT_PROPERTY_SATURATION, null);
    }

    @Test
    public void setColorMode_natural() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_NATURAL, mNightDisplayMatrix);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR, null))
                .isEqualTo("0" /* managed */);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_SATURATION, null))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_boosted() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_BOOSTED, mNightDisplayMatrix);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR, null))
                .isEqualTo("0" /* managed */);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_SATURATION, null))
                .isEqualTo("1.1" /* boosted */);
    }

    @Test
    public void setColorMode_saturated() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_SATURATED, mNightDisplayMatrix);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR, null))
                .isEqualTo("1" /* unmanaged */);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_SATURATION, null))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_automatic() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_AUTOMATIC, mNightDisplayMatrix);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR, null))
                .isEqualTo("2" /* enhanced */);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_SATURATION, null))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_vendor() {
        mDtm.setColorMode(0x100, mNightDisplayMatrix);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR, null))
                .isEqualTo(Integer.toString(0x100) /* pass-through */);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_SATURATION, null))
                .isEqualTo("1.0" /* default */);
    }

    @Test
    public void setColorMode_outOfBounds() {
        mDtm.setColorMode(0x50, mNightDisplayMatrix);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR, null))
                .isEqualTo("" /* default */);
        assertThat(SystemProperties.get(PERSISTENT_PROPERTY_SATURATION, null))
                .isEqualTo("" /* default */);
    }
}
