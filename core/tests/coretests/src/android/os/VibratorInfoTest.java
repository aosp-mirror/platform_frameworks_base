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

    @Test
    public void testHasAmplitudeControl() {
        VibratorInfo noCapabilities = new InfoBuilder().build();
        assertFalse(noCapabilities.hasAmplitudeControl());
        VibratorInfo composeAndAmplitudeControl = new InfoBuilder()
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS
                        | IVibrator.CAP_AMPLITUDE_CONTROL)
                .build();
        assertTrue(composeAndAmplitudeControl.hasAmplitudeControl());
    }

    @Test
    public void testHasCapabilities() {
        VibratorInfo info = new InfoBuilder()
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertTrue(info.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS));
        assertFalse(info.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL));
    }

    @Test
    public void testIsEffectSupported() {
        VibratorInfo noEffects = new InfoBuilder().build();
        VibratorInfo canClick = new InfoBuilder()
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
        VibratorInfo info = new InfoBuilder()
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .build();
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK));

        // Returns false when there is no compose capability.
        info = new InfoBuilder()
                .setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .build();
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testGetDefaultBraking_returnsFirstSupportedBraking() {
        assertEquals(Braking.NONE, new InfoBuilder().build().getDefaultBraking());
        assertEquals(Braking.CLAB,
                new InfoBuilder()
                        .setSupportedBraking(Braking.NONE, Braking.CLAB)
                        .build()
                        .getDefaultBraking());
    }

    @Test
    public void testGetFrequencyRange_invalidFrequencyMappingReturnsEmptyRange() {
        // Invalid, contains NaN values or empty array.
        assertEquals(Range.create(0f, 0f), new InfoBuilder().build().getFrequencyRange());
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        Float.NaN, 150, 25, 50, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRange());
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        50, Float.NaN, 25, 50, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRange());
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        50, 150, Float.NaN, 50, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRange());
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        50, 150, 25, Float.NaN, TEST_AMPLITUDE_MAP))
                .build().getFrequencyRange());
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(50, 150, 25, 50, null))
                .build().getFrequencyRange());
        // Invalid, minFrequency > resonantFrequency
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* minFrequencyHz= */ 250, /* resonantFrequency= */ 150, 25, 50, null))
                .build().getFrequencyRange());
        // Invalid, maxFrequency < resonantFrequency by changing resolution.
        assertEquals(Range.create(0f, 0f), new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        50, 150, /* frequencyResolutionHz= */10, 50, null))
                .build().getFrequencyRange());
    }

    @Test
    public void testGetFrequencyRange_safeRangeLimitedByMaxFrequency() {
        VibratorInfo info = new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* minFrequencyHz= */ 50, /* resonantFrequencyHz= */ 150,
                        /* frequencyResolutionHz= */ 25, /* suggestedSafeRangeHz= */ 200,
                        TEST_AMPLITUDE_MAP))
                .build();

        // Mapping should range from 50Hz = -2 to 200Hz = 1
        // Safe range [-1, 1] = [100Hz, 200Hz] defined by max - resonant = 50Hz
        assertEquals(Range.create(-2f, 1f), info.getFrequencyRange());
    }

    @Test
    public void testGetFrequencyRange_safeRangeLimitedByMinFrequency() {
        VibratorInfo info = new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* minFrequencyHz= */ 50, /* resonantFrequencyHz= */ 150,
                        /* frequencyResolutionHz= */ 50, /* suggestedSafeRangeHz= */ 200,
                        TEST_AMPLITUDE_MAP))
                .build();

        // Mapping should range from 50Hz = -1 to 350Hz = 2
        // Safe range [-1, 1] = [50Hz, 250Hz] defined by resonant - min = 100Hz
        assertEquals(Range.create(-1f, 2f), info.getFrequencyRange());
    }

    @Test
    public void testGetFrequencyRange_validMappingReturnsFullRelativeRange() {
        VibratorInfo info = new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(
                        /* minFrequencyHz= */ 50, /* resonantFrequencyHz= */ 150,
                        /* frequencyResolutionHz= */ 50, /* suggestedSafeRangeHz= */ 100,
                        TEST_AMPLITUDE_MAP))
                .build();

        // Mapping should range from 50Hz = -2 to 350Hz = 4
        // Safe range [-1, 1] = [100Hz, 200Hz] defined by suggested safe range 100Hz
        assertEquals(Range.create(-2f, 4f), info.getFrequencyRange());
    }

    @Test
    public void testAbsoluteFrequency_emptyMappingReturnsNaN() {
        VibratorInfo info = new InfoBuilder().build();
        assertTrue(Float.isNaN(info.getAbsoluteFrequency(-1)));
        assertTrue(Float.isNaN(info.getAbsoluteFrequency(0)));
        assertTrue(Float.isNaN(info.getAbsoluteFrequency(1)));
    }

    @Test
    public void testAbsoluteFrequency_validRangeReturnsOriginalValue() {
        VibratorInfo info = new InfoBuilder().setFrequencyMapping(TEST_FREQUENCY_MAPPING).build();
        assertEquals(TEST_RESONANT_FREQUENCY, info.getAbsoluteFrequency(0), TEST_TOLERANCE);

        // Safe range [-1, 1] = [125Hz, 175Hz] defined by suggested safe range 100Hz
        assertEquals(125, info.getAbsoluteFrequency(-1), TEST_TOLERANCE);
        assertEquals(175, info.getAbsoluteFrequency(1), TEST_TOLERANCE);
        assertEquals(155, info.getAbsoluteFrequency(0.2f), TEST_TOLERANCE);
        assertEquals(140, info.getAbsoluteFrequency(-0.4f), TEST_TOLERANCE);

        // Full range [-4, 2] = [50Hz, 200Hz] defined by min frequency and amplitude mapping size
        assertEquals(50, info.getAbsoluteFrequency(info.getFrequencyRange().getLower()),
                TEST_TOLERANCE);
        assertEquals(200, info.getAbsoluteFrequency(info.getFrequencyRange().getUpper()),
                TEST_TOLERANCE);
    }

    @Test
    public void testGetMaxAmplitude_emptyMappingReturnsOnlyResonantFrequency() {
        VibratorInfo info = new InfoBuilder().build();
        assertEquals(1f, info.getMaxAmplitude(0), TEST_TOLERANCE);
        assertEquals(0f, info.getMaxAmplitude(0.1f), TEST_TOLERANCE);
        assertEquals(0f, info.getMaxAmplitude(-1), TEST_TOLERANCE);
    }

    @Test
    public void testGetMaxAmplitude_validMappingReturnsMappedValues() {
        VibratorInfo info = new InfoBuilder()
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(/* minFrequencyHz= */ 50,
                        /* resonantFrequencyHz= */ 150, /* frequencyResolutionHz= */ 25,
                        /* suggestedSafeRangeHz= */ 50, TEST_AMPLITUDE_MAP))
                .build();

        assertEquals(1f, info.getMaxAmplitude(0), TEST_TOLERANCE); // 150Hz
        assertEquals(0.9f, info.getMaxAmplitude(1), TEST_TOLERANCE); // 175Hz
        assertEquals(0.8f, info.getMaxAmplitude(-1), TEST_TOLERANCE); // 125Hz
        assertEquals(0.8f, info.getMaxAmplitude(info.getFrequencyRange().getUpper()),
                TEST_TOLERANCE); // 200Hz
        assertEquals(0.1f, info.getMaxAmplitude(info.getFrequencyRange().getLower()),
                TEST_TOLERANCE); // 50Hz

        // Rounds 145Hz to the max amplitude for 125Hz, which is lower.
        assertEquals(0.8f, info.getMaxAmplitude(-0.1f), TEST_TOLERANCE); // 145Hz
        // Rounds 185Hz to the max amplitude for 200Hz, which is lower.
        assertEquals(0.8f, info.getMaxAmplitude(1.2f), TEST_TOLERANCE); // 185Hz
    }

    @Test
    public void testEquals() {
        InfoBuilder completeBuilder = new InfoBuilder()
                .setId(1)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .setQFactor(2f)
                .setFrequencyMapping(TEST_FREQUENCY_MAPPING);
        VibratorInfo complete = completeBuilder.build();

        assertEquals(complete, complete);
        assertEquals(complete, completeBuilder.build());

        VibratorInfo completeWithComposeControl = completeBuilder
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertNotEquals(complete, completeWithComposeControl);

        VibratorInfo completeWithNoEffects = completeBuilder
                .setSupportedEffects()
                .setSupportedPrimitives()
                .build();
        assertNotEquals(complete, completeWithNoEffects);

        VibratorInfo completeWithUnknownEffects = completeBuilder
                .setSupportedEffects(null)
                .build();
        assertNotEquals(complete, completeWithUnknownEffects);

        VibratorInfo completeWithUnknownPrimitives = completeBuilder
                .setSupportedPrimitives(null)
                .build();
        assertNotEquals(complete, completeWithUnknownPrimitives);

        VibratorInfo completeWithDifferentFrequencyMapping = completeBuilder
                .setFrequencyMapping(new VibratorInfo.FrequencyMapping(TEST_MIN_FREQUENCY + 10,
                        TEST_RESONANT_FREQUENCY + 20, TEST_FREQUENCY_RESOLUTION + 5,
                        /* suggestedSafeRangeHz= */ 100, TEST_AMPLITUDE_MAP))
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

        VibratorInfo empty = new InfoBuilder().setId(1).build();
        VibratorInfo emptyWithKnownSupport = new InfoBuilder()
                .setId(1)
                .setSupportedEffects()
                .setSupportedPrimitives()
                .build();
        assertNotEquals(empty, emptyWithKnownSupport);
    }

    @Test
    public void testParceling() {
        VibratorInfo original = new InfoBuilder()
                .setId(1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitives(null)
                .setQFactor(Float.NaN)
                .setFrequencyMapping(TEST_FREQUENCY_MAPPING)
                .build();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VibratorInfo restored = VibratorInfo.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    private static class InfoBuilder {
        private int mId = 0;
        private int mCapabilities = 0;
        private int[] mSupportedEffects = null;
        private int[] mSupportedBraking = null;
        private int[] mSupportedPrimitives = null;
        private float mQFactor = Float.NaN;
        private VibratorInfo.FrequencyMapping mFrequencyMapping = EMPTY_FREQUENCY_MAPPING;

        public InfoBuilder setId(int id) {
            mId = id;
            return this;
        }

        public InfoBuilder setCapabilities(int capabilities) {
            mCapabilities = capabilities;
            return this;
        }

        public InfoBuilder setSupportedEffects(int... supportedEffects) {
            mSupportedEffects = supportedEffects;
            return this;
        }

        public InfoBuilder setSupportedBraking(int... supportedBraking) {
            mSupportedBraking = supportedBraking;
            return this;
        }

        public InfoBuilder setSupportedPrimitives(int... supportedPrimitives) {
            mSupportedPrimitives = supportedPrimitives;
            return this;
        }

        public InfoBuilder setQFactor(float qFactor) {
            mQFactor = qFactor;
            return this;
        }

        public InfoBuilder setFrequencyMapping(VibratorInfo.FrequencyMapping frequencyMapping) {
            mFrequencyMapping = frequencyMapping;
            return this;
        }

        public VibratorInfo build() {
            return new VibratorInfo(mId, mCapabilities, mSupportedEffects, mSupportedBraking,
                    mSupportedPrimitives, mQFactor, mFrequencyMapping);
        }
    }
}
