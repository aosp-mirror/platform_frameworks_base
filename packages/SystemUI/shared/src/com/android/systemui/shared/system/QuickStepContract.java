/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.shared.system;

import android.content.res.Resources;

/**
 * Various shared constants between Launcher and SysUI as part of quickstep
 */
public class QuickStepContract {

    public static final String KEY_EXTRA_SYSUI_PROXY = "extra_sysui_proxy";
    public static final String KEY_EXTRA_INPUT_CHANNEL = "extra_input_channel";
    public static final String KEY_EXTRA_WINDOW_CORNER_RADIUS = "extra_window_corner_radius";
    public static final String KEY_EXTRA_SUPPORTS_WINDOW_CORNERS = "extra_supports_window_corners";

    /**
     * Touch slopes and thresholds for quick step operations. Drag slop is the point where the
     * home button press/long press over are ignored and will start to drag when exceeded and the
     * touch slop is when the respected operation will occur when exceeded. Touch slop must be
     * larger than the drag slop.
     */
    public static int getQuickStepDragSlopPx() {
        return convertDpToPixel(10);
    }

    public static int getQuickStepTouchSlopPx() {
        return convertDpToPixel(24);
    }

    public static int getQuickScrubTouchSlopPx() {
        return convertDpToPixel(24);
    }

    private static int convertDpToPixel(float dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }
}
