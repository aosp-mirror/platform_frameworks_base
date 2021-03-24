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

import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;

@Presubmit
@RunWith(JUnit4.class)
public class CombinedVibrationEffectTest {
    private static final VibrationEffect VALID_EFFECT = VibrationEffect.createOneShot(10, 255);
    private static final VibrationEffect INVALID_EFFECT = new VibrationEffect.Composed(
            new ArrayList<>(), 0);

    @Test
    public void testValidateMono() {
        CombinedVibrationEffect.createSynced(VALID_EFFECT);

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibrationEffect.createSynced(INVALID_EFFECT));
    }

    @Test
    public void testValidateStereo() {
        CombinedVibrationEffect.startSynced()
                .addVibrator(0, VALID_EFFECT)
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .combine();
        CombinedVibrationEffect.startSynced()
                .addVibrator(0, INVALID_EFFECT)
                .addVibrator(0, VALID_EFFECT)
                .combine();

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibrationEffect.startSynced()
                        .addVibrator(0, INVALID_EFFECT)
                        .combine());
    }

    @Test
    public void testValidateSequential() {
        CombinedVibrationEffect.startSequential()
                .addNext(0, VALID_EFFECT)
                .addNext(CombinedVibrationEffect.createSynced(VALID_EFFECT))
                .combine();
        CombinedVibrationEffect.startSequential()
                .addNext(0, VALID_EFFECT)
                .addNext(0, VALID_EFFECT, 100)
                .combine();
        CombinedVibrationEffect.startSequential()
                .addNext(CombinedVibrationEffect.startSequential()
                        .addNext(0, VALID_EFFECT)
                        .combine())
                .combine();

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibrationEffect.startSequential()
                        .addNext(0, VALID_EFFECT, -1)
                        .combine());
        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibrationEffect.startSequential()
                        .addNext(0, INVALID_EFFECT)
                        .combine());
    }

    @Test
    public void testNestedSequentialAccumulatesDelays() {
        CombinedVibrationEffect.Sequential combined =
                (CombinedVibrationEffect.Sequential) CombinedVibrationEffect.startSequential()
                        .addNext(CombinedVibrationEffect.startSequential()
                                        .addNext(0, VALID_EFFECT, /* delay= */ 100)
                                        .addNext(1, VALID_EFFECT, /* delay= */ 100)
                                        .combine(),
                                /* delay= */ 10)
                        .addNext(CombinedVibrationEffect.startSequential()
                                .addNext(0, VALID_EFFECT, /* delay= */ 100)
                                .combine())
                        .addNext(CombinedVibrationEffect.startSequential()
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
                () -> CombinedVibrationEffect.startSynced().combine());
        assertThrows(IllegalStateException.class,
                () -> CombinedVibrationEffect.startSequential().combine());
    }

    @Test
    public void testDurationMono() {
        assertEquals(1, CombinedVibrationEffect.createSynced(
                VibrationEffect.createOneShot(1, 1)).getDuration());
        assertEquals(-1, CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK)).getDuration());
        assertEquals(Long.MAX_VALUE, CombinedVibrationEffect.createSynced(
                VibrationEffect.createWaveform(
                        new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0)).getDuration());
    }

    @Test
    public void testDurationStereo() {
        assertEquals(6, CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.createOneShot(1, 1))
                .addVibrator(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(-1, CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(Long.MAX_VALUE, CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0))
                .combine()
                .getDuration());
    }

    @Test
    public void testDurationSequential() {
        assertEquals(26, CombinedVibrationEffect.startSequential()
                .addNext(1, VibrationEffect.createOneShot(10, 10), 10)
                .addNext(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(-1, CombinedVibrationEffect.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1))
                .combine()
                .getDuration());
        assertEquals(Long.MAX_VALUE, CombinedVibrationEffect.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(2,
                        VibrationEffect.createWaveform(new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0))
                .combine()
                .getDuration());
    }

    @Test
    public void testHasVibratorMono_returnsTrueForAnyVibrator() {
        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        assertTrue(effect.hasVibrator(0));
        assertTrue(effect.hasVibrator(1));
    }

    @Test
    public void testHasVibratorStereo_returnsOnlyTheIdsSet() {
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        assertFalse(effect.hasVibrator(0));
        assertTrue(effect.hasVibrator(1));
        assertFalse(effect.hasVibrator(2));
    }

    @Test
    public void testHasVibratorSequential_returnsNestedVibrators() {
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSequential()
                .addNext(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(CombinedVibrationEffect.startSynced()
                        .addVibrator(2, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                        .combine())
                .combine();
        assertFalse(effect.hasVibrator(0));
        assertTrue(effect.hasVibrator(1));
        assertTrue(effect.hasVibrator(2));
    }

    @Test
    public void testSerializationMono() {
        CombinedVibrationEffect original = CombinedVibrationEffect.createSynced(VALID_EFFECT);

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibrationEffect restored = CombinedVibrationEffect.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    @Test
    public void testSerializationStereo() {
        CombinedVibrationEffect original = CombinedVibrationEffect.startSynced()
                .addVibrator(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(1, VibrationEffect.createOneShot(10, 255))
                .combine();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibrationEffect restored = CombinedVibrationEffect.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    @Test
    public void testSerializationSequential() {
        CombinedVibrationEffect original = CombinedVibrationEffect.startSequential()
                .addNext(0, VALID_EFFECT)
                .addNext(CombinedVibrationEffect.createSynced(VALID_EFFECT))
                .addNext(0, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), 100)
                .combine();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibrationEffect restored = CombinedVibrationEffect.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }
}
