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

import static android.view.MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT;
import static android.view.MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE;
import static android.view.MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;

import android.view.MotionEvent;

public final class Utilities {

    public static boolean isTrackpadScroll(MotionEvent event) {
        return event.getClassification() == CLASSIFICATION_TWO_FINGER_SWIPE;
    }

    public static boolean isTrackpadThreeFingerSwipe(MotionEvent event) {
        return event.getClassification() == CLASSIFICATION_MULTI_FINGER_SWIPE
                && event.getAxisValue(AXIS_GESTURE_SWIPE_FINGER_COUNT) == 3;
    }
}
