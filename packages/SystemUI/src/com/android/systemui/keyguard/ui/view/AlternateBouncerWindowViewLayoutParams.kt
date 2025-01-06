/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.view

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

object AlternateBouncerWindowViewLayoutParams {
    val layoutParams: WindowManager.LayoutParams
        get() =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "AlternateBouncerView"
                    fitInsetsTypes = 0 // overrides default, avoiding status bars during layout
                    gravity = Gravity.TOP or Gravity.LEFT
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    privateFlags =
                        WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                            WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
                    // Avoid announcing window title.
                    accessibilityTitle = " "
                }
}
