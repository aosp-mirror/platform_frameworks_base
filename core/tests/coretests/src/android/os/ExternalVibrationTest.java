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

import static org.mockito.Mockito.mock;

import android.media.AudioAttributes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ExternalVibrationTest {
    @Test
    public void testSerialization() {
        AudioAttributes audio = new AudioAttributes.Builder().build();
        IExternalVibrationController controller = mock(IExternalVibrationController.class);
        ExternalVibration original = new ExternalVibration(
                123, // uid
                "pkg",
                audio,
                controller);
        Parcel p = Parcel.obtain();
        original.writeToParcel(p, 0);
        p.setDataPosition(0);
        ExternalVibration restored = ExternalVibration.CREATOR.createFromParcel(p);
        assertEquals(original, restored);
    }
}

