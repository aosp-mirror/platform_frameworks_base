/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.view

import android.graphics.PixelFormat
import android.graphics.Point
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import com.android.systemui.biometrics.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import javax.inject.Inject

private const val TAG = "SideFpsProgressBar"

const val progressBarHeight = 100

@SysUISingleton
class SideFpsProgressBar
@Inject
constructor(
    private val layoutInflater: LayoutInflater,
    private val windowManager: WindowManager,
) {
    private var progressBarWidth = 200
    fun updateView(
        visible: Boolean,
        location: Point,
        shouldRotate90Degrees: Boolean,
        progressBarWidth: Int
    ) {
        if (visible) {
            this.progressBarWidth = progressBarWidth
            createAndShowOverlay(location, shouldRotate90Degrees)
        } else {
            hideOverlay()
        }
    }

    fun hideOverlay() {
        overlayView = null
    }

    private val overlayViewParams =
        WindowManager.LayoutParams(
                progressBarHeight,
                progressBarWidth,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS,
                PixelFormat.TRANSPARENT
            )
            .apply {
                title = TAG
                fitInsetsTypes = 0 // overrides default, avoiding status bars during layout
                gravity = Gravity.TOP or Gravity.LEFT
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags =
                    WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                        WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
            }

    private var overlayView: View? = null
        set(value) {
            field?.let { oldView -> windowManager.removeView(oldView) }
            field = value
            field?.let { newView -> windowManager.addView(newView, overlayViewParams) }
        }

    private fun createAndShowOverlay(
        fingerprintSensorLocation: Point,
        shouldRotate90Degrees: Boolean
    ) {
        if (overlayView == null) {
            overlayView = layoutInflater.inflate(R.layout.sidefps_progress_bar, null, false)
        }
        overlayViewParams.x = fingerprintSensorLocation.x
        overlayViewParams.y = fingerprintSensorLocation.y
        if (shouldRotate90Degrees) {
            overlayView?.rotation = 270.0f
            overlayViewParams.width = progressBarHeight
            overlayViewParams.height = progressBarWidth
        } else {
            overlayView?.rotation = 0.0f
            overlayViewParams.width = progressBarWidth
            overlayViewParams.height = progressBarHeight
        }
        windowManager.updateViewLayout(overlayView, overlayViewParams)
    }

    fun setProgress(value: Float) {
        overlayView
            ?.findViewById<ProgressBar?>(R.id.side_fps_progress_bar)
            ?.setProgress((value * 100).toInt(), false)
    }
}
