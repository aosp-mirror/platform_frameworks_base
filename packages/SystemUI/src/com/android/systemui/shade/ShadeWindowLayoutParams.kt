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

package com.android.systemui.shade

import android.content.Context
import android.graphics.PixelFormat
import android.os.Binder
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams
import com.android.systemui.scene.shared.flag.SceneContainerFlag

object ShadeWindowLayoutParams {
    /**
     * Creates [LayoutParams] for the shade window.
     *
     * This is extracted to a single place as those layout params will be used by several places:
     * - When sysui starts, and the shade is added the first time
     * - When the shade moves to a different window (e.g. while an external display is connected)
     */
    fun create(context: Context): LayoutParams {
        return LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_NOTIFICATION_SHADE,
                LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING or
                    LayoutParams.FLAG_SPLIT_TOUCH or
                    LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                // Now that the notification shade encompasses the sliding panel and its
                // translucent backdrop, the entire thing is made TRANSLUCENT and is
                // hardware-accelerated.
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                token = Binder()
                gravity = Gravity.TOP
                fitInsetsTypes = 0
                title = "NotificationShade"
                packageName = context.packageName
                layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags = privateFlags or LayoutParams.PRIVATE_FLAG_OPTIMIZE_MEASURE
                if (SceneContainerFlag.isEnabled) {
                    // This prevents the appearance and disappearance of the software keyboard (also
                    // known as the "IME") from scrolling/panning the window to make room for the
                    // keyboard.
                    //
                    // The scene container logic does its own adjustment and animation when the IME
                    // appears or disappears.
                    softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                }
            }
    }
}
