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
public class VirtualTouchEventTest {

    @Test
    public void touchEvent_emptyBuilder() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder().build());
    }

    @Test
    public void touchEvent_noAction() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .build());
    }

    @Test
    public void touchEvent_noPointerId() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setX(0f)
                .setY(1f)
                .build());
    }

    @Test
    public void touchEvent_noToolType() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .build());
    }

    @Test
    public void touchEvent_noX() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setY(1f)
                .setPointerId(1)
                .build());
    }


    @Test
    public void touchEvent_noY() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setX(0f)
                .setPointerId(1)
                .build());
    }

    @Test
    public void touchEvent_created() {
        final VirtualTouchEvent event = new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .build();
        assertWithMessage("Incorrect action").that(event.getAction()).isEqualTo(
                VirtualTouchEvent.ACTION_DOWN);
        assertWithMessage("Incorrect tool type").that(event.getToolType()).isEqualTo(
                VirtualTouchEvent.TOOL_TYPE_FINGER);
        assertWithMessage("Incorrect x").that(event.getX()).isEqualTo(0f);
        assertWithMessage("Incorrect y").that(event.getY()).isEqualTo(1f);
        assertWithMessage("Incorrect pointer id").that(event.getPointerId()).isEqualTo(1);
    }

    @Test
    public void touchEvent_created_withPressureAndAxis() {
        final VirtualTouchEvent event = new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .setPressure(0.5f)
                .setMajorAxisSize(10f)
                .build();
        assertWithMessage("Incorrect action").that(event.getAction()).isEqualTo(
                VirtualTouchEvent.ACTION_DOWN);
        assertWithMessage("Incorrect tool type").that(event.getToolType()).isEqualTo(
                VirtualTouchEvent.TOOL_TYPE_FINGER);
        assertWithMessage("Incorrect x").that(event.getX()).isEqualTo(0f);
        assertWithMessage("Incorrect y").that(event.getY()).isEqualTo(1f);
        assertWithMessage("Incorrect pointer id").that(event.getPointerId()).isEqualTo(1);
        assertWithMessage("Incorrect pressure").that(event.getPressure()).isEqualTo(0.5f);
        assertWithMessage("Incorrect major axis size").that(event.getMajorAxisSize()).isEqualTo(
                10f);
    }

    @Test
    public void touchEvent_cancelUsedImproperly() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_CANCEL)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .build());
    }

    @Test
    public void touchEvent_palmUsedImproperly() {
        assertThrows(IllegalArgumentException.class, () -> new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_MOVE)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_PALM)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .build());
    }

    @Test
    public void touchEvent_palmAndCancelUsedProperly() {
        final VirtualTouchEvent event = new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_CANCEL)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_PALM)
                .setX(0f)
                .setY(1f)
                .setPointerId(1)
                .setPressure(0.5f)
                .setMajorAxisSize(10f)
                .build();
        assertWithMessage("Incorrect action").that(event.getAction()).isEqualTo(
                VirtualTouchEvent.ACTION_CANCEL);
        assertWithMessage("Incorrect tool type").that(event.getToolType()).isEqualTo(
                VirtualTouchEvent.TOOL_TYPE_PALM);
        assertWithMessage("Incorrect x").that(event.getX()).isEqualTo(0f);
        assertWithMessage("Incorrect y").that(event.getY()).isEqualTo(1f);
        assertWithMessage("Incorrect pointer id").that(event.getPointerId()).isEqualTo(1);
        assertWithMessage("Incorrect pressure").that(event.getPressure()).isEqualTo(0.5f);
        assertWithMessage("Incorrect major axis size").that(event.getMajorAxisSize()).isEqualTo(
                10f);
    }
}
