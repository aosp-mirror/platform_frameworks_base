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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;
import static com.android.server.display.color.DisplayTransformManager.PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE;
import static com.android.server.display.color.DisplayTransformManager.PERSISTENT_PROPERTY_DISPLAY_COLOR;
import static com.android.server.display.color.DisplayTransformManager.PERSISTENT_PROPERTY_SATURATION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;

import android.hardware.display.ColorDisplayManager;
import android.os.SystemProperties;
import android.view.Display;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public class DisplayTransformManagerTest {

    private MockitoSession mSession;
    private DisplayTransformManager mDtm;
    private float[] mNightDisplayMatrix;
    private HashMap<String, String> mSystemProperties;

    @Before
    public void setUp() {
        mDtm = new DisplayTransformManager();
        mNightDisplayMatrix = mDtm.getColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY);

        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(SystemProperties.class)
                .startMocking();
        mSystemProperties = new HashMap<>();

        doAnswer((Answer<Void>) invocationOnMock -> {
                    mSystemProperties.put(invocationOnMock.getArgument(0),
                            invocationOnMock.getArgument(1));
                    return null;
                }
        ).when(() -> SystemProperties.set(anyString(), any()));
    }

    @After
    public void tearDown() throws Exception {
        mSession.finishMocking();
        mSystemProperties.clear();
    }

    @Test
    public void setColorMode_natural() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_NATURAL, mNightDisplayMatrix, -1);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR))
                .isEqualTo("0" /* managed */);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_SATURATION))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_boosted() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_BOOSTED, mNightDisplayMatrix, -1);

        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR))
                .isEqualTo("0" /* managed */);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_SATURATION))
                .isEqualTo("1.1" /* boosted */);
    }

    @Test
    public void setColorMode_saturated() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_SATURATED, mNightDisplayMatrix, -1);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR))
                .isEqualTo("1" /* unmanaged */);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_SATURATION))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_automatic() {
        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_AUTOMATIC, mNightDisplayMatrix, -1);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR))
                .isEqualTo("2" /* enhanced */);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_SATURATION))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_vendor() {
        mDtm.setColorMode(0x100, mNightDisplayMatrix, -1);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR))
                .isEqualTo(Integer.toString(0x100) /* pass-through */);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_SATURATION))
                .isEqualTo("1.0" /* natural */);
    }

    @Test
    public void setColorMode_outOfBounds() {
        mDtm.setColorMode(0x50, mNightDisplayMatrix, -1);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_DISPLAY_COLOR))
                .isEqualTo(null);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_SATURATION))
                .isEqualTo(null);
    }

    @Test
    public void setColorMode_withoutColorSpace() {
        String magicPropertyValue = "magic";

        // Start with a known state, which we expect to remain unmodified
        SystemProperties.set(PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE, magicPropertyValue);

        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_NATURAL, mNightDisplayMatrix,
                Display.COLOR_MODE_INVALID);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE))
                .isEqualTo(magicPropertyValue);
    }

    @Test
    public void setColorMode_withColorSpace() {
        String magicPropertyValue = "magic";
        int testPropertyValue = Display.COLOR_MODE_SRGB;

        // Start with a known state, which we expect to get modified
        SystemProperties.set(PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE, magicPropertyValue);

        mDtm.setColorMode(ColorDisplayManager.COLOR_MODE_NATURAL, mNightDisplayMatrix,
                testPropertyValue);
        assertThat(mSystemProperties.get(PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE))
                .isEqualTo(Integer.toString(testPropertyValue));
    }

}
