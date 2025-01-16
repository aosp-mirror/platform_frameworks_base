/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PwleSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class SplitPwleSegmentsAdapterTest {
    private static final float TEST_RESONANT_FREQUENCY = 150;
    private static final float[] TEST_FREQUENCIES =
            new float[]{90f, 120f, 150f, 60f, 30f, 210f, 270f, 300f, 240f, 180f};
    private static final float[] TEST_OUTPUT_ACCELERATIONS =
            new float[]{1.2f, 1.8f, 2.4f, 0.6f, 0.1f, 2.2f, 1.0f, 0.5f, 1.9f, 3.0f};

    private static final VibratorInfo.FrequencyProfile TEST_FREQUENCY_PROFILE =
            new VibratorInfo.FrequencyProfile(TEST_RESONANT_FREQUENCY, TEST_FREQUENCIES,
                    TEST_OUTPUT_ACCELERATIONS);

    private SplitPwleSegmentsAdapter mAdapter;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        mAdapter = new SplitPwleSegmentsAdapter();
    }

    @Test
    @DisableFlags(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testSplitPwleSegmentsAdapter_withFeatureFlagDisabled_returnsOriginalSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                //  startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration
                new PwleSegment(0.0f, 1.0f, 300.0f, 300.0f, 5000)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        VibratorInfo vibratorInfo = createVibratorInfo(
                /*maxEnvelopeControlPointDuration=*/1000, TEST_FREQUENCY_PROFILE,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2);

        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ -1))
                .isEqualTo(-1);
        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ 1))
                .isEqualTo(1);

        assertThat(segments).isEqualTo(originalSegments);
    }

    @Test
    @EnableFlags(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testSplitPwleSegmentsAdapter_noPwleCapability_returnsOriginalSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                //  startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration
                new PwleSegment(0.0f, 1.0f, 300.0f, 300.0f, 5000)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        VibratorInfo vibratorInfo = createVibratorInfo(
                /*maxEnvelopeControlPointDuration=*/1000, TEST_FREQUENCY_PROFILE);

        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ -1))
                .isEqualTo(-1);
        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ 1))
                .isEqualTo(1);

        assertThat(segments).isEqualTo(originalSegments);
    }

    @Test
    @EnableFlags(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testSplitPwleSegmentsAdapter_noMaxEnvelopeEffectSize_returnsOriginalSegments() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                //  startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration
                new PwleSegment(0.0f, 1.0f, 300.0f, 300.0f, 5000)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);
        VibratorInfo vibratorInfo = createVibratorInfo(/*maxEnvelopeControlPointDuration=*/ 0,
                TEST_FREQUENCY_PROFILE, IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2);

        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ -1))
                .isEqualTo(-1);
        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ 1))
                .isEqualTo(1);

        assertThat(segments).isEqualTo(originalSegments);
    }

    @Test
    @EnableFlags(Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testSplitPwleSegmentsAdapter_withPwleCapability_adaptSegmentsCorrectly() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                //  startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration
                new PwleSegment(0.0f, 1.0f, 200.0f, 200.0f, 1000),
                new PwleSegment(0.0f, 1.0f, 300.0f, 300.0f, 5000)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                //  startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration
                new PwleSegment(0.0f, 1.0f, 200.0f, 200.0f, 1000),
                new PwleSegment(0.0f, 0.2f, 300.0f, 300.0f, 1000),
                new PwleSegment(0.2f, 0.4f, 300.0f, 300.0f, 1000),
                new PwleSegment(0.4f, 0.6f, 300.0f, 300.0f, 1000),
                new PwleSegment(0.6f, 0.8f, 300.0f, 300.0f, 1000),
                new PwleSegment(0.8f, 1.0f, 300.0f, 300.0f, 1000));
        VibratorInfo vibratorInfo = createVibratorInfo(
                /*maxEnvelopeControlPointDuration=*/1000, TEST_FREQUENCY_PROFILE,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2);

        assertThat(mAdapter.adaptToVibrator(vibratorInfo, segments, /*repeatIndex= */ 0))
                .isEqualTo(0);
        assertThat(segments).isEqualTo(expectedSegments);
    }

    private static VibratorInfo createVibratorInfo(int maxEnvelopeControlPointDuration,
            VibratorInfo.FrequencyProfile frequencyProfile, int... capabilities) {
        return new VibratorInfo.Builder(0)
                .setCapabilities(IntStream.of(capabilities).reduce((a, b) -> a | b).orElse(0))
                .setFrequencyProfile(frequencyProfile)
                .setMaxEnvelopeEffectSize(1)
                .setMaxEnvelopeEffectControlPointDurationMillis(maxEnvelopeControlPointDuration)
                .build();
    }
}
