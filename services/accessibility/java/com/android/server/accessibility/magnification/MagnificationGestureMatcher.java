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

package com.android.server.accessibility.magnification;

import android.annotation.IntDef;
import android.content.Context;
import android.view.ViewConfiguration;

import com.android.internal.R;
import com.android.server.accessibility.gestures.GestureMatcher;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class to define gesture id of {@link GestureMatcher} for magnification.
 *
 */
class MagnificationGestureMatcher {

    private static final int GESTURE_BASE = 100;
    public static final int GESTURE_TWO_FINGERS_DOWN_OR_SWIPE = GESTURE_BASE + 1;
    public static final int GESTURE_SWIPE = GESTURE_BASE + 2;
    public static final int GESTURE_SINGLE_TAP = GESTURE_BASE + 3;
    public static final int GESTURE_SINGLE_TAP_AND_HOLD = GESTURE_BASE + 4;
    public static final int GESTURE_TRIPLE_TAP = GESTURE_BASE + 5;
    public static final int GESTURE_TRIPLE_TAP_AND_HOLD = GESTURE_BASE + 6;

    @IntDef(prefix = {"GESTURE_MAGNIFICATION_"}, value = {
            GESTURE_TWO_FINGERS_DOWN_OR_SWIPE,
            GESTURE_SWIPE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface GestureId {
    }

    /**
     * Returns the string representation of a gesture id.
     * @param gestureId the gesture Id.
     * @return "none" if the id is not listed in {@link GestureId}.
     */
    static String gestureIdToString(@GestureId int gestureId) {
        switch (gestureId) {
            case GESTURE_SWIPE:
                return "GESTURE_SWIPE";
            case GESTURE_TWO_FINGERS_DOWN_OR_SWIPE:
                return "GESTURE_TWO_FINGERS_DOWN_OR_SWIPE";
            case GESTURE_SINGLE_TAP:
                return "GESTURE_SINGLE_TAP";
            case GESTURE_SINGLE_TAP_AND_HOLD:
                return "GESTURE_SINGLE_TAP_AND_HOLD";
            case GESTURE_TRIPLE_TAP:
                return "GESTURE_TRIPLE_TAP";
            case GESTURE_TRIPLE_TAP_AND_HOLD:
                return "GESTURE_TRIPLE_TAP_AND_HOLD";
        }
        return "none";
    }

    /**
     * @param context
     * @return the duration in milliseconds between the first tap's down event and
     * the second tap's down event to be considered that the user is going to performing
     *  panning/scaling gesture.
     */
    static int getMagnificationMultiTapTimeout(Context context) {
        return ViewConfiguration.getDoubleTapTimeout() + context.getResources().getInteger(
                R.integer.config_screen_magnification_multi_tap_adjustment);
    }
}
