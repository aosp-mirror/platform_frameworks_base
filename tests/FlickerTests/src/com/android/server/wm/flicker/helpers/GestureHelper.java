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

package com.android.server.wm.flicker.helpers;

import android.annotation.NonNull;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import androidx.annotation.Nullable;

/**
 * Injects gestures given an {@link Instrumentation} object.
 */
public class GestureHelper {
    // Inserted after each motion event injection.
    private static final int MOTION_EVENT_INJECTION_DELAY_MILLIS = 5;

    private final UiAutomation mUiAutomation;

    /**
     * Primary pointer should be cached here for separate release
     */
    @Nullable private PointerProperties mPrimaryPtrProp;
    @Nullable private PointerCoords mPrimaryPtrCoord;
    private long mPrimaryPtrDownTime;

    /**
     * A pair of floating point values.
     */
    public static class Tuple {
        public float x;
        public float y;

        public Tuple(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public GestureHelper(Instrumentation instrumentation) {
        mUiAutomation = instrumentation.getUiAutomation();
    }

    /**
     * Injects a series of {@link MotionEvent}s to simulate a drag gesture without pointer release.
     *
     * Simulates a drag gesture without releasing the primary pointer. The primary pointer info
     * will be cached for potential release later on by {@code releasePrimaryPointer()}
     *
     * @param startPoint initial coordinates of the primary pointer
     * @param endPoint final coordinates of the primary pointer
     * @param steps number of steps to take to animate dragging
     * @return true if gesture is injected successfully
     */
    public boolean dragWithoutRelease(@NonNull Tuple startPoint,
            @NonNull Tuple endPoint, int steps) {
        PointerProperties ptrProp = getPointerProp(0, MotionEvent.TOOL_TYPE_FINGER);
        PointerCoords ptrCoord = getPointerCoord(startPoint.x, startPoint.y, 1, 1);

        PointerProperties[] ptrProps = new PointerProperties[] { ptrProp };
        PointerCoords[] ptrCoords = new PointerCoords[] { ptrCoord };

        long downTime = SystemClock.uptimeMillis();

        if (!primaryPointerDown(ptrProp, ptrCoord, downTime)) {
            return false;
        }

        // cache the primary pointer info for later potential release
        mPrimaryPtrProp = ptrProp;
        mPrimaryPtrCoord = ptrCoord;
        mPrimaryPtrDownTime = downTime;

        return movePointers(ptrProps, ptrCoords, new Tuple[] { endPoint }, downTime, steps);
    }

    /**
     * Release primary pointer if previous gesture has cached the primary pointer info.
     *
     * @return true if the release was injected successfully
     */
    public boolean releasePrimaryPointer() {
        if (mPrimaryPtrProp != null && mPrimaryPtrCoord != null) {
            return primaryPointerUp(mPrimaryPtrProp, mPrimaryPtrCoord, mPrimaryPtrDownTime);
        }

        return false;
    }

    /**
     * Injects a series of {@link MotionEvent} objects to simulate a pinch gesture.
     *
     * @param startPoint1 initial coordinates of the first pointer
     * @param startPoint2 initial coordinates of the second pointer
     * @param endPoint1 final coordinates of the first pointer
     * @param endPoint2 final coordinates of the second pointer
     * @param steps number of steps to take to animate pinching
     * @return true if gesture is injected successfully
     */
    public boolean pinch(@NonNull Tuple startPoint1, @NonNull Tuple startPoint2,
            @NonNull Tuple endPoint1, @NonNull Tuple endPoint2, int steps) {
        PointerProperties ptrProp1 = getPointerProp(0, MotionEvent.TOOL_TYPE_FINGER);
        PointerProperties ptrProp2 = getPointerProp(1, MotionEvent.TOOL_TYPE_FINGER);

        PointerCoords ptrCoord1 = getPointerCoord(startPoint1.x, startPoint1.y, 1, 1);
        PointerCoords ptrCoord2 = getPointerCoord(startPoint2.x, startPoint2.y, 1, 1);

        PointerProperties[] ptrProps = new PointerProperties[] {
                ptrProp1, ptrProp2
        };

        PointerCoords[] ptrCoords = new PointerCoords[] {
                ptrCoord1, ptrCoord2
        };

        long downTime = SystemClock.uptimeMillis();

        if (!primaryPointerDown(ptrProp1, ptrCoord1, downTime)) {
            return false;
        }

        if (!nonPrimaryPointerDown(ptrProps, ptrCoords, downTime, 1)) {
            return false;
        }

        if (!movePointers(ptrProps, ptrCoords, new Tuple[] { endPoint1, endPoint2 },
                downTime, steps)) {
            return false;
        }

        if (!nonPrimaryPointerUp(ptrProps, ptrCoords, downTime, 1)) {
            return false;
        }

        return primaryPointerUp(ptrProp1, ptrCoord1, downTime);
    }

    private boolean primaryPointerDown(@NonNull PointerProperties prop,
            @NonNull PointerCoords coord, long downTime) {
        MotionEvent event = getMotionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 1,
                new PointerProperties[]{ prop }, new PointerCoords[]{ coord });

        return injectEventSync(event);
    }

