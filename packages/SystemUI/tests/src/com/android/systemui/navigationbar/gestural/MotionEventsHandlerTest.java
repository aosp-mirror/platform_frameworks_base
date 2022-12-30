/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural;

import static android.view.InputDevice.SOURCE_TOUCHPAD;
import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.AXIS_GESTURE_X_OFFSET;
import static android.view.MotionEvent.AXIS_GESTURE_Y_OFFSET;

import static com.google.common.truth.Truth.assertThat;

import android.view.MotionEvent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link MotionEventsHandler}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class MotionEventsHandlerTest extends SysuiTestCase {

    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    private static int SCALE = 100;

    private MotionEventsHandler mMotionEventsHandler;

    @Before
    public void setUp() {
        mFeatureFlags.set(Flags.TRACKPAD_GESTURE_BACK, true);
        mMotionEventsHandler = new MotionEventsHandler(mFeatureFlags, SCALE);
    }

    @Test
    public void onTouchEvent_touchScreen_hasCorrectDisplacements() {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100, 100, 0);
        // TODO: change to use classification after gesture library is ported.
        down.setSource(SOURCE_TOUCHSCREEN);
        MotionEvent move1 = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 150, 125, 0);
        move1.setSource(SOURCE_TOUCHSCREEN);
        MotionEvent move2 = MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 200, 150, 0);
        move2.setSource(SOURCE_TOUCHSCREEN);
        MotionEvent up = MotionEvent.obtain(0, 3, MotionEvent.ACTION_UP, 250, 175, 0);
        up.setSource(SOURCE_TOUCHSCREEN);

        mMotionEventsHandler.onMotionEvent(down);
        mMotionEventsHandler.onMotionEvent(move1);
        assertThat(mMotionEventsHandler.getDisplacementX(move1)).isEqualTo(50);
        assertThat(mMotionEventsHandler.getDisplacementY(move1)).isEqualTo(25);
        mMotionEventsHandler.onMotionEvent(move2);
        assertThat(mMotionEventsHandler.getDisplacementX(move2)).isEqualTo(100);
        assertThat(mMotionEventsHandler.getDisplacementY(move2)).isEqualTo(50);
        mMotionEventsHandler.onMotionEvent(up);
        assertThat(mMotionEventsHandler.getDisplacementX(up)).isEqualTo(150);
        assertThat(mMotionEventsHandler.getDisplacementY(up)).isEqualTo(75);
    }

    @Test
    public void onTouchEvent_trackpad_hasCorrectDisplacements() {
        MotionEvent.PointerCoords[] downPointerCoords = new MotionEvent.PointerCoords[1];
        downPointerCoords[0] = new MotionEvent.PointerCoords();
        downPointerCoords[0].setAxisValue(AXIS_GESTURE_X_OFFSET, 0.1f);
        downPointerCoords[0].setAxisValue(AXIS_GESTURE_Y_OFFSET, 0.1f);
        MotionEvent.PointerProperties[] downPointerProperties =
                new MotionEvent.PointerProperties[1];
        downPointerProperties[0] = new MotionEvent.PointerProperties();
        downPointerProperties[0].id = 1;
        downPointerProperties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1,
                downPointerProperties, downPointerCoords, 0, 0, 1.0f, 1.0f, 0, 0,
                SOURCE_TOUCHPAD, 0);

        MotionEvent.PointerCoords[] movePointerCoords1 = new MotionEvent.PointerCoords[1];
        movePointerCoords1[0] = new MotionEvent.PointerCoords();
        movePointerCoords1[0].setAxisValue(AXIS_GESTURE_X_OFFSET, 0.2f);
        movePointerCoords1[0].setAxisValue(AXIS_GESTURE_Y_OFFSET, 0.1f);
        MotionEvent.PointerProperties[] movePointerProperties1 =
                new MotionEvent.PointerProperties[1];
        movePointerProperties1[0] = new MotionEvent.PointerProperties();
        movePointerProperties1[0].id = 1;
        movePointerProperties1[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent move1 = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 1,
                movePointerProperties1, movePointerCoords1, 0, 0, 1.0f, 1.0f, 0, 0, SOURCE_TOUCHPAD,
                0);

        MotionEvent.PointerCoords[] movePointerCoords2 = new MotionEvent.PointerCoords[1];
        movePointerCoords2[0] = new MotionEvent.PointerCoords();
        movePointerCoords2[0].setAxisValue(AXIS_GESTURE_X_OFFSET, 0.1f);
        movePointerCoords2[0].setAxisValue(AXIS_GESTURE_Y_OFFSET, 0.4f);
        MotionEvent.PointerProperties[] movePointerProperties2 =
                new MotionEvent.PointerProperties[1];
        movePointerProperties2[0] = new MotionEvent.PointerProperties();
        movePointerProperties2[0].id = 1;
        movePointerProperties2[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent move2 = MotionEvent.obtain(0, 2, MotionEvent.ACTION_MOVE, 1,
                movePointerProperties2, movePointerCoords2, 0, 0, 1.0f, 1.0f, 0, 0, SOURCE_TOUCHPAD,
                0);

        MotionEvent.PointerCoords[] upPointerCoords = new MotionEvent.PointerCoords[1];
        upPointerCoords[0] = new MotionEvent.PointerCoords();
        upPointerCoords[0].setAxisValue(AXIS_GESTURE_X_OFFSET, 0.1f);
        upPointerCoords[0].setAxisValue(AXIS_GESTURE_Y_OFFSET, 0.1f);
        MotionEvent.PointerProperties[] upPointerProperties2 =
                new MotionEvent.PointerProperties[1];
        upPointerProperties2[0] = new MotionEvent.PointerProperties();
        upPointerProperties2[0].id = 1;
        upPointerProperties2[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent up = MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, 1,
                upPointerProperties2, upPointerCoords, 0, 0, 1.0f, 1.0f, 0, 0, SOURCE_TOUCHPAD, 0);

        mMotionEventsHandler.onMotionEvent(down);
        mMotionEventsHandler.onMotionEvent(move1);
        assertThat(mMotionEventsHandler.getDisplacementX(move1)).isEqualTo(20f);
        assertThat(mMotionEventsHandler.getDisplacementY(move1)).isEqualTo(10f);
        mMotionEventsHandler.onMotionEvent(move2);
        assertThat(mMotionEventsHandler.getDisplacementX(move2)).isEqualTo(30f);
        assertThat(mMotionEventsHandler.getDisplacementY(move2)).isEqualTo(50f);
        mMotionEventsHandler.onMotionEvent(up);
        assertThat(mMotionEventsHandler.getDisplacementX(up)).isEqualTo(40f);
        assertThat(mMotionEventsHandler.getDisplacementY(up)).isEqualTo(60f);
    }
}
