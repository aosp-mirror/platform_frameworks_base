/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.policy.PhoneWindow;

/**
 * Utility methods for setting up a floating window
 */
public class FloatingWindowUtil {

    /**
     * Convert input dp to pixels given DisplayMetrics
     */
    public static float dpToPx(DisplayMetrics metrics, float dp) {
        return dp * metrics.densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
    }

    /**
     * Sets up window params for a floating window
     */
    public static WindowManager.LayoutParams getFloatingWindowParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                MATCH_PARENT, MATCH_PARENT, /* xpos */ 0, /* ypos */ 0, TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        // This is needed to let touches pass through outside the touchable areas
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        return params;
    }

    /**
     * Constructs a transparent floating window
     */
    public static PhoneWindow getFloatingWindow(Context context) {
        PhoneWindow window = new PhoneWindow(context);
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        return window;
    }


}
