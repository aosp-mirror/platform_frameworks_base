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
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import android.graphics.PointF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * generates {@link MotionEvent} with source {@link InputDevice#SOURCE_TOUCHSCREEN}
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

    private static MotionEvent generateSingleTouchEvent(int displayId, int action, float x,
            float y) {
        return generateMultiplePointersEvent(displayId, action, new PointF(x, y));
    }

    /**
     * Creates a list of {@link MotionEvent} with given pointers location.
     *
     * @param displayId the display id
     * @param pointF1   location on the screen of the second pointer.
     * @param pointF2   location on the screen of the second pointer.
     * @return a list of {@link MotionEvent} with {@link MotionEvent#ACTION_DOWN} and {@link
     * MotionEvent#ACTION_POINTER_DOWN}.
     */
    public static List<MotionEvent> twoPointersDownEvents(int displayId, PointF pointF1,
            PointF pointF2) {
        final List<MotionEvent> downEvents = new ArrayList<>();
        final MotionEvent downEvent = generateMultiplePointersEvent(displayId,
                MotionEvent.ACTION_DOWN, pointF1);
        downEvents.add(downEvent);

        final int actionIndex = 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        final int action = ACTION_POINTER_DOWN | actionIndex;

        final MotionEvent twoPointersDownEvent = generateMultiplePointersEvent(displayId, action,
                pointF1, pointF2);
        downEvents.add(twoPointersDownEvent);
        return downEvents;
    }

    private static MotionEvent generateMultiplePointersEvent(int displayId, int action,
            PointF... pointFs) {
        final int length = pointFs.length;
        final MotionEvent.PointerCoords[] pointerCoordsArray =
                new MotionEvent.PointerCoords[length];
        final MotionEvent.PointerProperties[] pointerPropertiesArray =
                new MotionEvent.PointerProperties[length];
        for (int i = 0; i < length; i++) {
            MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
            pointerCoords.x = pointFs[i].x;
            pointerCoords.y = pointFs[i].y;
            pointerCoordsArray[i] = pointerCoords;

            MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
            pointerProperties.id = i;
            pointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;
            pointerPropertiesArray[i] = pointerProperties;
        }

        final long downTime = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(
                /* downTime */ downTime,
                /* eventTime */ downTime,
                /* action */ action,
                /* pointerCount */ length,
                /* pointerProperties */ pointerPropertiesArray,
                /* pointerCoords */ pointerCoordsArray,
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

    /**
     *  Generates a move event that moves the pointer of the original event with given index.
     *  The original event should not be up event and we don't support
     *  {@link MotionEvent#ACTION_POINTER_UP} now.
     *
     * @param originalEvent the move or down event
     * @param pointerIndex the index of the pointer we want to move.
     * @param offsetX the offset in X coordinate.
     * @param offsetY the offset in Y coordinate.
     * @return a motion event with move action.
     */
    public static MotionEvent movePointer(MotionEvent originalEvent, int pointerIndex,
            float offsetX, float offsetY) {
        if (originalEvent.getActionMasked() == ACTION_UP) {
            throw new IllegalArgumentException("No pointer is on the screen");
        }

        if (originalEvent.getActionMasked() == ACTION_POINTER_UP) {
            throw new IllegalArgumentException("unsupported yet,please implement it first");
        }

        final int pointerCount = originalEvent.getPointerCount();
        if (pointerIndex >= pointerCount) {
            throw new IllegalArgumentException(
                    pointerIndex + "is not available with pointer count" + pointerCount);
        }
        final int action = MotionEvent.ACTION_MOVE;
        final MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            MotionEvent.PointerProperties pointerProperty = new MotionEvent.PointerProperties();
            originalEvent.getPointerProperties(i, pointerProperty);
            pp[i] = pointerProperty;
        }

        final MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            MotionEvent.PointerCoords pointerCoord = new MotionEvent.PointerCoords();
            originalEvent.getPointerCoords(i, pointerCoord);
            pc[i] = pointerCoord;
        }
        pc[pointerIndex].x += offsetX;
        pc[pointerIndex].y += offsetY;
        final MotionEvent ev = MotionEvent.obtain(
                /* downTime */ originalEvent.getDownTime(),
                /* eventTime */ SystemClock.uptimeMillis(),
                /* action */ action,
                /* pointerCount */ 2,
                /* pointerProperties */ pp,
                /* pointerCoords */ pc,
                /* metaState */ 0,
                /* buttonState */ 0,
                /* xPrecision */ 1.0f,
                /* yPrecision */ 1.0f,
                /* deviceId */ 0,
                /* edgeFlags */ 0,
                /* source */ originalEvent.getSource(),
                /* flags */ originalEvent.getFlags());
        ev.setDisplayId(originalEvent.getDisplayId());
        return ev;
    }
}
