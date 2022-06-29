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

import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;

import android.os.Parcel;
import android.os.VibrationEffect;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class PrebakedSegmentTest {

    @Test
    public void testCreation() {
        PrebakedSegment prebaked = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        assertEquals(-1, prebaked.getDuration());
        assertTrue(prebaked.hasNonZeroAmplitude());
        assertEquals(VibrationEffect.EFFECT_CLICK, prebaked.getEffectId());
        assertEquals(VibrationEffect.EFFECT_STRENGTH_MEDIUM, prebaked.getEffectStrength());
        assertTrue(prebaked.shouldFallback());
    }

    @Test
    public void testSerialization() {
        PrebakedSegment original = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        assertEquals(original, PrebakedSegment.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void testValidate() {
        new PrebakedSegment(VibrationEffect.EFFECT_CLICK, true,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM).validate();

        assertThrows(IllegalArgumentException.class,
                () -> new PrebakedSegment(1000, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> new PrebakedSegment(VibrationEffect.EFFECT_TICK, false, 1000)
                        .validate());
    }

    @Test
    public void testResolve_ignoresAndReturnsSameEffect() {
        PrebakedSegment prebaked = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        assertSame(prebaked, prebaked.resolve(1000));
    }

    @Test
    public void testApplyEffectStrength() {
        PrebakedSegment medium = new PrebakedSegment(
                VibrationEffect.EFFECT_THUD, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        PrebakedSegment light = medium.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertNotEquals(medium, light);
        assertEquals(medium.getEffectId(), light.getEffectId());
        assertEquals(medium.shouldFallback(), light.shouldFallback());
        assertEquals(VibrationEffect.EFFECT_STRENGTH_LIGHT, light.getEffectStrength());

        PrebakedSegment strong = medium.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG);
        assertNotEquals(medium, strong);
        assertEquals(medium.getEffectId(), strong.getEffectId());
        assertEquals(medium.shouldFallback(), strong.shouldFallback());
        assertEquals(VibrationEffect.EFFECT_STRENGTH_STRONG, strong.getEffectStrength());

        assertSame(medium, medium.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_MEDIUM));
        // Invalid vibration effect strength is ignored.
        assertSame(medium, medium.applyEffectStrength(1000));
    }

    @Test
    public void testScale_ignoresAndReturnsSameEffect() {
        PrebakedSegment prebaked = new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        assertSame(prebaked, prebaked.scale(0.5f));
    }

    @Test
    public void testDuration() {
        assertEquals(-1, new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .getDuration());
        assertEquals(-1, new PrebakedSegment(
                VibrationEffect.EFFECT_TICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .getDuration());
        assertEquals(-1, new PrebakedSegment(
                VibrationEffect.EFFECT_DOUBLE_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .getDuration());
        assertEquals(-1, new PrebakedSegment(
                VibrationEffect.EFFECT_THUD, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .getDuration());
    }

    @Test
    public void testIsHapticFeedbackCandidate_prebakedConstants_areCandidates() {
        assertTrue(new PrebakedSegment(
                VibrationEffect.EFFECT_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
        assertTrue(new PrebakedSegment(
                VibrationEffect.EFFECT_TICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
        assertTrue(new PrebakedSegment(
                VibrationEffect.EFFECT_DOUBLE_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
        assertTrue(new PrebakedSegment(
                VibrationEffect.EFFECT_HEAVY_CLICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
        assertTrue(new PrebakedSegment(
                VibrationEffect.EFFECT_THUD, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
        assertTrue(new PrebakedSegment(
                VibrationEffect.EFFECT_TEXTURE_TICK, true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_prebakedRingtones_notCandidates() {
        assertFalse(new PrebakedSegment(
                VibrationEffect.RINGTONES[1], true, VibrationEffect.EFFECT_STRENGTH_MEDIUM)
                .isHapticFeedbackCandidate());
    }
}