    private boolean nonPrimaryPointerDown(@NonNull PointerProperties[] props,
            @NonNull PointerCoords[] coords, long downTime, int index) {
        // at least 2 pointers are needed
        if (props.length != coords.length || coords.length < 2) {
            return false;
        }

        long eventTime = SystemClock.uptimeMillis();

        MotionEvent event = getMotionEvent(downTime, eventTime, MotionEvent.ACTION_POINTER_DOWN
                + (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT), coords.length, props, coords);

        return injectEventSync(event);
    }

    private boolean movePointers(@NonNull PointerProperties[] props,
            @NonNull PointerCoords[] coords, @NonNull Tuple[] endPoints, long downTime, int steps) {
        // the number of endpoints should be the same as the number of pointers
        if (props.length != coords.length || coords.length != endPoints.length) {
            return false;
        }

        // prevent division by 0 and negative number of steps
        if (steps < 1) {
            steps = 1;
        }

        // save the starting points before updating any pointers
        Tuple[] startPoints = new Tuple[coords.length];

        for (int i = 0; i < coords.length; i++) {
            startPoints[i] = new Tuple(coords[i].x, coords[i].y);
        }

        MotionEvent event;
        long eventTime;

        for (int i = 0; i < steps; i++) {
            // inject a delay between movements
            SystemClock.sleep(MOTION_EVENT_INJECTION_DELAY_MILLIS);

            // update the coordinates
            for (int j = 0; j < coords.length; j++) {
                coords[j].x += (endPoints[j].x - startPoints[j].x) / steps;
                coords[j].y += (endPoints[j].y - startPoints[j].y) / steps;
            }

            eventTime = SystemClock.uptimeMillis();

            event = getMotionEvent(downTime, eventTime, MotionEvent.ACTION_MOVE,
                    coords.length, props, coords);

            boolean didInject = injectEventSync(event);

            if (!didInject) {
                return false;
            }
        }

        return true;
    }

    private boolean primaryPointerUp(@NonNull PointerProperties prop,
            @NonNull PointerCoords coord, long downTime) {
        long eventTime = SystemClock.uptimeMillis();

        MotionEvent event = getMotionEvent(downTime, eventTime, MotionEvent.ACTION_UP, 1,
                new PointerProperties[]{ prop }, new PointerCoords[]{ coord });

        return injectEventSync(event);
    }

    private boolean nonPrimaryPointerUp(@NonNull PointerProperties[] props,
            @NonNull PointerCoords[] coords, long downTime, int index) {
        // at least 2 pointers are needed
        if (props.length != coords.length || coords.length < 2) {
            return false;
        }

        long eventTime = SystemClock.uptimeMillis();

        MotionEvent event = getMotionEvent(downTime, eventTime, MotionEvent.ACTION_POINTER_UP
                + (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT), coords.length, props, coords);

        return injectEventSync(event);
    }

    private PointerCoords getPointerCoord(float x, float y, float pressure, float size) {
        PointerCoords ptrCoord = new PointerCoords();
        ptrCoord.x = x;
        ptrCoord.y = y;
        ptrCoord.pressure = pressure;
        ptrCoord.size = size;
        return ptrCoord;
    }

    private PointerProperties getPointerProp(int id, int toolType) {
        PointerProperties ptrProp = new PointerProperties();
        ptrProp.id = id;
        ptrProp.toolType = toolType;
        return ptrProp;
    }

    private static MotionEvent getMotionEvent(long downTime, long eventTime, int action,
            int pointerCount, PointerProperties[] ptrProps, PointerCoords[] ptrCoords) {
        return MotionEvent.obtain(downTime, eventTime, action, pointerCount,
                ptrProps, ptrCoords, 0, 0, 1.0f, 1.0f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private boolean injectEventSync(InputEvent event) {
        return mUiAutomation.injectInputEvent(event, true);
    }
}
