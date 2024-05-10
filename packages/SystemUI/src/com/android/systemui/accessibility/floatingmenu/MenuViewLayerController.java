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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.Flags;
import com.android.systemui.util.settings.SecureSettings;

/**
 * Controls the {@link MenuViewLayer} whether to be attached to the window via the interface
 * of {@link IAccessibilityFloatingMenu}.
 */
class MenuViewLayerController implements IAccessibilityFloatingMenu {
    private final WindowManager mWindowManager;
    private final MenuViewLayer mMenuViewLayer;
    private boolean mIsShowing;

    MenuViewLayerController(Context context, WindowManager windowManager,
            AccessibilityManager accessibilityManager, SecureSettings secureSettings) {
        mWindowManager = windowManager;
        mMenuViewLayer = new MenuViewLayer(context, windowManager, accessibilityManager, this,
                secureSettings);
    }

    @Override
    public boolean isShowing() {
        return mIsShowing;
    }

    @Override
    public void show() {
        if (isShowing()) {
            return;
        }

        mIsShowing = true;
        mWindowManager.addView(mMenuViewLayer, createDefaultLayerLayoutParams());
    }

    @Override
    public void hide() {
        if (!isShowing()) {
            return;
        }

        mIsShowing = false;
        mWindowManager.removeView(mMenuViewLayer);
    }

    private static WindowManager.LayoutParams createDefaultLayerLayoutParams() {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.receiveInsetsIgnoringZOrder = true;
        params.privateFlags |= PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
        params.windowAnimations = android.R.style.Animation_Translucent;
        // Insets are configured to allow the menu to display over navigation and system bars.
        if (Flags.floatingMenuOverlapsNavBarsFlag()) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            params.setFitInsetsTypes(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        }
        return params;
    }
}
