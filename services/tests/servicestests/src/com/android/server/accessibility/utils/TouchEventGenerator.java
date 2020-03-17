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

package com.android.server.accessibility.utils;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.PointerCoords;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * generates {@link MotionEvent} with source {@link InputDevice#SOURCE_TOUCHSCREEN}
 *
 */
public class TouchEventGenerator {

    public static MotionEvent downEvent(int displayId, float x, float y) {
        return generateSingleTouchEvent(displayId, ACTION_DOWN, x, y);
    }

    public static MotionEvent moveEvent(int displayId, float x, float y) {
        return generateSingleTouchEvent(displayId, ACTION_MOVE, x, y);
    }
    public static MotionEvent upEvent(int displayId, float x, float y) {
        return generateSingleTouchEvent(displayId, ACTION_UP, x, y);
    }

    public static MotionEvent pointerDownEvent(int displayId, PointerCoords defPointerCoords,
            PointerCoords pointerCoords) {
        return generatePointerEvent(displayId, ACTION_POINTER_DOWN, defPointerCoords,
                pointerCoords);
    }

    private static MotionEvent generateSingleTouchEvent(int displayId, int action, float x,
            float y) {
        final long  downTime = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(downTime, downTime,
                action, x, y, 0);
        ev.setDisplayId(displayId);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    private static MotionEvent generatePointerEvent(int displayId, int action,
            PointerCoords defPointerCoords, PointerCoords pointerCoords) {
        final long  downTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties defPointerProperties = new MotionEvent.PointerProperties();
        defPointerProperties.id = 0;
        defPointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
        pointerProperties.id = 1;
        pointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;

        final MotionEvent ev = MotionEvent.obtain(
                /* downTime */ downTime,
                /* eventTime */ downTime,
                /* action */ action,
                /* pointerCount */ 2,
                /* pointerProperties */ new MotionEvent.PointerProperties[] {
                        defPointerProperties, pointerProperties},
                /* pointerCoords */ new PointerCoords[] { defPointerCoords, pointerCoords },
                /* metaState */ 0,
                /* buttonState */ 0,
                /* xPrecision */ 1.0f,
                /* yPrecision */ 1.0f,
                /* deviceId */ 0,
                /* edgeFlags */ 0,
                /* source */ InputDevice.SOURCE_TOUCHSCREEN,
                /* flags */ 0);
        ev.setDisplayId(displayId);
        return ev;
    }
}
