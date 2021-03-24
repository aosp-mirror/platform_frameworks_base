/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static org.junit.Assert.assertEquals;

import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

/**
 * Tests for {@link DeviceVibrationEffectAdapter}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:DeviceVibrationEffectAdapterTest
 */
@Presubmit
public class DeviceVibrationEffectAdapterTest {
    private static final float TEST_MIN_FREQUENCY = 50;
    private static final float TEST_RESONANT_FREQUENCY = 150;
    private static final float TEST_FREQUENCY_RESOLUTION = 25;
    private static final float[] TEST_AMPLITUDE_MAP = new float[]{
            /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f, /* 200Hz= */ 0.8f};

    private static final VibratorInfo.FrequencyMapping EMPTY_FREQUENCY_MAPPING =
            new VibratorInfo.FrequencyMapping(Float.NaN, Float.NaN, Float.NaN, Float.NaN, null);
    private static final VibratorInfo.FrequencyMapping TEST_FREQUENCY_MAPPING =
            new VibratorInfo.FrequencyMapping(TEST_MIN_FREQUENCY,
                    TEST_RESONANT_FREQUENCY, TEST_FREQUENCY_RESOLUTION,
                    /* suggestedSafeRangeHz= */ 50, TEST_AMPLITUDE_MAP);

    private DeviceVibrationEffectAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        mAdapter = new DeviceVibrationEffectAdapter();
    }

    @Test
    public void testPrebakedAndPrimitiveSegments_returnsOriginalSegment() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new PrebakedSegment(
                        VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_LIGHT),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10),
                new PrebakedSegment(
                        VibrationEffect.EFFECT_THUD, true, VibrationEffect.EFFECT_STRENGTH_STRONG),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.5f, 100)),
                /* repeatIndex= */ -1);

        assertEquals(effect, mAdapter.apply(effect, createVibratorInfo(EMPTY_FREQUENCY_MAPPING)));
        assertEquals(effect, mAdapter.apply(effect, createVibratorInfo(TEST_FREQUENCY_MAPPING)));
    }

    @Test
    public void testStepAndRampSegments_emptyMapping_returnsSameAmplitudesAndFrequencyZero() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequency= */ 1, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 1,
                        /* startFrequency= */ -1, /* endFrequency= */ 1, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.7f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ 10, /* endFrequency= */ -5, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequency= */ Float.NaN, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ Float.NaN,
                        /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 1,
                        /* startFrequency= */ Float.NaN, /* endFrequency= */ Float.NaN,
                        /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.7f, /* endAmplitude= */ 0.5f,
                        /* startFrequency= */ Float.NaN, /* endFrequency= */ Float.NaN,
                        /* duration= */ 20)),
                /* repeatIndex= */ 2);

        assertEquals(expected, mAdapter.apply(effect, createVibratorInfo(EMPTY_FREQUENCY_MAPPING)));
    }

    @Test
    public void testStepAndRampSegments_nonEmptyMapping_returnsClippedValues() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 1, /* frequency= */ -1, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequency= */ -4, /* endFrequency= */ 2, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequency= */ 10, /* endFrequency= */ -5, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0.5f, /* frequency= */ 150, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.8f, /* frequency= */ 125, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.1f, /* endAmplitude= */ 0.8f,
                        /* startFrequency= */ 50, /* endFrequency= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.1f,
                        /* startFrequency= */ 200, /* endFrequency= */ 50, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        assertEquals(expected, mAdapter.apply(effect, createVibratorInfo(TEST_FREQUENCY_MAPPING)));
    }

    private static VibratorInfo createVibratorInfo(VibratorInfo.FrequencyMapping frequencyMapping) {
        return new VibratorInfo(/* id= */ 0, /* capabilities= */ 0, null, null,
                /* qFactor= */ Float.NaN, frequencyMapping);
    }
}
