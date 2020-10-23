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

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Presubmit
@RunWith(JUnit4.class)
public class CombinedVibrationEffectTest {

    @Test
    public void testValidateMono() {
        CombinedVibrationEffect.createSynced(VibrationEffect.get(VibrationEffect.EFFECT_CLICK));

        assertThrows(IllegalArgumentException.class,
                () -> CombinedVibrationEffect.createSynced(new VibrationEffect.OneShot(-1, -1)));
    }

    @Test
    public void testSerializationMono() {
        CombinedVibrationEffect original = CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CombinedVibrationEffect restored = CombinedVibrationEffect.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }
}
