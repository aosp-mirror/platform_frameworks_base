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

package com.android.server.display.brightness.clamper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class DisplayDimModifierTest {
    private static final float FLOAT_TOLERANCE = 0.001f;
    private static final float DEFAULT_BRIGHTNESS = 0.5f;
    private static final float MIN_DIM_AMOUNT = 0.05f;
    private static final float DIM_CONFIG = 0.4f;

    @Mock
    private Context mMockContext;

    @Mock
    private PowerManager mMockPowerManager;

    @Mock
    private Resources mMockResources;

    private final DisplayManagerInternal.DisplayPowerRequest
            mRequest = new DisplayManagerInternal.DisplayPowerRequest();
    private final DisplayBrightnessState.Builder mBuilder = prepareBuilder();
    private DisplayDimModifier mModifier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getFloat(
                R.dimen.config_screenBrightnessMinimumDimAmountFloat)).thenReturn(MIN_DIM_AMOUNT);
        when(mMockContext.getSystemService(PowerManager.class)).thenReturn(mMockPowerManager);
        when(mMockPowerManager.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DIM)).thenReturn(DIM_CONFIG);

        mModifier = new DisplayDimModifier(mMockContext);
        mRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
    }

    @Test
    public void testApply_noDimPolicy() {
        mRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
        mModifier.apply(mRequest, mBuilder);

        assertEquals(DEFAULT_BRIGHTNESS, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(0, mBuilder.getBrightnessReason().getModifier());
        assertTrue(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_dimPolicyFromResources() {
        mBuilder.setBrightness(0.4f);
        mModifier.apply(mRequest, mBuilder);

        assertEquals(0.4f - MIN_DIM_AMOUNT, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_DIMMED,
                mBuilder.getBrightnessReason().getModifier());
        assertFalse(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_dimPolicyFromConfig() {
        mModifier.apply(mRequest, mBuilder);

        assertEquals(DIM_CONFIG, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_DIMMED,
                mBuilder.getBrightnessReason().getModifier());
        assertFalse(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_dimPolicyAndDimPolicyAlreadyApplied() {
        mModifier.apply(mRequest, mBuilder);
        DisplayBrightnessState.Builder builder = prepareBuilder();

        mModifier.apply(mRequest, builder);

        assertEquals(DIM_CONFIG, builder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_DIMMED,
                builder.getBrightnessReason().getModifier());
        assertTrue(builder.isSlowChange());
    }

    @Test
    public void testApply_dimPolicyAndMinBrightness() {
        mBuilder.setBrightness(0.0f);
        mModifier.apply(mRequest, mBuilder);

        assertEquals(0.0f, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(0, mBuilder.getBrightnessReason().getModifier());
        assertFalse(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_dimPolicyOffAfterDimPolicyOn() {
        mModifier.apply(mRequest, mBuilder);
        mRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
        DisplayBrightnessState.Builder builder = prepareBuilder();

        mModifier.apply(mRequest, builder);

        assertEquals(DEFAULT_BRIGHTNESS, builder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(0, builder.getBrightnessReason().getModifier());
        assertFalse(builder.isSlowChange());
    }

    private DisplayBrightnessState.Builder prepareBuilder() {
        DisplayBrightnessState.Builder builder = DisplayBrightnessState.builder();
        builder.setBrightness(DEFAULT_BRIGHTNESS);
        builder.setIsSlowChange(true);
        return builder;
    }
}
