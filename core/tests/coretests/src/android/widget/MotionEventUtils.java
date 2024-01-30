/*
 * Copyright 2023 The Android Open Source Project
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

package android.widget;

import static android.view.InputDevice.SOURCE_CLASS_POINTER;
import static android.view.InputDevice.SOURCE_ROTARY_ENCODER;
import static android.view.MotionEvent.ACTION_SCROLL;
import static android.view.MotionEvent.AXIS_HSCROLL;
import static android.view.MotionEvent.AXIS_SCROLL;
import static android.view.MotionEvent.AXIS_VSCROLL;

import android.view.MotionEvent;

/** Test utilities for {@link MotionEvent}s. */
public class MotionEventUtils {

    /** Creates a test {@link MotionEvent} from a {@link SOURCE_ROTARY_ENCODER}. */
    public static MotionEvent createRotaryEvent(float scroll) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(AXIS_SCROLL, scroll);

        return createGenericMotionEvent(SOURCE_ROTARY_ENCODER, ACTION_SCROLL, coords);
    }

    /** Creates a test {@link MotionEvent} from a {@link SOURCE_CLASS_POINTER}. */
    public static MotionEvent createGenericPointerEvent(float hScroll, float vScroll) {
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(AXIS_HSCROLL, hScroll);
        coords.setAxisValue(AXIS_VSCROLL, vScroll);

        return createGenericMotionEvent(SOURCE_CLASS_POINTER, ACTION_SCROLL, coords);
    }

    private static MotionEvent createGenericMotionEvent(
            int source, int action, MotionEvent.PointerCoords coords) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;

        return MotionEvent.obtain(
                /* downTime= */ 0,
                /* eventTime= */ 100,
                action,
                /* pointerCount= */ 1,
                new MotionEvent.PointerProperties[] {props},
                new MotionEvent.PointerCoords[] {coords},
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 0,
                /* yPrecision= */ 0,
                /* deviceId= */ 1,
                /* edgeFlags= */ 0,
                source,
                /* flags= */ 0);
    }
}
