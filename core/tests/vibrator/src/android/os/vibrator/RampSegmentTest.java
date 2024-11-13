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

package android.os.vibrator;

import static android.os.VibrationEffect.DEFAULT_AMPLITUDE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.assertTrue;

import static org.testng.Assert.assertThrows;

import android.hardware.vibrator.IVibrator;
import android.os.Parcel;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RampSegmentTest {
    private static final float TOLERANCE = 1e-2f;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testCreation() {
        RampSegment ramp = new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 200, /* duration= */ 100);

        assertEquals(100L, ramp.getDuration());
        assertEquals(1f, ramp.getStartAmplitude());
        assertEquals(0f, ramp.getEndAmplitude());
        assertEquals(100f, ramp.getStartFrequencyHz());
        assertEquals(200f, ramp.getEndFrequencyHz());
    }

    @Test
    public void testSerialization() {
        RampSegment original = new RampSegment(0, 1, 10, 20.5f, 10);
        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        assertEquals(original, RampSegment.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void testValidate() {
        new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                /* startFrequencyHz= */ 2, /* endFrequencyHz= */ 1, /* duration= */ 100).validate();
        // Zero frequency is still used internally for unset frequency.
        new RampSegment(0, 0, 0, 0, 0).validate();

        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(DEFAULT_AMPLITUDE, 0, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(/* startAmplitude= */ -2, 0, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, /* endAmplitude= */ 2, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, 0, /* startFrequencyHz= */ -1, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, 0, 0, /* endFrequencyHz= */ -3, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, 0, 0, 0, /* duration= */ -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(/* startAmplitude= */ Float.NaN, 0, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, 0, /* startFrequencyHz= */ Float.NaN, 0, 0).validate());
    }

    @Test
    public void testResolve() {
        RampSegment ramp = new RampSegment(0, 1, 0, 0, 0);
        assertSame(ramp, ramp.resolve(100));
    }

    @Test
    public void testApplyEffectStrength_ignoresAndReturnsSameEffect() {
        RampSegment ramp = new RampSegment(1, 0, 1, 0, 0);
        assertSame(ramp, ramp.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG));
    }

    @Test
    @DisableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testScale_withLegacyScaling_halfAndFullAmplitudes() {
        RampSegment initial = new RampSegment(0.5f, 1, 0, 0, 0);

        assertEquals(0.5f, initial.scale(1).getStartAmplitude(), TOLERANCE);
        assertEquals(0.17f, initial.scale(0.5f).getStartAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(0.86f, initial.scale(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.47f, initial.scale(1.5f).scale(2 / 3f).getStartAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.35f, initial.scale(0.8f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scale(0.8f).scale(1.25f).getStartAmplitude(), TOLERANCE);

        assertEquals(1f, initial.scale(1).getEndAmplitude(), TOLERANCE);
        assertEquals(0.34f, initial.scale(0.5f).getEndAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(1f, initial.scale(1.5f).getEndAmplitude(), TOLERANCE);
        assertEquals(0.53f, initial.scale(1.5f).scale(2 / 3f).getEndAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.71f, initial.scale(0.8f).getEndAmplitude(), TOLERANCE);
        assertEquals(0.84f, initial.scale(0.8f).scale(1.25f).getEndAmplitude(), TOLERANCE);
    }

    @Test
    @EnableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testScale_withScalingV2_halfAndFullAmplitudes() {
        RampSegment initial = new RampSegment(0.5f, 1, 0, 0, 0);

        assertEquals(0.5f, initial.scale(1).getStartAmplitude(), TOLERANCE);
        assertEquals(0.25f, initial.scale(0.5f).getStartAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(0.66f, initial.scale(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.44f, initial.scale(1.5f).scale(2 / 3f).getStartAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.4f, initial.scale(0.8f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.48f, initial.scale(0.8f).scale(1.25f).getStartAmplitude(), TOLERANCE);

        assertEquals(1f, initial.scale(1).getEndAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scale(0.5f).getEndAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(1f, initial.scale(1.5f).getEndAmplitude(), TOLERANCE);
        assertEquals(2 / 3f, initial.scale(1.5f).scale(2 / 3f).getEndAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.81f, initial.scale(0.8f).getEndAmplitude(), TOLERANCE);
        assertEquals(0.86f, initial.scale(0.8f).scale(1.25f).getEndAmplitude(), TOLERANCE);
    }

    @Test
    public void testScale_zeroAmplitude() {
        RampSegment initial = new RampSegment(0, 1, 0, 0, 0);

        assertEquals(0f, initial.scale(1).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(0.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(1.5f).scale(2 / 3f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(0.8f).scale(1.25f).getStartAmplitude(), TOLERANCE);
    }

    @Test
    public void testScaleLinearly() {
        RampSegment initial = new RampSegment(0, 1, 0, 0, 0);

        assertEquals(0f, initial.scaleLinearly(1f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scaleLinearly(0.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scaleLinearly(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scaleLinearly(1.5f).scaleLinearly(2 / 3f).getStartAmplitude(),
                TOLERANCE);
        assertEquals(0f, initial.scaleLinearly(0.8f).scaleLinearly(1.25f).getStartAmplitude(),
                TOLERANCE);

        assertEquals(1f, initial.scaleLinearly(1f).getEndAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scaleLinearly(0.5f).getEndAmplitude(), TOLERANCE);
        assertEquals(1f, initial.scaleLinearly(1.5f).getEndAmplitude(), TOLERANCE);
        assertEquals(0.8f, initial.scaleLinearly(0.8f).getEndAmplitude(), TOLERANCE);
        // Restores back to the exact original value since this is a linear scaling.
        assertEquals(0.8f, initial.scaleLinearly(1.5f).scaleLinearly(0.8f).getEndAmplitude(),
                TOLERANCE);

        initial = new RampSegment(0.5f, 1, 0, 0, 0);

        assertEquals(0.5f, initial.scaleLinearly(1).getStartAmplitude(), TOLERANCE);
        assertEquals(0.25f, initial.scaleLinearly(0.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.75f, initial.scaleLinearly(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.4f, initial.scaleLinearly(0.8f).getStartAmplitude(), TOLERANCE);
        // Restores back to the exact original value since this is a linear scaling.
        assertEquals(0.5f, initial.scaleLinearly(0.8f).scaleLinearly(1.25f).getStartAmplitude(),
                TOLERANCE);
    }

    @Test
    public void testDuration() {
        assertEquals(10, new RampSegment(0.5f, 1, 0, 0, 10).getDuration());
    }

    @Test
    public void testVibrationFeaturesSupport_amplitudeAndFrequencyControls_supported() {
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ true);

        // Increasing amplitude
        assertTrue(new RampSegment(0.5f, 1, 0, 0, 10).areVibrationFeaturesSupported(info));
        // Increasing frequency
        assertTrue(new RampSegment(0.5f, 0.5f, 0, 1, 10).areVibrationFeaturesSupported(info));
        // Decreasing amplitude
        assertTrue(new RampSegment(1, 0.5f, 0, 0, 10).areVibrationFeaturesSupported(info));
        // Decreasing frequency
        assertTrue(new RampSegment(0.5f, 0.5f, 1, 0, 10).areVibrationFeaturesSupported(info));
        // Zero duration
        assertTrue(new RampSegment(0.5f, 0.5f, 1, 0, 0).areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_noAmplitudeControl_unsupportedForChangingAmplitude() {
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ false, /* hasFrequencyControl= */ true);

        // Test with increasing/decreasing amplitudes.
        assertFalse(new RampSegment(0.5f, 1, 0, 0, 10).areVibrationFeaturesSupported(info));
        assertFalse(new RampSegment(1, 0.5f, 0, 0, 10).areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_noAmplitudeControl_fractionalAmplitudeUnsupported() {
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ false, /* hasFrequencyControl= */ true);

        assertFalse(new RampSegment(0.2f, 0.2f, 0, 0, 10).areVibrationFeaturesSupported(info));
        assertFalse(new RampSegment(0, 0.2f, 0, 0, 10).areVibrationFeaturesSupported(info));
        assertFalse(new RampSegment(0.2f, 0, 0, 0, 10).areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_unchangingZeroAmplitude_supported() {
        RampSegment amplitudeZeroWithIncreasingFrequency = new RampSegment(1, 1, 0.5f, 0.8f, 10);
        RampSegment amplitudeZeroWithDecreasingFrequency = new RampSegment(1, 1, 0.8f, 0.5f, 10);
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ false, /* hasFrequencyControl= */ true);

        assertTrue(amplitudeZeroWithIncreasingFrequency.areVibrationFeaturesSupported(info));
        assertTrue(amplitudeZeroWithDecreasingFrequency.areVibrationFeaturesSupported(info));

        info = createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ true);

        assertTrue(amplitudeZeroWithIncreasingFrequency.areVibrationFeaturesSupported(info));
        assertTrue(amplitudeZeroWithDecreasingFrequency.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_unchangingOneAmplitude_supported() {
        RampSegment amplitudeOneWithIncreasingFrequency = new RampSegment(1, 1, 0.5f, 0.8f, 10);
        RampSegment amplitudeOneWithDecreasingFrequency = new RampSegment(1, 1, 0.8f, 0.5f, 10);
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ false, /* hasFrequencyControl= */ true);

        assertTrue(amplitudeOneWithIncreasingFrequency.areVibrationFeaturesSupported(info));
        assertTrue(amplitudeOneWithDecreasingFrequency.areVibrationFeaturesSupported(info));

        info = createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ true);

        assertTrue(amplitudeOneWithIncreasingFrequency.areVibrationFeaturesSupported(info));
        assertTrue(amplitudeOneWithDecreasingFrequency.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_unchangingDefaultAmplitude_supported() {
        RampSegment defaultAmplitudeIncreasingFrequency =
                new RampSegment(DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE, 0.5f, 0.8f, 10);
        RampSegment defaultAmplitudeDecreasingFrequency =
                new RampSegment(DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE, 0.8f, 0.5f, 10);
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ false, /* hasFrequencyControl= */ true);

        assertTrue(defaultAmplitudeIncreasingFrequency.areVibrationFeaturesSupported(info));
        assertTrue(defaultAmplitudeDecreasingFrequency.areVibrationFeaturesSupported(info));

        info = createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ true);

        assertTrue(defaultAmplitudeIncreasingFrequency.areVibrationFeaturesSupported(info));
        assertTrue(defaultAmplitudeDecreasingFrequency.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_noFrequencyControl_unsupportedForChangingFrequency() {
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ false);

        // Test with increasing/decreasing frequencies.
        assertFalse(new RampSegment(0, 0, 0.2f, 0.4f, 10).areVibrationFeaturesSupported(info));
        assertFalse(new RampSegment(0, 0, 0.4f, 0.2f, 10).areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_noFrequencyControl_fractionalFrequencyUnsupported() {
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ false);

        assertFalse(new RampSegment(0, 0, 0.2f, 0.2f, 10).areVibrationFeaturesSupported(info));
        assertFalse(new RampSegment(0, 0, 0.2f, 0, 10).areVibrationFeaturesSupported(info));
        assertFalse(new RampSegment(0, 0, 0, 0.2f, 10).areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_unchangingZeroFrequency_supported() {
        RampSegment frequencyZeroWithIncreasingAmplitude = new RampSegment(0.1f, 1, 0, 0, 10);
        RampSegment frequencyZeroWithDecreasingAmplitude = new RampSegment(1, 0.1f, 0, 0, 10);
        VibratorInfo info =
                createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ false);

        assertTrue(frequencyZeroWithIncreasingAmplitude.areVibrationFeaturesSupported(info));
        assertTrue(frequencyZeroWithDecreasingAmplitude.areVibrationFeaturesSupported(info));

        info = createVibInfo(/* hasAmplitudeControl= */ true, /* hasFrequencyControl= */ true);

        assertTrue(frequencyZeroWithIncreasingAmplitude.areVibrationFeaturesSupported(info));
        assertTrue(frequencyZeroWithDecreasingAmplitude.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testIsHapticFeedbackCandidate_returnsTrue() {
        // A single ramp segment duration is not checked here, but contributes to the effect known
        // duration checked in VibrationEffect implementations.
        assertTrue(new RampSegment(0.5f, 1, 0, 0, 5_000).isHapticFeedbackCandidate());
    }

    private static VibratorInfo createVibInfo(
            boolean hasAmplitudeControl, boolean hasFrequencyControl) {
        VibratorInfo.Builder builder = new VibratorInfo.Builder(/* id= */ 1);
        long capabilities = 0;
        if (hasAmplitudeControl) {
            capabilities |= IVibrator.CAP_AMPLITUDE_CONTROL;
        }
        if (hasFrequencyControl) {
            capabilities |= (IVibrator.CAP_FREQUENCY_CONTROL | IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        }
        return builder.setCapabilities(capabilities).build();
    }
}
