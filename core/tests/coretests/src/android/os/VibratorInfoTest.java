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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.vibrator.Braking;
import android.hardware.vibrator.IVibrator;
import android.platform.test.annotations.Presubmit;
import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Presubmit
@RunWith(JUnit4.class)
public class VibratorInfoTest {
    private static final float TEST_TOLERANCE = 1e-5f;

    private static final int TEST_VIBRATOR_ID = 1;
    private static final float TEST_MIN_FREQUENCY = 50;
    private static final float TEST_RESONANT_FREQUENCY = 150;
    private static final float TEST_FREQUENCY_RESOLUTION = 25;
    private static final float[] TEST_AMPLITUDE_MAP = new float[]{
            /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f, /* 200Hz= */ 0.8f};

    private static final VibratorInfo.FrequencyMapping EMPTY_FREQUENCY_MAPPING =
            new VibratorInfo.FrequencyMapping(Float.NaN, Float.NaN, Float.NaN, null);
    private static final VibratorInfo.FrequencyMapping TEST_FREQUENCY_MAPPING =
            new VibratorInfo.FrequencyMapping(TEST_RESONANT_FREQUENCY, TEST_MIN_FREQUENCY,
                    TEST_FREQUENCY_RESOLUTION, TEST_AMPLITUDE_MAP);

