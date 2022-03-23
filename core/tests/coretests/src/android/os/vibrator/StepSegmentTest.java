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

import android.os.Parcel;
import android.os.VibrationEffect;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class StepSegmentTest {
    private static final float TOLERANCE = 1e-2f;

    @Test
    public void testCreation() {
        StepSegment step = new StepSegment(/* amplitude= */ 1f, /* frequency= */ -1f,
                /* duration= */ 100);

        assertEquals(100, step.getDuration());
        assertTrue(step.hasNonZeroAmplitude());
        assertEquals(1f, step.getAmplitude());
        assertEquals(-1f, step.getFrequency());
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
        new StepSegment(/* amplitude= */ 0f, /* frequency= */ -1f, /* duration= */ 100).validate();

        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(/* amplitude= */ -2, 1f, 10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(/* amplitude= */ 2, 1f, 10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new StepSegment(2, 1f, /* duration= */ -1).validate());
    }

    @Test
    public void testHasNonZeroAmplitude() {
        assertTrue(new StepSegment(1f, 0, 0).hasNonZeroAmplitude());
        assertTrue(new StepSegment(0.01f, 0, 0).hasNonZeroAmplitude());
        assertTrue(new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE, 0, 0).hasNonZeroAmplitude());
        assertFalse(new StepSegment(0, 0, 0).hasNonZeroAmplitude());
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
    public void testScale_fullAmplitude() {
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
    public void testScale_halfAmplitude() {
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
}
