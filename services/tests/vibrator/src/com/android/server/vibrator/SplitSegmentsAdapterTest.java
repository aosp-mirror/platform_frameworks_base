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

package com.android.server.vibrator;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class SplitSegmentsAdapterTest {
    private static final int PWLE_COMPOSITION_PRIMITIVE_DURATION_MAX = 10;

    private static final float[] TEST_AMPLITUDE_MAP = new float[]{
            /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f, /* 200Hz= */ 0.8f};

    private static final VibratorInfo.FrequencyProfile TEST_FREQUENCY_PROFILE =
            new VibratorInfo.FrequencyProfile(
                    /* resonantFrequencyHz= */ 150f, /* minFrequencyHz= */ 50f,
                    /* frequencyResolutionHz= */ 25f, TEST_AMPLITUDE_MAP);

    private static final VibratorInfo EMPTY_VIBRATOR_INFO = createVibratorInfo();
    private static final VibratorInfo PWLE_VIBRATOR_INFO = createVibratorInfo(
            IVibrator.CAP_COMPOSE_PWLE_EFFECTS);

    private SplitSegmentsAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        mAdapter = new SplitSegmentsAdapter();
    }

    @Test
    public void testStepAndPrebakedAndPrimitiveSegments_returnsOriginalSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 40f, /* duration= */ 100),
                new PrebakedSegment(
                        VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_LIGHT),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertThat(mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, /*repeatIndex= */ -1))
                .isEqualTo(-1);
        assertThat(mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, /*repeatIndex= */ 1))
                .isEqualTo(1);

        assertThat(mAdapter.adaptToVibrator(PWLE_VIBRATOR_INFO, segments, /*repeatIndex= */ -1))
                .isEqualTo(-1);
        assertThat(mAdapter.adaptToVibrator(PWLE_VIBRATOR_INFO, segments, /*repeatIndex= */ 1))
                .isEqualTo(1);

        assertThat(segments).isEqualTo(originalSegments);
    }

    @Test
    public void testRampSegments_noPwleCapabilities_returnsOriginalSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 10, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.2f, /* endAmplitude*/ 0.8f,
                        /* startFrequencyHz= */ 60, /* endFrequencyHz= */ 90, /* duration= */ 10)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertThat(mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, /*repeatIndex= */ -1))
                .isEqualTo(-1);
        assertThat(mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, /*repeatIndex= */ 1))
                .isEqualTo(1);

        assertThat(segments).isEqualTo(originalSegments);
    }

    @Test
    public void testRampSegments_withPwleDurationLimit_splitsLongRampsAndPreserveOtherSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 40f, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 10, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 0, /* endFrequencyHz= */ 50, /* duration= */ 25),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 40f, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude*/ 1,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 20, /* duration= */ 5)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 40f, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 10, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0.32f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 118f, /* duration= */ 8),
                new RampSegment(/* startAmplitude= */ 0.32f, /* endAmplitude= */ 0.64f,
                        /* startFrequencyHz= */ 118f, /* endFrequencyHz= */ 86f,
                        /* duration= */ 8),
                new RampSegment(/* startAmplitude= */ 0.64f, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 86f, /* endFrequencyHz= */ 50f, /* duration= */ 9),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 40f, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude*/ 1,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 20, /* duration= */ 5));

        VibratorInfo vibratorInfo = new VibratorInfo.Builder(0)
                .setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)
                .setPwlePrimitiveDurationMax(10)
                .setFrequencyProfile(TEST_FREQUENCY_PROFILE)
                .build();

        // Update repeat index to skip the ramp splits.
        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ 3))
                .isEqualTo(5);
        assertThat(segments).isEqualTo(expectedSegments);
    }

    private static VibratorInfo createVibratorInfo(int... capabilities) {
        return new VibratorInfo.Builder(0)
                .setCapabilities(IntStream.of(capabilities).reduce((a, b) -> a | b).orElse(0))
                .setFrequencyProfile(TEST_FREQUENCY_PROFILE)
                .setPwlePrimitiveDurationMax(PWLE_COMPOSITION_PRIMITIVE_DURATION_MAX)
                .build();
    }
}
