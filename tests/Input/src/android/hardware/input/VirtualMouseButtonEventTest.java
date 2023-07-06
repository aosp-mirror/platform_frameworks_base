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

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class VirtualMouseButtonEventTest {

    @Test
    public void buttonEvent_emptyBuilder() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualMouseButtonEvent.Builder().build());
    }

    @Test
    public void buttonEvent_noButtonCode() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualMouseButtonEvent.Builder()
                .setAction(VirtualMouseButtonEvent.ACTION_BUTTON_RELEASE).build());
    }

    @Test
    public void buttonEvent_noAction() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualMouseButtonEvent.Builder()
                .setButtonCode(VirtualMouseButtonEvent.BUTTON_BACK).build());
    }

    @Test
    public void buttonEvent_created() {
        final VirtualMouseButtonEvent event = new VirtualMouseButtonEvent.Builder()
                .setAction(VirtualMouseButtonEvent.ACTION_BUTTON_PRESS)
                .setButtonCode(VirtualMouseButtonEvent.BUTTON_BACK).build();
        assertWithMessage("Incorrect button code").that(event.getButtonCode()).isEqualTo(
                VirtualMouseButtonEvent.BUTTON_BACK);
        assertWithMessage("Incorrect action").that(event.getAction()).isEqualTo(
                VirtualMouseButtonEvent.ACTION_BUTTON_PRESS);
    }
}
