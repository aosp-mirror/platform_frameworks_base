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
package com.android.systemui.unfold

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.DeviceStateManager.FoldStateListener
import android.view.Surface
import android.view.WindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.unfold.UnfoldTransitionProgressProvider
import com.android.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.LinearLightRevealEffect
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

@SysUISingleton
class UnfoldLightRevealOverlayAnimation @Inject constructor(
    private val context: Context,
    private val deviceStateManager: DeviceStateManager,
    private val unfoldTransitionProgressProvider: UnfoldTransitionProgressProvider,
    @Main private val executor: Executor,
    private val windowManager: WindowManager
) {

    private val transitionListener = TransitionListener()
    private var scrimView: LightRevealScrim? = null

    fun init() {
        deviceStateManager.registerCallback(executor, FoldListener())
        unfoldTransitionProgressProvider.addCallback(transitionListener)
    }

    private inner class TransitionListener : TransitionProgressListener {

        override fun onTransitionProgress(progress: Float) {
            scrimView?.revealAmount = progress
        }

        override fun onTransitionFinished() {
            removeOverlayView()
        }

        override fun onTransitionStarted() {
        }
    }

    private inner class FoldListener : FoldStateListener(context, Consumer { isFolded ->
        if (isFolded) {
            removeOverlayView()
        } else {
            // Add overlay view before starting the transition as soon as we unfolded the device
            addOverlayView()
        }
    })

    private fun addOverlayView() {
        val params: WindowManager.LayoutParams = WindowManager.LayoutParams()
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.format = PixelFormat.TRANSLUCENT

        // TODO(b/193801466): create a separate type for this overlay
        params.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY
        params.title = "Unfold Light Reveal Animation"
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.fitInsetsTypes = 0
        params.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        params.setTrustedOverlay()

        val rotation = windowManager.defaultDisplay.rotation
        val isVerticalFold = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

        val newScrimView = LightRevealScrim(context, null)
            .apply {
                revealEffect = LinearLightRevealEffect(isVerticalFold)
                isScrimOpaqueChangedListener = Consumer {}
                revealAmount = 0f
            }

        val packageName: String = newScrimView.context.opPackageName
        params.packageName = packageName
        params.hideTimeoutMilliseconds = OVERLAY_HIDE_TIMEOUT_MILLIS

        if (scrimView?.parent != null) {
            windowManager.removeView(scrimView)
        }

        this.scrimView = newScrimView

        try {
            windowManager.addView(scrimView, params)
        } catch (e: WindowManager.BadTokenException) {
            e.printStackTrace()
        }
    }

    private fun removeOverlayView() {
        scrimView?.let {
            if (it.parent != null) {
                windowManager.removeViewImmediate(it)
            }
            scrimView = null
        }
    }
}

private const val OVERLAY_HIDE_TIMEOUT_MILLIS = 10_000L
