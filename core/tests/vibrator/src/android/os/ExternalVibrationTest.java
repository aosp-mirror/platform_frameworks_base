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

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioAttributes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ExternalVibrationTest {

    @Test
    public void testSerialization() {
        ExternalVibration original = new ExternalVibration(
                /* uid= */ 123,
                "pkg",
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_BYPASS_MUTE)
                        .build(),
                IExternalVibrationController.Stub.asInterface(new Binder()));
        Parcel p = Parcel.obtain();
        original.writeToParcel(p, 0);
        p.setDataPosition(0);
        ExternalVibration restored = ExternalVibration.CREATOR.createFromParcel(p);
        assertThat(restored).isEqualTo(original);
        // ExternalVibration.equals relies on the binder token only, check other attributes as well
        assertThat(restored.getUid()).isEqualTo(original.getUid());
        assertThat(restored.getPackage()).isEqualTo(original.getPackage());
        assertThat(restored.getAudioAttributes()).isEqualTo(original.getAudioAttributes());
        assertThat(restored.getToken()).isEqualTo(original.getToken());
    }
}
