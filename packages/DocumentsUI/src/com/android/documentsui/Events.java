/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Utility code for dealing with MotionEvents.
 */
final class Events {

    /**
     * Returns true if event was triggered by a mouse.
     */
    static boolean isMouseEvent(MotionEvent e) {
        return isMouseType(e.getToolType(0));
    }

    /**
     * Returns true if event was triggered by a finger or stylus touch.
     */
    static boolean isTouchEvent(MotionEvent e) {
        return isTouchType(e.getToolType(0));
    }

    /**
     * Returns true if event was triggered by a mouse.
     */
    static boolean isMouseType(int toolType) {
        return toolType == MotionEvent.TOOL_TYPE_MOUSE;
    }

    /**
     * Returns true if event was triggered by a finger or stylus touch.
     */
    static boolean isTouchType(int toolType) {
        return toolType == MotionEvent.TOOL_TYPE_FINGER
                || toolType == MotionEvent.TOOL_TYPE_STYLUS;
    }

    /**
     * Returns true if the shift is pressed.
     */
    boolean isShiftPressed(MotionEvent e) {
        return hasShiftBit(e.getMetaState());
    }

    /**
     * Returns true if the "SHIFT" bit is set.
     */
    static boolean hasShiftBit(int metaState) {
        return (metaState & KeyEvent.META_SHIFT_ON) != 0;
    }
}
