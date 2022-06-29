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
public class VirtualMouseScrollEventTest {

    @Test
    public void scrollEvent_xOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualMouseScrollEvent.Builder()
                .setXAxisMovement(1.5f)
                .setYAxisMovement(1.0f));
    }

    @Test
    public void scrollEvent_yOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualMouseScrollEvent.Builder()
                .setXAxisMovement(0.5f)
                .setYAxisMovement(1.1f));
    }

    @Test
    public void scrollEvent_created() {
        final VirtualMouseScrollEvent event = new VirtualMouseScrollEvent.Builder()
                .setXAxisMovement(-1f)
                .setYAxisMovement(1f).build();
        assertWithMessage("Incorrect x value").that(event.getXAxisMovement()).isEqualTo(-1f);
        assertWithMessage("Incorrect y value").that(event.getYAxisMovement()).isEqualTo(1f);
    }
}

