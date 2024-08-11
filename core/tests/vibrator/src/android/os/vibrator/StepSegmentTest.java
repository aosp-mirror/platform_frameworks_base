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
public class StepSegmentTest {
    private static final float TOLERANCE = 1e-2f;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testCreation() {
        StepSegment step = new StepSegment(/* amplitude= */ 1f, /* frequencyHz= */ 1f,
                /* duration= */ 100);

        assertEquals(100, step.getDuration());
        assertEquals(1f, step.getAmplitude());
        assertEquals(1f, step.getFrequencyHz());
    }

    @Test
    public void testSerialization() {
        StepSegment original = new StepSegment(0.5f, 1f, 10);
        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        assertEquals(original, StepSegment.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void testValidate() {
        new StepSegment(/* amplitude= */ 0f, /* frequencyHz= */ 10f, /* duration= */ 10).validate();
        // Zero frequency is still used internally for unset frequency.
        new StepSegment(0, 0, 0).validate();

        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(/* amplitude= */ -2, 1f, 10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(/* amplitude= */ 2, 1f, 10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(1, /* frequencyHz*/ -1f, 10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(2, 1f, /* duration= */ -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(/* amplitude= */ Float.NaN, 1f, 10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(1, /* frequencyHz*/ Float.NaN, 10).validate());
    }

    @Test
    public void testResolve() {
        StepSegment original = new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE, 0, 0);
        assertEquals(1f, original.resolve(VibrationEffect.MAX_AMPLITUDE).getAmplitude());
        assertEquals(0.2f, original.resolve(51).getAmplitude(), TOLERANCE);

        StepSegment resolved = new StepSegment(0, 0, 0);
        assertSame(resolved, resolved.resolve(100));

        assertThrows(IllegalArgumentException.class, () -> resolved.resolve(1000));
    }

    @Test
    public void testApplyEffectStrength_ignoresAndReturnsSameEffect() {
        StepSegment step = new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE, 0, 0);
        assertSame(step, step.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG));
    }

    @Test
    @DisableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testScale_withLegacyScaling_fullAmplitude() {
        StepSegment initial = new StepSegment(1f, 0, 0);

        assertEquals(1f, initial.scale(1).getAmplitude(), TOLERANCE);
        assertEquals(0.34f, initial.scale(0.5f).getAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(1f, initial.scale(1.5f).getAmplitude(), TOLERANCE);
        assertEquals(0.53f, initial.scale(1.5f).scale(2 / 3f).getAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.71f, initial.scale(0.8f).getAmplitude(), TOLERANCE);
        assertEquals(0.84f, initial.scale(0.8f).scale(1.25f).getAmplitude(), TOLERANCE);
    }

    @Test
    @EnableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testScale_withScalingV2_fullAmplitude() {
        StepSegment initial = new StepSegment(1f, 0, 0);

        assertEquals(1f, initial.scale(1).getAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scale(0.5f).getAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(1f, initial.scale(1.5f).getAmplitude(), TOLERANCE);
        assertEquals(2 / 3f, initial.scale(1.5f).scale(2 / 3f).getAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.8f, initial.scale(0.8f).getAmplitude(), TOLERANCE);
        assertEquals(0.86f, initial.scale(0.8f).scale(1.25f).getAmplitude(), TOLERANCE);
    }

    @Test
    @DisableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testScale_withLegacyScaling_halfAmplitude() {
        StepSegment initial = new StepSegment(0.5f, 0, 0);

        assertEquals(0.5f, initial.scale(1).getAmplitude(), TOLERANCE);
        assertEquals(0.17f, initial.scale(0.5f).getAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(0.86f, initial.scale(1.5f).getAmplitude(), TOLERANCE);
        assertEquals(0.47f, initial.scale(1.5f).scale(2 / 3f).getAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.35f, initial.scale(0.8f).getAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scale(0.8f).scale(1.25f).getAmplitude(), TOLERANCE);
    }

    @Test
    @EnableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testScale_withScalingV2_halfAmplitude() {
        StepSegment initial = new StepSegment(0.5f, 0, 0);

        assertEquals(0.5f, initial.scale(1).getAmplitude(), TOLERANCE);
        assertEquals(0.25f, initial.scale(0.5f).getAmplitude(), TOLERANCE);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(0.66f, initial.scale(1.5f).getAmplitude(), TOLERANCE);
        assertEquals(0.44f, initial.scale(1.5f).scale(2 / 3f).getAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.4f, initial.scale(0.8f).getAmplitude(), TOLERANCE);
        assertEquals(0.48f, initial.scale(0.8f).scale(1.25f).getAmplitude(), TOLERANCE);
    }

    @Test
    public void testScale_zeroAmplitude() {
        StepSegment initial = new StepSegment(0, 0, 0);

        assertEquals(0f, initial.scale(1).getAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(0.5f).getAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(1.5f).getAmplitude(), TOLERANCE);
    }

    @Test
    public void testScale_defaultAmplitude() {
        StepSegment initial = new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE, 0, 0);

        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, initial.scale(1).getAmplitude(), TOLERANCE);
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, initial.scale(0.5f).getAmplitude(),
                TOLERANCE);
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, initial.scale(1.5f).getAmplitude(),
                TOLERANCE);
    }

    @Test
    public void testScaleLinearly_fullAmplitude() {
        StepSegment initial = new StepSegment(1f, 0, 0);

        assertEquals(1f, initial.scaleLinearly(1).getAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scaleLinearly(0.5f).getAmplitude(), TOLERANCE);
        assertEquals(1f, initial.scaleLinearly(1.5f).getAmplitude(), TOLERANCE);
        assertEquals(0.8f, initial.scaleLinearly(0.8f).getAmplitude(), TOLERANCE);
        // Restores back to the exact original value since this is a linear scaling.
        assertEquals(1f, initial.scaleLinearly(0.8f).scaleLinearly(1.25f).getAmplitude(),
                TOLERANCE);

        initial = new StepSegment(0, 0, 0);

        assertEquals(0f, initial.scaleLinearly(1).getAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scaleLinearly(0.5f).getAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scaleLinearly(1.5f).getAmplitude(), TOLERANCE);
    }

    @Test
    public void testScaleLinearly_defaultAmplitude() {
        StepSegment initial = new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE, 0, 0);

        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, initial.scaleLinearly(1).getAmplitude(),
                TOLERANCE);
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, initial.scaleLinearly(0.5f).getAmplitude(),
                TOLERANCE);
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, initial.scaleLinearly(1.5f).getAmplitude(),
                TOLERANCE);
    }

    @Test
    public void testDuration() {
        assertEquals(5, new StepSegment(0, 0, 5).getDuration());
    }

    @Test
    public void testVibrationFeaturesSupport_zeroAmplitude_supported() {
        StepSegment segment =
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 0);
        VibratorInfo info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ true);

        assertTrue(segment.areVibrationFeaturesSupported(info));

        info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ false);

        assertTrue(segment.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_maxAmplitude_supported() {
        StepSegment segment =
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 0);
        VibratorInfo info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ true);

        assertTrue(segment.areVibrationFeaturesSupported(info));

        info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ false);

        assertTrue(segment.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_defaultAmplitude_supported() {
        StepSegment segment =
                new StepSegment(
                        /* amplitude= */ VibrationEffect.DEFAULT_AMPLITUDE,
                        /* frequencyHz= */ 0,
                        /* duration= */ 0);
        VibratorInfo info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ true);

        assertTrue(segment.areVibrationFeaturesSupported(info));

        info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ false);

        assertTrue(segment.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_fractionalAmplitude_hasAmplitudeCtrl_supported() {
        VibratorInfo info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ true);

        assertTrue(new StepSegment(/* amplitude= */ 0.2f, /* frequencyHz= */ 0, /* duration= */ 0)
                .areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_fractionalAmplitude_hasNoAmplitudeCtrl_notSupported() {
        VibratorInfo info = createVibInfoForAmplitude(/* hasAmplitudeControl= */ false);

        assertFalse(new StepSegment(/* amplitude= */ 0.2f, /* frequencyHz= */ 0, /* duration= */ 0)
                .areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_zeroFrequency_supported() {
        StepSegment segment =
                new StepSegment(/* amplitude= */ 0f, /* frequencyHz= */ 0, /* duration= */ 0);
        VibratorInfo info = createVibInfoForFrequency(/* hasFrequencyControl= */ false);

        assertTrue(segment.areVibrationFeaturesSupported(info));

        info = createVibInfoForFrequency(/* hasFrequencyControl= */ true);

        assertTrue(segment.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_nonZeroFrequency_hasFrequencyCtrl_supported() {
        StepSegment segment =
                new StepSegment(/* amplitude= */ 0f, /* frequencyHz= */ 0.2f, /* duration= */ 0);
        VibratorInfo info = createVibInfoForFrequency(/* hasFrequencyControl= */ true);

        assertTrue(segment.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testVibrationFeaturesSupport_nonZeroFrequency_hasNoFrequencyCtrl_notSupported() {
        StepSegment segment =
                new StepSegment(/* amplitude= */ 0f, /* frequencyHz= */ 0.2f, /* duration= */ 0);
        VibratorInfo info = createVibInfoForFrequency(/* hasFrequencyControl= */ false);

        assertFalse(segment.areVibrationFeaturesSupported(info));
    }

    @Test
    public void testIsHapticFeedbackCandidate_returnsTrue() {
        // A single step segment duration is not checked here, but contributes to the effect known
        // duration checked in VibrationEffect implementations.
        assertTrue(new StepSegment(0, 0, 5_000).isHapticFeedbackCandidate());
    }

    private static VibratorInfo createVibInfoForAmplitude(boolean hasAmplitudeControl) {
        VibratorInfo.Builder builder = new VibratorInfo.Builder(/* id= */ 1);
        if (hasAmplitudeControl) {
            builder.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        }
        return builder.build();
    }

    private static VibratorInfo createVibInfoForFrequency(boolean hasFrequencyControl) {
        VibratorInfo.Builder builder = new VibratorInfo.Builder(/* id= */ 1);
        if (hasFrequencyControl) {
            builder.setCapabilities(
                    IVibrator.CAP_FREQUENCY_CONTROL | IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        }
        return builder.build();
    }
}
