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

import android.annotation.IntDef;
import android.content.res.Resources;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.policy.ScreenDecorationsUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Various shared constants between Launcher and SysUI as part of quickstep
 */
public class QuickStepContract {

    public static final String KEY_EXTRA_SYSUI_PROXY = "extra_sysui_proxy";
    public static final String KEY_EXTRA_INPUT_CHANNEL = "extra_input_channel";
    public static final String KEY_EXTRA_INPUT_MONITOR = "extra_input_monitor";
    public static final String KEY_EXTRA_WINDOW_CORNER_RADIUS = "extra_window_corner_radius";
    public static final String KEY_EXTRA_SUPPORTS_WINDOW_CORNERS = "extra_supports_window_corners";

    public static final String NAV_BAR_MODE_2BUTTON_OVERLAY =
            WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON_OVERLAY;
    public static final String NAV_BAR_MODE_3BUTTON_OVERLAY =
            WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
    public static final String NAV_BAR_MODE_GESTURAL_OVERLAY =
            WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

    public static final int SYSUI_STATE_SCREEN_PINNING = 1 << 0;
    public static final int SYSUI_STATE_NAV_BAR_HIDDEN = 1 << 1;
    public static final int SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED = 1 << 2;
    public static final int SYSUI_STATE_BOUNCER_SHOWING = 1 << 3;
    public static final int SYSUI_STATE_A11Y_BUTTON_CLICKABLE = 1 << 4;
    public static final int SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE = 1 << 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SYSUI_STATE_SCREEN_PINNING,
            SYSUI_STATE_NAV_BAR_HIDDEN,
            SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
            SYSUI_STATE_BOUNCER_SHOWING,
            SYSUI_STATE_A11Y_BUTTON_CLICKABLE,
            SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE
    })
    public @interface SystemUiStateFlags {}

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
     * @return whether this nav bar mode is swipe up
     */
    public static boolean isSwipeUpMode(int mode) {
        return mode == NAV_BAR_MODE_2BUTTON;
    }

    /**
     * @return whether this nav bar mode is 3 button
     */
    public static boolean isLegacyMode(int mode) {
        return mode == NAV_BAR_MODE_3BUTTON;
    }

    /**
     * Corner radius that should be used on windows in order to cover the display.
     * These values are expressed in pixels because they should not respect display or font
     * scaling, this means that we don't have to reload them on config changes.
     */
    public static float getWindowCornerRadius(Resources resources) {
        return ScreenDecorationsUtils.getWindowCornerRadius(resources);
    }

    /**
     * If live rounded corners are supported on windows.
     */
    public static boolean supportsRoundedCornersOnWindows(Resources resources) {
        return ScreenDecorationsUtils.supportsRoundedCornersOnWindows(resources);
    }
}
