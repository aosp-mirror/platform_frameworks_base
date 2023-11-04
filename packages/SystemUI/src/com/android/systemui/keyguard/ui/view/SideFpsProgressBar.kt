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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.ProgressBar
import androidx.core.view.isGone
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import javax.inject.Inject

private const val TAG = "SideFpsProgressBar"

@SysUISingleton
class SideFpsProgressBar
@Inject
constructor(
    private val layoutInflater: LayoutInflater,
    private val windowManager: WindowManager,
) {
    private var overlayView: View? = null

    fun updateView(
        visible: Boolean,
        viewLeftTopLocation: Point,
        progressBarWidth: Int,
        progressBarHeight: Int,
        rotation: Float,
    ) {
        if (visible) {
            createAndShowOverlay(viewLeftTopLocation, rotation, progressBarWidth, progressBarHeight)
        } else {
            hide()
        }
    }

    fun hide() {
        progressBar?.isGone = true
    }

    private val overlayViewParams =
        WindowManager.LayoutParams(
                // overlay is always full screen
                MATCH_PARENT,
                MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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

    private fun createAndShowOverlay(
        viewLeftTop: Point,
        rotation: Float,
        progressBarLength: Int,
        progressBarThickness: Int,
    ) {
        if (overlayView == null) {
            overlayView = layoutInflater.inflate(R.layout.sidefps_progress_bar, null, false)
            windowManager.addView(overlayView, overlayViewParams)
            progressBar?.pivotX = 0.0f
            progressBar?.pivotY = 0.0f
        }
        progressBar?.layoutParams?.width = progressBarLength
        progressBar?.layoutParams?.height = progressBarThickness
        progressBar?.translationX = viewLeftTop.x.toFloat()
        progressBar?.translationY = viewLeftTop.y.toFloat()
        progressBar?.rotation = rotation
        progressBar?.isGone = false
        overlayView?.requestLayout()
    }

    fun setProgress(value: Float) {
        progressBar?.setProgress((value * 100).toInt(), false)
    }

    private val progressBar: ProgressBar?
        get() = overlayView?.findViewById(R.id.side_fps_progress_bar)
}