    @Test
    public void testHasAmplitudeControl() {
        VibratorInfo noCapabilities = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        assertFalse(noCapabilities.hasAmplitudeControl());
        VibratorInfo composeAndAmplitudeControl = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_AMPLITUDE_CONTROL)
                .build();
        assertTrue(composeAndAmplitudeControl.hasAmplitudeControl());
    }

    @Test
    public void testHasCapabilities() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertTrue(info.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS));
        assertFalse(info.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL));
    }

    @Test
    public void testIsEffectSupported() {
        VibratorInfo noEffects = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        VibratorInfo canClick = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN,
                noEffects.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                canClick.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                canClick.isEffectSupported(VibrationEffect.EFFECT_TICK));
    }

    @Test
    public void testIsPrimitiveSupported() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK));

        // Returns false when there is no compose capability.
        info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testGetPrimitiveDuration() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .build();
        assertEquals(20, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertEquals(0, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_TICK));
        assertEquals(0, new VibratorInfo.Builder(TEST_VIBRATOR_ID).build()
                .getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_TICK));
    }

    @Test
    public void testCompositionLimits() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setPrimitiveDelayMax(100)
                .setCompositionSizeMax(10)
                .setPwlePrimitiveDurationMax(50)
                .setPwleSizeMax(20)
                .build();
        assertEquals(100, info.getPrimitiveDelayMax());
        assertEquals(10, info.getCompositionSizeMax());
        assertEquals(50, info.getPwlePrimitiveDurationMax());
        assertEquals(20, info.getPwleSizeMax());

        VibratorInfo emptyInfo = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        assertEquals(0, emptyInfo.getPrimitiveDelayMax());
        assertEquals(0, emptyInfo.getCompositionSizeMax());
        assertEquals(0, emptyInfo.getPwlePrimitiveDurationMax());
        assertEquals(0, emptyInfo.getPwleSizeMax());
    }

    @Test
    public void testGetDefaultBraking_returnsFirstSupportedBraking() {
        assertEquals(Braking.NONE, new VibratorInfo.Builder(
                TEST_VIBRATOR_ID).build().getDefaultBraking());
        assertEquals(Braking.CLAB,
                new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                        .setSupportedBraking(Braking.NONE, Braking.CLAB)
                        .build()
                        .getDefaultBraking());
    }

    @Test
    public void testGetFrequencyRangeHz_invalidFrequencyMappingReturnsNull() {
        // Invalid, contains NaN values or empty array.
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID).build().getFrequencyRangeHz());
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        Float.NaN, 50, 25, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRangeHz());
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        150, Float.NaN, 25, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRangeHz());
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        150, 50, Float.NaN, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRangeHz());
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(150, 50, 25, null))
                .build().getFrequencyRangeHz());
        // Invalid, minFrequency > resonantFrequency
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* resonantFrequencyHz= */ 150, /* minFrequencyHz= */ 250, 25, null))
                .build().getFrequencyRangeHz());
        // Invalid, maxFrequency < resonantFrequency by changing resolution.
        assertNull(new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        150, 50, /* frequencyResolutionHz= */ 10, null))
                .build().getFrequencyRangeHz());
    }

    @Test
    public void testGetFrequencyRangeHz_resultRangeDerivedFromHalMapping() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* resonantFrequencyHz= */ 150,
                        /* minFrequencyHz= */ 50,
                        /* frequencyResolutionHz= */ 25,
                        new float[]{
                                /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f,
                                /* 200Hz= */ 0.8f}))
                .build();

        assertEquals(Range.create(50f, 200f), info.getFrequencyRangeHz());
    }

    @Test
    public void testGetMaxAmplitude_emptyMappingReturnsAlwaysZero() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        assertEquals(0f, info.getMaxAmplitude(Float.NaN), TEST_TOLERANCE);
        assertEquals(0f, info.getMaxAmplitude(100f), TEST_TOLERANCE);
        assertEquals(0f, info.getMaxAmplitude(200f), TEST_TOLERANCE);

        info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* resonantFrequencyHz= */ 150,
                        /* minFrequencyHz= */ Float.NaN,
                        /* frequencyResolutionHz= */ Float.NaN,
                        null))
                .build();

        assertEquals(0f, info.getMaxAmplitude(Float.NaN), TEST_TOLERANCE);
        assertEquals(0f, info.getMaxAmplitude(100f), TEST_TOLERANCE);
        assertEquals(0f, info.getMaxAmplitude(150f), TEST_TOLERANCE);
    }

    @Test
    public void testGetMaxAmplitude_validMappingReturnsMappedValues() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* resonantFrequencyHz= */ 150,
                        /* minFrequencyHz= */ 50,
                        /* frequencyResolutionHz= */ 25,
                        new float[]{
                                /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f,
                                /* 200Hz= */ 0.8f}))
                .build();

        assertEquals(1f, info.getMaxAmplitude(150f), TEST_TOLERANCE);
        assertEquals(0.9f, info.getMaxAmplitude(175f), TEST_TOLERANCE);
        assertEquals(0.8f, info.getMaxAmplitude(125f), TEST_TOLERANCE);
        assertEquals(0.8f, info.getMaxAmplitude(info.getFrequencyRangeHz().getUpper()),
                TEST_TOLERANCE); // 200Hz
        assertEquals(0.1f, info.getMaxAmplitude(info.getFrequencyRangeHz().getLower()),
                TEST_TOLERANCE); // 50Hz

        // 145Hz maps to the max amplitude for 125Hz, which is lower.
        assertEquals(0.8f, info.getMaxAmplitude(145f), TEST_TOLERANCE); // 145Hz
        // 185Hz maps to the max amplitude for 200Hz, which is lower.
        assertEquals(0.8f, info.getMaxAmplitude(185f), TEST_TOLERANCE); // 185Hz
    }

    @Test
    public void testEquals() {
        VibratorInfo.Builder completeBuilder = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .setPrimitiveDelayMax(100)
                .setCompositionSizeMax(10)
                .setSupportedBraking(Braking.CLAB)
                .setPwlePrimitiveDurationMax(50)
                .setPwleSizeMax(20)
                .setQFactor(2f)
                .setFrequencyMapping(TEST_FREQUENCY_MAPPING);
        VibratorInfo complete = completeBuilder.build();

        assertEquals(complete, complete);
        assertEquals(complete, completeBuilder.build());
        assertEquals(complete.hashCode(), completeBuilder.build().hashCode());

        VibratorInfo completeWithComposeControl = completeBuilder
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertNotEquals(complete, completeWithComposeControl);

        VibratorInfo completeWithNoEffects = completeBuilder
                .setSupportedEffects(new int[0])
                .build();
        assertNotEquals(complete, completeWithNoEffects);

        VibratorInfo completeWithUnknownEffects = completeBuilder
                .setSupportedEffects(null)
                .build();
        assertNotEquals(complete, completeWithUnknownEffects);

        VibratorInfo completeWithDifferentPrimitiveDuration = completeBuilder
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        assertNotEquals(complete, completeWithDifferentPrimitiveDuration);

        VibratorInfo completeWithDifferentFrequencyMapping = completeBuilder
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        TEST_RESONANT_FREQUENCY + 20,
                        TEST_MIN_FREQUENCY + 10,
                        TEST_FREQUENCY_RESOLUTION + 5,
                        TEST_AMPLITUDE_MAP))
                .build();
        assertNotEquals(complete, completeWithDifferentFrequencyMapping);

        VibratorInfo completeWithEmptyFrequencyMapping = completeBuilder
                .setFrequencyMapping(EMPTY_FREQUENCY_MAPPING)
                .build();
        assertNotEquals(complete, completeWithEmptyFrequencyMapping);

        VibratorInfo completeWithUnknownQFactor = completeBuilder
                .setQFactor(Float.NaN)
                .build();
        assertNotEquals(complete, completeWithUnknownQFactor);

        VibratorInfo completeWithDifferentQFactor = completeBuilder
                .setQFactor(complete.getQFactor() + 3f)
                .build();
        assertNotEquals(complete, completeWithDifferentQFactor);

        VibratorInfo unknownEffectSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        VibratorInfo knownEmptyEffectSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setSupportedEffects(new int[0])
                .build();
        assertNotEquals(unknownEffectSupport, knownEmptyEffectSupport);

        VibratorInfo unknownBrakingSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        VibratorInfo knownEmptyBrakingSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setSupportedBraking(new int[0])
                .build();
        assertNotEquals(unknownBrakingSupport, knownEmptyBrakingSupport);
    }

    @Test
    public void testParceling() {
        VibratorInfo original = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .setQFactor(Float.NaN)
                .setFrequencyMapping(TEST_FREQUENCY_MAPPING)
                .build();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VibratorInfo restored = VibratorInfo.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
    }
}
