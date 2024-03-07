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

import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.IVibrator;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;

public class DeviceAdapterTest {
    private static final int EMPTY_VIBRATOR_ID = 1;
    private static final int PWLE_VIBRATOR_ID = 2;
    private static final int PWLE_WITHOUT_FREQUENCIES_VIBRATOR_ID = 3;
    private static final float TEST_MIN_FREQUENCY = 50;
    private static final float TEST_RESONANT_FREQUENCY = 150;
    private static final float TEST_FREQUENCY_RESOLUTION = 25;
    private static final float[] TEST_AMPLITUDE_MAP = new float[]{
            /* 50Hz= */ 0.08f, 0.16f, 0.32f, 0.64f, /* 150Hz= */ 0.8f, 0.72f, /* 200Hz= */ 0.64f};

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private PackageManagerInternal mPackageManagerInternalMock;

    private TestLooper mTestLooper;
    private VibrationSettings mVibrationSettings;
    private DeviceAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);

        mTestLooper = new TestLooper();
        mVibrationSettings = new VibrationSettings(
                InstrumentationRegistry.getContext(), new Handler(mTestLooper.getLooper()));

        SparseArray<VibratorController> vibrators = new SparseArray<>();
        vibrators.put(EMPTY_VIBRATOR_ID, createEmptyVibratorController(EMPTY_VIBRATOR_ID));
        vibrators.put(PWLE_VIBRATOR_ID, createPwleVibratorController(PWLE_VIBRATOR_ID));
        vibrators.put(PWLE_WITHOUT_FREQUENCIES_VIBRATOR_ID,
                createPwleWithoutFrequenciesVibratorController(
                        PWLE_WITHOUT_FREQUENCIES_VIBRATOR_ID));
        mAdapter = new DeviceAdapter(mVibrationSettings, vibrators);
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

        assertThat(mAdapter.adaptToVibrator(EMPTY_VIBRATOR_ID, effect)).isEqualTo(effect);
        assertThat(mAdapter.adaptToVibrator(PWLE_VIBRATOR_ID, effect)).isEqualTo(effect);
    }

    @Test
    public void testStepAndRampSegments_withoutPwleCapability_convertsRampsToSteps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(0, 200, 10),
                new StepSegment(0.5f, 150, 100),
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(1, 0.2f, 1, 300, 10),
                new RampSegment(0.8f, 0.2f, 0, 0, 100),
                new RampSegment(0.65f, 0.65f, 0, 1, 1000)),
                /* repeatIndex= */ 3);

        VibrationEffect.Composed adaptedEffect =
                (VibrationEffect.Composed) mAdapter.adaptToVibrator(EMPTY_VIBRATOR_ID, effect);
        assertThat(adaptedEffect.getSegments().size()).isGreaterThan(effect.getSegments().size());
        assertThat(adaptedEffect.getRepeatIndex()).isAtLeast(effect.getRepeatIndex());

        for (VibrationEffectSegment adaptedSegment : adaptedEffect.getSegments()) {
            assertThat(adaptedSegment).isInstanceOf(StepSegment.class);
        }
    }

    @Test
    public void testStepAndRampSegments_withPwleCapability_convertsStepsToRamps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(0, 175, 10),
                new StepSegment(0.5f, 150, 60),
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(1, 1, 50, 200, 50),
                new RampSegment(0.8f, 0.2f, 1000, 1, 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(0, 0, 175, 175, 10),
                new RampSegment(0.5f, 0.5f, 150, 150, 60),
                new RampSegment(0.08f, 0.64f, 50, 200, 50),
                new RampSegment(0.64f, 0.08f, 200, 50, 20)),
                /* repeatIndex= */ 2);

        assertThat(mAdapter.adaptToVibrator(PWLE_VIBRATOR_ID, effect)).isEqualTo(expected);
    }

    @Test
    public void testStepAndRampSegments_withEmptyFreqMapping_returnsAmplitudesWithResonantFreq() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(0, 175, 10),
                new StepSegment(0.5f, 0, 100),
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(0.8f, 1, 50, 200, 50),
                new RampSegment(0.7f, 0.5f, 1000, 1, 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(0, 0, 0, 0, 10),
                new RampSegment(0.5f, 0.5f, 0, 0, 100),
                new RampSegment(0.8f, 1, 0, 0, 50),
                new RampSegment(0.7f, 0.5f, 0, 0, 20)),
                /* repeatIndex= */ 2);

        assertThat(mAdapter.adaptToVibrator(PWLE_WITHOUT_FREQUENCIES_VIBRATOR_ID, effect))
                .isEqualTo(expected);
    }

    @Test
    public void testStepAndRampSegments_withValidFreqMapping_returnsClippedValuesOnlyInRamps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                // Individual step without frequency control, will not use PWLE composition
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(1, 0, 10),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10),
                // Step with frequency control and followed by ramps, will use PWLE composition
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(0.5f, 0, 10),
                new StepSegment(1, 125, 100),
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(1, 1, 50, 200, 50),
                new RampSegment(0.8f, 0.2f, 1000, 1, 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(1, 0, 10),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10),
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(0.5f, 0.5f, 150, 150, 10),
                new RampSegment(0.64f, 0.64f, 125, 125, 100),
                new RampSegment(0.08f, 0.64f, 50, 200, 50),
                new RampSegment(0.64f, 0.08f, 200, 50, 20)),
                /* repeatIndex= */ 2);

        assertThat(mAdapter.adaptToVibrator(PWLE_VIBRATOR_ID, effect)).isEqualTo(expected);
    }

    @Test
    public void testMonoCombinedVibration_returnsSameVibrationWhenEffectsUnchanged() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new PrebakedSegment(
                        VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_LIGHT),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10),
                new PrebakedSegment(
                        VibrationEffect.EFFECT_THUD, true, VibrationEffect.EFFECT_STRENGTH_STRONG),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.5f, 100)),
                /* repeatIndex= */ -1);

        CombinedVibration expected = CombinedVibration.createParallel(effect);

        assertThat(expected.adapt(mAdapter)).isEqualTo(expected);
    }

    @Test
    public void testMonoCombinedVibration_mapsEffectsToAllVibrators() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(1, 175, 10),
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                new RampSegment(1, 1, 50, 200, 50)),
                /* repeatIndex= */ 1);

        CombinedVibration expected = CombinedVibration.startParallel()
                .addVibrator(EMPTY_VIBRATOR_ID, new VibrationEffect.Composed(Arrays.asList(
                        // Step(amplitude, frequencyHz, duration)
                        new StepSegment(1, 175, 10),
                        new StepSegment(1, 0, 50)),
                        /* repeatIndex= */ 1))
                .addVibrator(PWLE_VIBRATOR_ID, new VibrationEffect.Composed(Arrays.asList(
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                        new RampSegment(0.72f, 0.72f, 175, 175, 10),
                        new RampSegment(0.08f, 0.64f, 50, 200, 50)),
                        /* repeatIndex= */ 1))
                .addVibrator(PWLE_WITHOUT_FREQUENCIES_VIBRATOR_ID,
                        new VibrationEffect.Composed(Arrays.asList(
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                                new RampSegment(1, 1, 0, 0, 10),
                                new RampSegment(1, 1, 0, 0, 50)),
                                /* repeatIndex= */ 1))
                .combine();

        assertThat(CombinedVibration.createParallel(effect).adapt(mAdapter)).isEqualTo(expected);
    }

    @Test
    public void testStereoCombinedVibration_adaptMappedEffectsAndLeaveUnmappedOnesUnchanged() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                // Step(amplitude, frequencyHz, duration)
                new StepSegment(1, 175, 10)),
                /* repeatIndex= */ -1);

        int missingVibratorId = 1234;
        CombinedVibration vibration = CombinedVibration.startParallel()
                .addVibrator(missingVibratorId, effect)
                .addVibrator(EMPTY_VIBRATOR_ID, effect)
                .addVibrator(PWLE_VIBRATOR_ID, effect)
                .combine();

        CombinedVibration expected = CombinedVibration.startParallel()
                .addVibrator(missingVibratorId, effect) // unchanged
                .addVibrator(EMPTY_VIBRATOR_ID, new VibrationEffect.Composed(Arrays.asList(
                        // Step(amplitude, frequencyHz, duration)
                        new StepSegment(1, 175, 10)),
                        /* repeatIndex= */ -1))
                .addVibrator(PWLE_VIBRATOR_ID, new VibrationEffect.Composed(Arrays.asList(
                // Ramp(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz, duration)
                        new RampSegment(0.72f, 0.72f, 175, 175, 10)),
                        /* repeatIndex= */ -1))
                .combine();

        assertThat(vibration.adapt(mAdapter)).isEqualTo(expected);
    }

    private VibratorController createEmptyVibratorController(int vibratorId) {
        return new FakeVibratorControllerProvider(mTestLooper.getLooper())
                .newVibratorController(vibratorId, (id, vibrationId)  -> {});
    }

    private VibratorController createPwleWithoutFrequenciesVibratorController(int vibratorId) {
        FakeVibratorControllerProvider provider = new FakeVibratorControllerProvider(
                mTestLooper.getLooper());
        provider.setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        return provider.newVibratorController(vibratorId, (id, vibrationId)  -> {});
    }

    private VibratorController createPwleVibratorController(int vibratorId) {
        FakeVibratorControllerProvider provider = new FakeVibratorControllerProvider(
                mTestLooper.getLooper());
        provider.setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        provider.setResonantFrequency(TEST_RESONANT_FREQUENCY);
        provider.setMinFrequency(TEST_MIN_FREQUENCY);
        provider.setFrequencyResolution(TEST_FREQUENCY_RESOLUTION);
        provider.setMaxAmplitudes(TEST_AMPLITUDE_MAP);
        return provider.newVibratorController(vibratorId, (id, vibrationId)  -> {});
    }
}
