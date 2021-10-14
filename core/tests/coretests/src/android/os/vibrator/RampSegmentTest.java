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
public class RampSegmentTest {
    private static final float TOLERANCE = 1e-2f;

    @Test
    public void testCreation() {
        RampSegment ramp = new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                /* StartFrequency= */ -1, /* endFrequency= */ 1, /* duration= */ 100);

        assertEquals(100L, ramp.getDuration());
        assertTrue(ramp.hasNonZeroAmplitude());
        assertEquals(1f, ramp.getStartAmplitude());
        assertEquals(0f, ramp.getEndAmplitude());
        assertEquals(-1f, ramp.getStartFrequency());
        assertEquals(1f, ramp.getEndFrequency());
    }

    @Test
    public void testSerialization() {
        RampSegment original = new RampSegment(0, 1, 0, 0.5f, 10);
        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        assertEquals(original, RampSegment.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void testValidate() {
        new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                /* StartFrequency= */ -1, /* endFrequency= */ 1, /* duration= */ 100).validate();

        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(VibrationEffect.DEFAULT_AMPLITUDE, 0, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(/* startAmplitude= */ -2, 0, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, /* endAmplitude= */ 2, 0, 0, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RampSegment(0, 0, 0, 0, /* duration= */ -1).validate());
    }

    @Test
    public void testHasNonZeroAmplitude() {
        assertTrue(new RampSegment(0, 1, 0, 0, 0).hasNonZeroAmplitude());
        assertTrue(new RampSegment(0.01f, 0, 0, 0, 0).hasNonZeroAmplitude());
        assertFalse(new RampSegment(0, 0, 0, 0, 0).hasNonZeroAmplitude());
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
    public void testScale() {
        RampSegment initial = new RampSegment(0, 1, 0, 0, 0);

        assertEquals(0f, initial.scale(1).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(0.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(1.5f).scale(2 / 3f).getStartAmplitude(), TOLERANCE);
        assertEquals(0f, initial.scale(0.8f).scale(1.25f).getStartAmplitude(), TOLERANCE);

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
    public void testScale_halfPrimitiveScaleValue() {
        RampSegment initial = new RampSegment(0.5f, 1, 0, 0, 0);

        assertEquals(0.5f, initial.scale(1).getStartAmplitude(), TOLERANCE);
        assertEquals(0.17f, initial.scale(0.5f).getStartAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.86f, initial.scale(1.5f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.47f, initial.scale(1.5f).scale(2 / 3f).getStartAmplitude(), TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.35f, initial.scale(0.8f).getStartAmplitude(), TOLERANCE);
        assertEquals(0.5f, initial.scale(0.8f).scale(1.25f).getStartAmplitude(), TOLERANCE);
    }
}
