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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class VibratorInfoTest {
    private static final float TEST_TOLERANCE = 1e-5f;

    private static final int TEST_VIBRATOR_ID = 1;
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
    public void testHasFrequencyControl() {
        VibratorInfo noCapabilities = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        assertFalse(noCapabilities.hasFrequencyControl());
        VibratorInfo composeAndFrequencyControl = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(
                        IVibrator.CAP_FREQUENCY_CONTROL | IVibrator.CAP_COMPOSE_PWLE_EFFECTS)
                .build();
        assertTrue(composeAndFrequencyControl.hasFrequencyControl());
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
    public void testAreEnvelopeEffectsSupported() {
        VibratorInfo noCapabilities = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        assertFalse(noCapabilities.areEnvelopeEffectsSupported());
        VibratorInfo envelopeEffectCapability = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2)
                .build();
        assertTrue(envelopeEffectCapability.areEnvelopeEffectsSupported());
    }

    @Test
    public void testEnvelopeEffectLimits() {
        VibratorInfo info = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setMaxEnvelopeEffectSize(16)
                .setMinEnvelopeEffectControlPointDurationMillis(20)
                .setMaxEnvelopeEffectControlPointDurationMillis(1_000)
                .build();
        assertEquals(16, info.getMaxEnvelopeEffectSize());
        assertEquals(20, info.getMinEnvelopeEffectControlPointDurationMillis());
        assertEquals(1_000, info.getMaxEnvelopeEffectControlPointDurationMillis());
        assertEquals(16_000, info.getMaxEnvelopeEffectDurationMillis());

        VibratorInfo emptyInfo = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        assertEquals(0, emptyInfo.getMaxEnvelopeEffectSize());
        assertEquals(0, emptyInfo.getMinEnvelopeEffectControlPointDurationMillis());
        assertEquals(0, emptyInfo.getMaxEnvelopeEffectControlPointDurationMillis());
        assertEquals(0, emptyInfo.getMaxEnvelopeEffectDurationMillis());
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
    public void testGetFrequencyProfile_unsetProfileIsEmpty() {
        assertTrue(
                new VibratorInfo.Builder(TEST_VIBRATOR_ID).build().getFrequencyProfile().isEmpty());
    }

    @Test
    public void testFrequencyProfile_invalidValuesCreatesEmptyProfile() {
        // Invalid, contains NaN values or empty array.
        assertTrue(new VibratorInfo.FrequencyProfile(
                Float.NaN, 50, 25, TEST_AMPLITUDE_MAP).isEmpty());
        assertTrue(new VibratorInfo.FrequencyProfile(
                150, Float.NaN, 25, TEST_AMPLITUDE_MAP).isEmpty());
        assertTrue(new VibratorInfo.FrequencyProfile(
                150, 50, Float.NaN, TEST_AMPLITUDE_MAP).isEmpty());
        assertTrue(new VibratorInfo.FrequencyProfile(150, 50, 25, null).isEmpty());
        // Invalid, contains zero or negative frequency values.
        assertTrue(new VibratorInfo.FrequencyProfile(-1, 50, 25, TEST_AMPLITUDE_MAP).isEmpty());
        assertTrue(new VibratorInfo.FrequencyProfile(150, 0, 25, TEST_AMPLITUDE_MAP).isEmpty());
        assertTrue(new VibratorInfo.FrequencyProfile(150, 50, -2, TEST_AMPLITUDE_MAP).isEmpty());
        // Invalid max amplitude entries.
        assertTrue(new VibratorInfo.FrequencyProfile(
                150, 50, 50, new float[] { -1, 0, 1, 1, 0 }).isEmpty());
        assertTrue(new VibratorInfo.FrequencyProfile(
                150, 50, 50, new float[] { 0, 1, 2, 1, 0 }).isEmpty());
        // Invalid, minFrequency > resonantFrequency
        assertTrue(new VibratorInfo.FrequencyProfile(
                /* resonantFrequencyHz= */ 150, /* minFrequencyHz= */ 250, 25, TEST_AMPLITUDE_MAP)
                .isEmpty());
        // Invalid, maxFrequency < resonantFrequency by changing resolution.
        assertTrue(new VibratorInfo.FrequencyProfile(
                150, 50, /* frequencyResolutionHz= */ 10, TEST_AMPLITUDE_MAP).isEmpty());
    }

    @Test
    public void testGetFrequencyRangeHz_emptyProfileReturnsNull() {
        assertNull(new VibratorInfo.FrequencyProfile(
                Float.NaN, 50, 25, TEST_AMPLITUDE_MAP).getFrequencyRangeHz());
        assertNull(new VibratorInfo.FrequencyProfile(
                150, Float.NaN, 25, TEST_AMPLITUDE_MAP).getFrequencyRangeHz());
        assertNull(new VibratorInfo.FrequencyProfile(
                150, 50, Float.NaN, TEST_AMPLITUDE_MAP).getFrequencyRangeHz());
        assertNull(new VibratorInfo.FrequencyProfile(150, 50, 25, null).getFrequencyRangeHz());
    }

    @Test
    public void testGetFrequencyRangeHz_validProfileReturnsMappedValues() {
        VibratorInfo.FrequencyProfile profile = new VibratorInfo.FrequencyProfile(
                /* resonantFrequencyHz= */ 150,
                /* minFrequencyHz= */ 50,
                /* frequencyResolutionHz= */ 25,
                /* maxAmplitudes= */ new float[]{
                /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f,
                /* 200Hz= */ 0.8f});

        assertEquals(50f, profile.getFrequencyRangeHz().getLower(), TEST_TOLERANCE);
        assertEquals(200f, profile.getFrequencyRangeHz().getUpper(), TEST_TOLERANCE);
    }

    @Test
    public void testGetMaxAmplitude_emptyProfileReturnsAlwaysZero() {
        VibratorInfo.FrequencyProfile profile = EMPTY_FREQUENCY_PROFILE;
        assertEquals(0f, profile.getMaxAmplitude(Float.NaN), TEST_TOLERANCE);
        assertEquals(0f, profile.getMaxAmplitude(100f), TEST_TOLERANCE);
        assertEquals(0f, profile.getMaxAmplitude(200f), TEST_TOLERANCE);

        profile = new VibratorInfo.FrequencyProfile(
                        /* resonantFrequencyHz= */ 150,
                        /* minFrequencyHz= */ Float.NaN,
                        /* frequencyResolutionHz= */ Float.NaN,
                        /* maxAmplitudes= */ null);

        assertEquals(0f, profile.getMaxAmplitude(Float.NaN), TEST_TOLERANCE);
        assertEquals(0f, profile.getMaxAmplitude(100f), TEST_TOLERANCE);
        assertEquals(0f, profile.getMaxAmplitude(150f), TEST_TOLERANCE);
    }

    @Test
    public void testGetMaxAmplitude_validProfileReturnsMappedValues() {
        VibratorInfo.FrequencyProfile profile = new VibratorInfo.FrequencyProfile(
                        /* resonantFrequencyHz= */ 150,
                        /* minFrequencyHz= */ 50,
                        /* frequencyResolutionHz= */ 25,
                        /* maxAmplitudes= */ new float[]{
                                /* 50Hz= */ 0.1f, 0.2f, 0.4f, 0.8f, /* 150Hz= */ 1f, 0.9f,
                                /* 200Hz= */ 0.8f});

        // Values in the max amplitudes array should return exact measurement.
        assertEquals(1f, profile.getMaxAmplitude(150f), TEST_TOLERANCE);
        assertEquals(0.9f, profile.getMaxAmplitude(175f), TEST_TOLERANCE);
        assertEquals(0.8f, profile.getMaxAmplitude(125f), TEST_TOLERANCE);

        // Min and max frequencies should return exact measurement from array.
        assertEquals(0.8f, profile.getMaxAmplitude(200f), TEST_TOLERANCE);
        assertEquals(0.1f, profile.getMaxAmplitude(50f), TEST_TOLERANCE);

        // Values outside [50Hz, 200Hz] just return 0.
        assertEquals(0f, profile.getMaxAmplitude(49f), TEST_TOLERANCE);
        assertEquals(0f, profile.getMaxAmplitude(201f), TEST_TOLERANCE);

        // 145Hz maps to linear value between 125Hz and 150Hz max amplitudes 0.8 and 1.
        assertEquals(0.96f, profile.getMaxAmplitude(145f), TEST_TOLERANCE);
        // 185Hz maps to linear value between 175Hz and 200Hz max amplitudes 0.9 and 0.8.
        assertEquals(0.86f, profile.getMaxAmplitude(185f), TEST_TOLERANCE);
    }

    @Test
    public void testEquals() {
        VibratorInfo.Builder completeBuilder = new VibratorInfo.Builder(TEST_VIBRATOR_ID);
        // Create a builder with a different ID, but same properties the same as the first one.
        VibratorInfo.Builder completeBuilder2 = new VibratorInfo.Builder(TEST_VIBRATOR_ID + 2);

        for (VibratorInfo.Builder builder :
                new VibratorInfo.Builder[]{completeBuilder, completeBuilder2}) {
            builder.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                    .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                    .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                    .setPrimitiveDelayMax(100)
                    .setCompositionSizeMax(10)
                    .setSupportedBraking(Braking.CLAB)
                    .setPwlePrimitiveDurationMax(50)
                    .setPwleSizeMax(20)
                    .setQFactor(2f)
                    .setFrequencyProfile(TEST_FREQUENCY_PROFILE)
                    .setMaxEnvelopeEffectSize(16)
                    .setMinEnvelopeEffectControlPointDurationMillis(20)
                    .setMaxEnvelopeEffectControlPointDurationMillis(1_000);
        }
        VibratorInfo complete = completeBuilder.build();

        assertEquals(complete, complete);
        assertTrue(complete.equalContent(complete));
        assertEquals(complete, completeBuilder.build());
        assertTrue(complete.equalContent(completeBuilder.build()));
        assertEquals(complete.hashCode(), completeBuilder.build().hashCode());

        // The infos from the two builders should have equal content, but should not be equal due to
        // their different IDs.
        assertNotEquals(complete, completeBuilder2.build());
        assertTrue(complete.equalContent(completeBuilder2.build()));

        VibratorInfo completeWithComposeControl = completeBuilder
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertNotEquals(complete, completeWithComposeControl);
        assertFalse(complete.equalContent(completeWithComposeControl));

        VibratorInfo completeWithNoEffects = completeBuilder
                .setSupportedEffects(new int[0])
                .build();
        assertNotEquals(complete, completeWithNoEffects);
        assertFalse(complete.equalContent(completeWithNoEffects));

        VibratorInfo completeWithUnknownEffects = completeBuilder
                .setSupportedEffects(null)
                .build();
        assertNotEquals(complete, completeWithUnknownEffects);
        assertFalse(complete.equalContent(completeWithUnknownEffects));

        VibratorInfo completeWithDifferentPrimitiveDuration = completeBuilder
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        assertNotEquals(complete, completeWithDifferentPrimitiveDuration);
        assertFalse(complete.equalContent(completeWithDifferentPrimitiveDuration));

        VibratorInfo completeWithDifferentFrequencyProfile = completeBuilder
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(
                        TEST_RESONANT_FREQUENCY + 20,
                        TEST_MIN_FREQUENCY + 10,
                        TEST_FREQUENCY_RESOLUTION + 5,
                        TEST_AMPLITUDE_MAP))
                .build();
        assertNotEquals(complete, completeWithDifferentFrequencyProfile);
        assertFalse(complete.equalContent(completeWithDifferentFrequencyProfile));

        VibratorInfo completeWithEmptyFrequencyProfile = completeBuilder
                .setFrequencyProfile(EMPTY_FREQUENCY_PROFILE)
                .build();
        assertNotEquals(complete, completeWithEmptyFrequencyProfile);
        assertFalse(complete.equalContent(completeWithEmptyFrequencyProfile));

        VibratorInfo completeWithUnknownQFactor = completeBuilder.setQFactor(Float.NaN).build();
        assertNotEquals(complete, completeWithUnknownQFactor);
        assertFalse(complete.equalContent(completeWithUnknownQFactor));

        VibratorInfo completeWithDifferentQFactor = completeBuilder
                .setQFactor(complete.getQFactor() + 3f)
                .build();
        assertNotEquals(complete, completeWithDifferentQFactor);
        assertFalse(complete.equalContent(completeWithDifferentQFactor));

        VibratorInfo unknownEffectSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        VibratorInfo knownEmptyEffectSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setSupportedEffects(new int[0])
                .build();
        assertNotEquals(unknownEffectSupport, knownEmptyEffectSupport);
        assertFalse(unknownEffectSupport.equalContent(knownEmptyEffectSupport));

        VibratorInfo unknownBrakingSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID).build();
        VibratorInfo knownEmptyBrakingSupport = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setSupportedBraking(new int[0])
                .build();
        assertNotEquals(unknownBrakingSupport, knownEmptyBrakingSupport);
        assertFalse(unknownBrakingSupport.equalContent(knownEmptyBrakingSupport));
    }

    @Test
    public void testParceling() {
        VibratorInfo original = new VibratorInfo.Builder(TEST_VIBRATOR_ID)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .setQFactor(Float.NaN)
                .setFrequencyProfile(TEST_FREQUENCY_PROFILE)
                .build();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VibratorInfo restored = VibratorInfo.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
    }

    @Test
    public void areVibrationFeaturesSupported_noAmplitudeControl_fractionalAmplitudeUnsupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1).build();

        // Have at least one fractional amplitude (amplitude not min (0) or max (255) or DEFAULT).
        assertFalse(info.areVibrationFeaturesSupported(waveformWithAmplitudes(10, 30)));
        assertFalse(info.areVibrationFeaturesSupported(waveformWithAmplitudes(10, 255)));
        assertFalse(info.areVibrationFeaturesSupported(
                VibrationEffect.createOneShot(20, /* amplitude= */ 40)));
    }

    @Test
    public void areVibrationFeaturesSupported_noAmplitudeControl_nonFractionalAmplitudeSupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1).build();

        // All amplitudes are min, max, or default. Requires no amplitude control.
        assertTrue(info.areVibrationFeaturesSupported(
                waveformWithAmplitudes(255, 0, VibrationEffect.DEFAULT_AMPLITUDE, 255)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createWaveform(
                        /* timings= */ new long[] {1, 2, 3}, /* repeatIndex= */ -1)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createOneShot(20, /* amplitude= */ 255)));
    }

    @Test
    public void areVibrationFeaturesSupported_withAmplitudeControl_allWaveformsSupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                .build();

        // All forms of amplitudes are valid when amplitude control is available.
        assertTrue(info.areVibrationFeaturesSupported(
                waveformWithAmplitudes(255, 0, VibrationEffect.DEFAULT_AMPLITUDE, 255)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createWaveform(
                        /* timings= */ new long[] {1, 2, 3}, /* repeatIndex= */ -1)));
        assertTrue(info.areVibrationFeaturesSupported(waveformWithAmplitudes(10, 30, 50)));
        assertTrue(info.areVibrationFeaturesSupported(
                waveformWithAmplitudes(7, 255, 0, 0, 60)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createOneShot(20, /* amplitude= */ 255)));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.createOneShot(20, /* amplitude= */ 40)));
    }

    @Test
    public void areVibrationFeaturesSupported_compositionsWithSupportedPrimitivesSupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();

        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose()));
        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(
                                VibrationEffect.Composition.PRIMITIVE_CLICK,
                                /* scale= */ 0.2f,
                                /* delay= */ 200)
                        .compose()));
    }

    @Test
    public void areVibrationFeaturesSupported_compositionsWithUnupportedPrimitivesUnsupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();

        assertFalse(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                        .compose()));
        assertFalse(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                        .compose()));
    }

    @Test
    public void areVibrationFeaturesSupported_composedEffects_allComponentsSupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_AMPLITUDE_CONTROL)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .setSupportedEffects(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_POP)
                .build();

        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addEffect(VibrationEffect.createWaveform(
                                /* timings= */ new long[] {1, 2, 3},
                                /* amplitudes= */ new int[] {10, 20, 255},
                                /* repeatIndex= */ -1))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_POP))
                        .compose()));

        info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 10)
                .setSupportedEffects(VibrationEffect.EFFECT_POP, VibrationEffect.EFFECT_CLICK)
                .build();

        assertTrue(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                        .addEffect(VibrationEffect.createWaveform(
                                // These timings are given either 0 or default amplitudes, which
                                // do not require vibrator's amplitude control.
                                /* timings= */ new long[] {1, 2, 3},
                                /* repeatIndex= */ -1))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_POP))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        .compose()));
    }

    @Test
    public void areVibrationFeaturesSupported_composedEffects_someComponentsUnupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_AMPLITUDE_CONTROL)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .setSupportedEffects(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_POP)
                .build();

        // Not supported due to the TICK primitive, which the vibrator has no support for.
        assertFalse(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                        .addEffect(VibrationEffect.createWaveform(
                                /* timings= */ new long[] {1, 2, 3},
                                /* amplitudes= */ new int[] {10, 20, 255},
                                /* repeatIndex= */ -1))
                        .compose()));
        // Not supported due to the THUD effect, which the vibrator has no support for.
        assertFalse(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addEffect(VibrationEffect.createWaveform(
                                /* timings= */ new long[] {1, 2, 3},
                                /* amplitudes= */ new int[] {10, 20, 255},
                                /* repeatIndex= */ -1))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_THUD))
                        .compose()));

        info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 10)
                .setSupportedEffects(VibrationEffect.EFFECT_POP)
                .build();

        // Not supported due to fractional amplitudes (amplitudes not min (0) or max (255) or
        // DEFAULT), because the vibrator has no amplitude control.
        assertFalse(info.areVibrationFeaturesSupported(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                        .addEffect(VibrationEffect.createWaveform(
                                /* timings= */ new long[] {1, 2, 3},
                                /* amplitudes= */ new int[] {10, 20, 255},
                                /* repeatIndex= */ -1))
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_POP))
                        .compose()));
    }

    private static VibrationEffect waveformWithAmplitudes(int...amplitudes) {
        long[] timings = new long[amplitudes.length];
        for (int i = 0; i < timings.length; i++) {
            timings[i] = i * 2; // Arbitrary timings.
        }
        return VibrationEffect.createWaveform(timings, amplitudes, /* repeatIndex= */ -1);
    }
}
