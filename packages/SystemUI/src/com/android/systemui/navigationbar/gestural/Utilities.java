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

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

public final class Utilities {

    private static final int TRACKPAD_GESTURE_SCALE = 60;

    public static boolean isTrackpadMotionEvent(boolean isTrackpadGestureBackEnabled,
            MotionEvent event) {
        // TODO: ideally should use event.getClassification(), but currently only the move
        // events get assigned the correct classification.
        return isTrackpadGestureBackEnabled
                && (event.getSource() & SOURCE_TOUCHSCREEN) != SOURCE_TOUCHSCREEN;
    }

    public static int getTrackpadScale(Context context) {
        return ViewConfiguration.get(context).getScaledTouchSlop() * TRACKPAD_GESTURE_SCALE;
    }
}
