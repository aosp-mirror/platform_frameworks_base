/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Tests for {@link StepToRampAdapter}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:StepToRampAdapterTest
 */
@Presubmit
public class StepToRampAdapterTest {
    private static final float[] TEST_AMPLITUDE_MAP = new float[]{
            /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f, /* 200Hz= */ 0.8f};
    private static final VibratorInfo.FrequencyProfile TEST_FREQUENCY_PROFILE =
            new VibratorInfo.FrequencyProfile(
                    /* resonantFrequencyHz= */ 150f, /* minFrequencyHz= */ 50f,
                    /* frequencyResolutionHz= */ 25f, TEST_AMPLITUDE_MAP);

    private StepToRampAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        mAdapter = new StepToRampAdapter();
    }

    @Test
    public void testRampAndPrebakedAndPrimitiveSegments_returnsOriginalSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 40f, /* endFrequencyHz= */ 20f, /* duration= */ 10),
                new PrebakedSegment(
                        VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_LIGHT),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.apply(segments, -1, createVibratorInfo()));
        assertEquals(1, mAdapter.apply(segments, 1, createVibratorInfo()));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testRampSegments_withPwleDurationLimit_splitsLongRamps() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 10, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 0, /* endFrequencyHz= */ 50, /* duration= */ 25),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude*/ 1,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 20, /* duration= */ 5)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 10, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0.32f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 118f, /* duration= */ 8),
                new RampSegment(/* startAmplitude= */ 0.32f, /* endAmplitude= */ 0.64f,
                        /* startFrequencyHz= */ 118f, /* endFrequencyHz= */ 86f,
                        /* duration= */ 8),
                new RampSegment(/* startAmplitude= */ 0.64f, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 86f, /* endFrequencyHz= */ 50f, /* duration= */ 9),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude*/ 1,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 20, /* duration= */ 5));

        VibratorInfo vibratorInfo = new VibratorInfo.Builder(0)
                .setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)
                .setPwlePrimitiveDurationMax(10)
                .setFrequencyProfile(TEST_FREQUENCY_PROFILE)
                .build();

        // Update repeat index to skip the ramp splits.
        assertEquals(4, mAdapter.apply(segments, 2, vibratorInfo));
        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepAndRampSegments_withoutPwleCapability_keepsListUnchanged() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 1, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 50, /* duration= */ 20)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.apply(segments, -1, createVibratorInfo()));
        assertEquals(0, mAdapter.apply(segments, 0, createVibratorInfo()));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testStepAndRampSegments_withPwleCapabilityAndNoFrequency_keepsOriginalSteps() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 100),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 40, /* endFrequencyHz= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 1, /* duration= */ 20)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        VibratorInfo vibratorInfo = createVibratorInfo(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(-1, mAdapter.apply(segments, -1, vibratorInfo));
        assertEquals(3, mAdapter.apply(segments, 3, vibratorInfo));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testStepAndRampSegments_withPwleCapabilityAndStepNextToRamp_convertsStepsToRamps() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 200, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 150, /* duration= */ 60),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 300, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 1000, /* endFrequencyHz= */ 1, /* duration= */ 20),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 10, /* duration= */ 60)));

        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 200, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 150, /* duration= */ 60),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 300, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 1000, /* endFrequencyHz= */ 1, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.8f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 10, /* duration= */ 60));

        VibratorInfo vibratorInfo = createVibratorInfo(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(-1, mAdapter.apply(segments, -1, vibratorInfo));
        assertEquals(2, mAdapter.apply(segments, 2, vibratorInfo));

        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withPwleCapabilityAndFrequency_convertsStepsToRamps() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 100, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 6)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 150, /* duration= */ 6));

        VibratorInfo vibratorInfo = createVibratorInfo(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(-1, mAdapter.apply(segments, -1, vibratorInfo));
        assertEquals(0, mAdapter.apply(segments, 0, vibratorInfo));

        assertEquals(expectedSegments, segments);
    }

    private static VibratorInfo createVibratorInfo(int... capabilities) {
        return new VibratorInfo.Builder(0)
                .setCapabilities(IntStream.of(capabilities).reduce((a, b) -> a | b).orElse(0))
                .setFrequencyProfile(TEST_FREQUENCY_PROFILE)
                .build();
    }
}
