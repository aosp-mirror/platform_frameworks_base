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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.IVibrator;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.stream.IntStream;

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

    private static final VibratorInfo.FrequencyProfile EMPTY_FREQUENCY_PROFILE =
            new VibratorInfo.FrequencyProfile(Float.NaN, Float.NaN, Float.NaN, null);
    private static final VibratorInfo.FrequencyProfile TEST_FREQUENCY_PROFILE =
            new VibratorInfo.FrequencyProfile(TEST_RESONANT_FREQUENCY, TEST_MIN_FREQUENCY,
                    TEST_FREQUENCY_RESOLUTION, TEST_AMPLITUDE_MAP);

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private PackageManagerInternal mPackageManagerInternalMock;

    private DeviceVibrationEffectAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);

        VibrationSettings vibrationSettings = new VibrationSettings(
                InstrumentationRegistry.getContext(), new Handler(new TestLooper().getLooper()));
        mAdapter = new DeviceVibrationEffectAdapter(vibrationSettings);
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

        assertEquals(effect, mAdapter.apply(effect, createVibratorInfo(EMPTY_FREQUENCY_PROFILE)));
        assertEquals(effect, mAdapter.apply(effect, createVibratorInfo(TEST_FREQUENCY_PROFILE)));
    }

    @Test
    public void testStepAndRampSegments_withoutPwleCapability_convertsRampsToSteps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 200, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 150, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 300, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 0, /* endFrequencyHz= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.65f, /* endAmplitude= */ 0.65f,
                        /* startFrequencyHz= */ 0, /* endFrequencyHz= */ 1, /* duration= */ 1000)),
                /* repeatIndex= */ 3);

        VibrationEffect.Composed adaptedEffect = (VibrationEffect.Composed) mAdapter.apply(effect,
                createVibratorInfo(EMPTY_FREQUENCY_PROFILE));
        assertTrue(adaptedEffect.getSegments().size() > effect.getSegments().size());
        assertTrue(adaptedEffect.getRepeatIndex() >= effect.getRepeatIndex());

        for (VibrationEffectSegment adaptedSegment : adaptedEffect.getSegments()) {
            assertTrue(adaptedSegment instanceof StepSegment);
        }
    }

    @Test
    public void testStepAndRampSegments_withPwleCapability_convertsStepsToRamps() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 175, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 150, /* duration= */ 60),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 50, /* endFrequencyHz= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 1000, /* endFrequencyHz= */ 1, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequencyHz= */ 175, /* endFrequencyHz= */ 175, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 150, /* duration= */ 60),
                new RampSegment(/* startAmplitude= */ 0.1f, /* endAmplitude= */ 0.8f,
                        /* startFrequencyHz= */ 50, /* endFrequencyHz= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.1f,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 50, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibratorInfo info = createVibratorInfo(TEST_FREQUENCY_PROFILE,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(expected, mAdapter.apply(effect, info));
    }

    @Test
    public void testStepAndRampSegments_withEmptyFreqMapping_returnsAmplitudesWithResonantFreq() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 175, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 50, /* endFrequencyHz= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.7f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 1000, /* endFrequencyHz= */ 1, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ Float.NaN, /* endFrequencyHz= */ Float.NaN,
                        /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ Float.NaN, /* endFrequencyHz= */ Float.NaN,
                        /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ Float.NaN, /* endFrequencyHz= */ Float.NaN,
                        /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.7f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ Float.NaN, /* endFrequencyHz= */ Float.NaN,
                        /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibratorInfo info = createVibratorInfo(EMPTY_FREQUENCY_PROFILE,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(expected, mAdapter.apply(effect, info));
    }

    @Test
    public void testStepAndRampSegments_withValidFreqMapping_returnsClippedValues() {
        VibrationEffect.Composed effect = new VibrationEffect.Composed(Arrays.asList(
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 125, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 50, /* endFrequencyHz= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 1000, /* endFrequencyHz= */ 1, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibrationEffect.Composed expected = new VibrationEffect.Composed(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 150,
                        /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.8f,
                        /* startFrequencyHz= */ 125, /* endFrequencyHz= */ 125,
                        /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.1f, /* endAmplitude= */ 0.8f,
                        /* startFrequencyHz= */ 50, /* endFrequencyHz= */ 200, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.1f,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 50, /* duration= */ 20)),
                /* repeatIndex= */ 2);

        VibratorInfo info = createVibratorInfo(TEST_FREQUENCY_PROFILE,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        assertEquals(expected, mAdapter.apply(effect, info));
    }

    private static VibratorInfo createVibratorInfo(VibratorInfo.FrequencyProfile frequencyProfile,
            int... capabilities) {
        int cap = IntStream.of(capabilities).reduce((a, b) -> a | b).orElse(0);
        return new VibratorInfo.Builder(0)
                .setCapabilities(cap)
                .setFrequencyProfile(frequencyProfile)
                .build();
    }
}
