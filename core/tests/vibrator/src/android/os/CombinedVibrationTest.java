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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.testng.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(JUnit4.class)
public class CombinedVibrationTest {
    private static final VibrationEffect VALID_EFFECT = VibrationEffect.createOneShot(10, 255);
    private static final VibrationEffect INVALID_EFFECT = new VibrationEffect.Composed(
            new ArrayList<>(), 0);

    @Test
    public void testValidateMono() {
        CombinedVibration.createParallel(VALID_EFFECT);

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibration.createParallel(INVALID_EFFECT));
    }

    @Test
    public void testValidateStereo() {
        CombinedVibration.startParallel()
                .addVibrator(0, VALID_EFFECT)
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .combine();
        CombinedVibration.startParallel()
                .addVibrator(0, INVALID_EFFECT)
                .addVibrator(0, VALID_EFFECT)
                .combine();

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibration.startParallel()
                        .addVibrator(0, INVALID_EFFECT)
                        .combine());
    }

    @Test
    public void testValidateSequential() {
        CombinedVibration.startSequential()
                .addNext(0, VALID_EFFECT)
                .addNext(CombinedVibration.createParallel(VALID_EFFECT))
                .combine();
        CombinedVibration.startSequential()
                .addNext(0, VALID_EFFECT)
                .addNext(0, VALID_EFFECT, 100)
                .combine();
        CombinedVibration.startSequential()
                .addNext(CombinedVibration.startSequential()
                        .addNext(0, VALID_EFFECT)
                        .combine())
                .combine();

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibration.startSequential()
                        .addNext(0, VALID_EFFECT, -1)
                        .combine());
        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibration.startSequential()
                        .addNext(0, INVALID_EFFECT)
                        .combine());
    }

    @Test
    public void testNestedSequentialAccumulatesDelays() {
        CombinedVibration.Sequential combined =
                (CombinedVibration.Sequential) CombinedVibration.startSequential()
                        .addNext(CombinedVibration.startSequential()
                                        .addNext(0, VALID_EFFECT, /* delay= */ 100)
                                        .addNext(1, VALID_EFFECT, /* delay= */ 100)
                                        .combine(),
                                /* delay= */ 10)
                        .addNext(CombinedVibration.startSequential()
                                .addNext(0, VALID_EFFECT, /* delay= */ 100)
                                .combine())
                        .addNext(CombinedVibration.startSequential()
                                        .addNext(0, VALID_EFFECT)
                                        .addNext(0, VALID_EFFECT, /* delay= */ 100)
                                        .combine(),
                                /* delay= */ 10)
                        .combine();

        assertEquals(Arrays.asList(110, 100, 100, 10, 100), combined.getDelays());
    }

    @Test
    public void testCombineEmptyFails() {
        assertThrows(IllegalStateException.class,
                () -> CombinedVibration.startParallel().combine());
        assertThrows(IllegalStateException.class,
                () -> CombinedVibration.startSequential().combine());
    }

    @Test
    public void testDurationMono() {
        assertEquals(1, CombinedVibration.createParallel(
                VibrationEffect.createOneShot(1, 1)).getDuration());
        assertEquals(-1, CombinedVibration.createParallel(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK)).getDuration());
        assertEquals(-1, CombinedVibration.createParallel(
                        VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 100)
                                .compose())
                .getDuration());
        assertEquals(Long.MAX_VALUE, CombinedVibration.createParallel(
                VibrationEffect.createWaveform(
                        new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0)).getDuration());
    }

    @Test
    public void testDurationStereo() {
        assertEquals(6, CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createOneShot(1, 1))
                .addVibrator(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(-1, CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(Long.MAX_VALUE, CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0))
                .combine()
                .getDuration());
    }

    @Test
    public void testDurationSequential() {
        assertEquals(26, CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.createOneShot(10, 10), 10)
                .addNext(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(-1, CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(Long.MAX_VALUE, CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0))
                .combine()
                .getDuration());
    }

    @Test
    public void testIsHapticFeedbackCandidateMono() {
        assertTrue(CombinedVibration.createParallel(
                VibrationEffect.createOneShot(1, 1)).isHapticFeedbackCandidate());
        assertTrue(CombinedVibration.createParallel(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK)).isHapticFeedbackCandidate());
        assertTrue(CombinedVibration.createParallel(
                        VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 100)
                                .compose())
                .isHapticFeedbackCandidate());
        // Too long to be classified as a haptic feedback.
        assertFalse(CombinedVibration.createParallel(
                VibrationEffect.createOneShot(10_000, 1)).isHapticFeedbackCandidate());
        // Repeating vibrations should not be classified as a haptic feedback.
        assertFalse(CombinedVibration.createParallel(
                VibrationEffect.createWaveform(new long[]{1}, new int[]{1}, 0))
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidateStereo() {
        assertTrue(CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createOneShot(1, 1))
                .addVibrator(2, VibrationEffect.createWaveform(new long[]{6}, new int[]{1}, -1))
                .combine()
                .isHapticFeedbackCandidate());
        assertTrue(CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2,
                        VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 100)
                                .compose())
                .combine()
                .isHapticFeedbackCandidate());
        // Repeating vibrations should not be classified as a haptic feedback.
        assertFalse(CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createWaveform(new long[]{1}, new int[]{1}, 0))
                .combine()
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidateSequential() {
        assertTrue(CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.createOneShot(10, 10), 10)
                .addNext(2, VibrationEffect.createWaveform(new long[]{5}, new int[]{1}, -1))
                .combine()
                .isHapticFeedbackCandidate());
        assertTrue(CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2,
                        VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
                                .compose())
                .combine()
                .isHapticFeedbackCandidate());
        // Repeating vibrations should not be classified as a haptic feedback.
        assertFalse(CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2, VibrationEffect.createWaveform(new long[]{1}, new int[]{1}, 0))
                .combine()
                .isHapticFeedbackCandidate());
        // Too many effects to be classified as a haptic feedback.
        assertFalse(CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .addNext(3, VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK))
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_THUD))
                .combine()
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testHasVibratorMono_returnsTrueForAnyVibrator() {
        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        assertTrue(effect.hasVibrator(0));
        assertTrue(effect.hasVibrator(1));
    }

    @Test
    public void testHasVibratorStereo_returnsOnlyTheIdsSet() {
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(effect.hasVibrator(0));
        assertTrue(effect.hasVibrator(1));
        assertFalse(effect.hasVibrator(2));
    }

    @Test
    public void testHasVibratorSequential_returnsNestedVibrators() {
        CombinedVibration effect = CombinedVibration.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(CombinedVibration.startParallel()
                        .addVibrator(2, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                        .combine())
                .combine();
        assertFalse(effect.hasVibrator(0));
        assertTrue(effect.hasVibrator(1));
        assertTrue(effect.hasVibrator(2));
    }

    @Test
    public void testSerializationMono() {
        CombinedVibration original = CombinedVibration.createParallel(VALID_EFFECT);

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibration restored = CombinedVibration.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    @Test
    public void testSerializationStereo() {
        CombinedVibration original = CombinedVibration.startParallel()
                .addVibrator(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(1, VibrationEffect.createOneShot(10, 255))
                .combine();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibration restored = CombinedVibration.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    @Test
    public void testSerializationSequential() {
        CombinedVibration original = CombinedVibration.startSequential()
                .addNext(0, VALID_EFFECT)
                .addNext(CombinedVibration.createParallel(VALID_EFFECT))
                .addNext(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), 100)
                .combine();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibration restored = CombinedVibration.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }
}
