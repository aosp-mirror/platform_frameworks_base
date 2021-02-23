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

import android.hardware.vibrator.IVibrator;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Presubmit
@RunWith(JUnit4.class)
public class VibratorInfoTest {

    @Test
    public void testHasAmplitudeControl() {
        assertFalse(createInfo(/* capabilities= */ 0).hasAmplitudeControl());
        assertTrue(createInfo(IVibrator.CAP_COMPOSE_EFFECTS
                | IVibrator.CAP_AMPLITUDE_CONTROL).hasAmplitudeControl());
    }

    @Test
    public void testHasCapabilities() {
        assertTrue(createInfo(IVibrator.CAP_COMPOSE_EFFECTS)
                .hasCapability(IVibrator.CAP_COMPOSE_EFFECTS));
        assertFalse(createInfo(IVibrator.CAP_COMPOSE_EFFECTS)
                .hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL));
    }

    @Test
    public void testIsEffectSupported() {
        VibratorInfo info = new VibratorInfo(/* id= */ 0, /* capabilities= */0,
                new int[]{VibrationEffect.EFFECT_CLICK}, null);
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN,
                createInfo(/* capabilities= */ 0).isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_TICK));
    }

    @Test
    public void testIsPrimitiveSupported() {
        VibratorInfo info = new VibratorInfo(/* id= */ 0, IVibrator.CAP_COMPOSE_EFFECTS,
                null, new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK});
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK));

        // Returns false when there is no compose capability.
        info = new VibratorInfo(/* id= */ 0, /* capabilities= */ 0,
                null, new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK});
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testEquals() {
        VibratorInfo empty = new VibratorInfo(1, 0, null, null);
        VibratorInfo complete = new VibratorInfo(1, IVibrator.CAP_AMPLITUDE_CONTROL,
                new int[]{VibrationEffect.EFFECT_CLICK},
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK});

        assertEquals(complete, complete);
        assertEquals(complete, new VibratorInfo(1, IVibrator.CAP_AMPLITUDE_CONTROL,
                new int[]{VibrationEffect.EFFECT_CLICK},
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}));

        assertFalse(empty.equals(new VibratorInfo(1, 0, new int[]{}, new int[]{})));
        assertFalse(complete.equals(new VibratorInfo(1, IVibrator.CAP_COMPOSE_EFFECTS,
                new int[]{VibrationEffect.EFFECT_CLICK},
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK})));
        assertFalse(complete.equals(new VibratorInfo(1, IVibrator.CAP_AMPLITUDE_CONTROL,
                new int[]{}, new int[]{})));
        assertFalse(complete.equals(new VibratorInfo(1, IVibrator.CAP_AMPLITUDE_CONTROL,
                null, new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK})));
        assertFalse(complete.equals(new VibratorInfo(1, IVibrator.CAP_AMPLITUDE_CONTROL,
                new int[]{VibrationEffect.EFFECT_CLICK}, null)));
    }

    @Test
    public void testSerialization() {
        VibratorInfo original = new VibratorInfo(1, IVibrator.CAP_COMPOSE_EFFECTS,
                new int[]{VibrationEffect.EFFECT_CLICK}, null);

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VibratorInfo restored = VibratorInfo.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    private static VibratorInfo createInfo(long capabilities) {
        return new VibratorInfo(/* id= */ 0, capabilities, null, null);
    }
}
