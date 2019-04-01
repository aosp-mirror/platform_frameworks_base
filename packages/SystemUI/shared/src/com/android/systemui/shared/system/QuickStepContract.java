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

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import android.content.Context;
import android.content.res.Resources;
import android.view.WindowManagerPolicyConstants;

/**
 * Various shared constants between Launcher and SysUI as part of quickstep
 */
public class QuickStepContract {

    public static final String KEY_EXTRA_SYSUI_PROXY = "extra_sysui_proxy";
    public static final String KEY_EXTRA_INPUT_CHANNEL = "extra_input_channel";
    public static final String KEY_EXTRA_WINDOW_CORNER_RADIUS = "extra_window_corner_radius";
    public static final String KEY_EXTRA_SUPPORTS_WINDOW_CORNERS = "extra_supports_window_corners";

    public static final String NAV_BAR_MODE_2BUTTON_OVERLAY =
            WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON_OVERLAY;
    public static final String NAV_BAR_MODE_3BUTTON_OVERLAY =
            WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
    public static final String NAV_BAR_MODE_GESTURAL_OVERLAY =
            WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

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

    /**
     * @return whether this nav bar mode is edge to edge
     */
    public static boolean isGesturalMode(int mode) {
        return mode == NAV_BAR_MODE_GESTURAL;
    }

    /**
     * @return whether the current nav bar mode is gestural
     */
    public static boolean isGesturalMode(Context context) {
        return isGesturalMode(getCurrentInteractionMode(context));
    }

    /**
     * @return whether this nav bar mode is swipe up
     */
    public static boolean isSwipeUpMode(int mode) {
        return mode == NAV_BAR_MODE_2BUTTON;
    }

    /**
     * @return whether the current nav bar mode is swipe up
     */
    public static boolean isSwipeUpMode(Context context) {
        return isSwipeUpMode(getCurrentInteractionMode(context));
    }

    /**
     * @return whether this nav bar mode is 3 button
     */
    public static boolean isLegacyMode(int mode) {
        return mode == NAV_BAR_MODE_3BUTTON;
    }

    /**
     * @return whether this nav bar mode is 3 button
     */
    public static boolean isLegacyMode(Context context) {
        return isLegacyMode(getCurrentInteractionMode(context));
    }

    /**
     * @return the current nav bar interaction mode
     */
    public static int getCurrentInteractionMode(Context context) {
        return context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
    }

    /**
     * @return {@code true} if the navbar can be clicked through
     */
    public static boolean isNavBarClickThrough(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_navBarTapThrough);
    }

    /**
     * @return the edge sensitivity width in px
     */
    public static int getEdgeSensitivityWidth(Context context) {
        return context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.config_backGestureInset);
    }


}
