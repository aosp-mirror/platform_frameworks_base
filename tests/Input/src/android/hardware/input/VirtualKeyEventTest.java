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

package android.hardware.input;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.assertThrows;

import android.view.KeyEvent;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VirtualKeyEventTest {

    @Test
    public void keyEvent_emptyBuilder() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualKeyEvent.Builder().build());
    }

    @Test
    public void keyEvent_noKeyCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualKeyEvent.Builder().setAction(VirtualKeyEvent.ACTION_DOWN).build());
    }

    @Test
    public void keyEvent_noAction() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualKeyEvent.Builder().setKeyCode(KeyEvent.KEYCODE_A).build());
    }

    @Test
    public void keyEvent_created() {
        final VirtualKeyEvent event = new VirtualKeyEvent.Builder()
                .setAction(VirtualKeyEvent.ACTION_DOWN)
                .setKeyCode(KeyEvent.KEYCODE_A).build();
        assertWithMessage("Incorrect key code").that(event.getKeyCode()).isEqualTo(
                KeyEvent.KEYCODE_A);
        assertWithMessage("Incorrect action").that(event.getAction()).isEqualTo(
                VirtualKeyEvent.ACTION_DOWN);
    }
}
