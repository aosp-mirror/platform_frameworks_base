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
package com.android.wm.shell.windowdecor.viewholder

import android.animation.ObjectAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Point
import android.hardware.input.InputManager
import android.os.Handler
import android.view.MotionEvent.ACTION_DOWN
import android.view.SurfaceControl
import android.view.View
import android.view.View.OnClickListener
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowManager
import android.widget.ImageButton
import com.android.internal.policy.SystemBarUtils
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.shared.animation.Interpolators
import com.android.wm.shell.windowdecor.WindowManagerWrapper
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer

/**
 * A desktop mode window decoration used when the window is in full "focus" (i.e. fullscreen/split).
 * It hosts a simple handle bar from which to initiate a drag motion to enter desktop mode.
 */
internal class AppHandleViewHolder(
    rootView: View,
    onCaptionTouchListener: View.OnTouchListener,
    onCaptionButtonClickListener: OnClickListener,
    private val windowManagerWrapper: WindowManagerWrapper,
    private val handler: Handler
) : WindowDecorationViewHolder(rootView) {

    companion object {
        private const val CAPTION_HANDLE_ANIMATION_DURATION: Long = 100
    }
    private lateinit var taskInfo: RunningTaskInfo
    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: ImageButton = rootView.requireViewById(R.id.caption_handle)
    private val inputManager = context.getSystemService(InputManager::class.java)
    private var statusBarInputLayerExists = false

    // An invisible View that takes up the same coordinates as captionHandle but is layered
    // above the status bar. The purpose of this View is to receive input intended for
    // captionHandle.
    private var statusBarInputLayer: AdditionalSystemViewContainer? = null

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnClickListener(onCaptionButtonClickListener)
    }

    override fun bindData(
        taskInfo: RunningTaskInfo,
        position: Point,
        width: Int,
        height: Int,
        isCaptionVisible: Boolean
    ) {
        captionHandle.imageTintList = ColorStateList.valueOf(getCaptionHandleBarColor(taskInfo))
        this.taskInfo = taskInfo
        // If handle is not in status bar region(i.e., bottom stage in vertical split),
        // do not create an input layer
        if (position.y >= SystemBarUtils.getStatusBarHeight(context)) return
        if (!isCaptionVisible && statusBarInputLayerExists) {
            disposeStatusBarInputLayer()
            return
        }
        // Input layer view creation / modification takes a significant amount of time;
        // post them so we don't hold up DesktopModeWindowDecoration#relayout.
        if (statusBarInputLayerExists) {
            handler.post { updateStatusBarInputLayer(position) }
        } else {
            // Input layer is created on a delay; prevent multiple from being created.
            statusBarInputLayerExists = true
            handler.post { createStatusBarInputLayer(position, width, height) }
        }
    }

    override fun onHandleMenuOpened() {
        animateCaptionHandleAlpha(startValue = 1f, endValue = 0f)
    }

    override fun onHandleMenuClosed() {
        animateCaptionHandleAlpha(startValue = 0f, endValue = 1f)
    }

    private fun createStatusBarInputLayer(handlePosition: Point,
                                          handleWidth: Int,
                                          handleHeight: Int) {
        if (!Flags.enableHandleInputFix()) return
        statusBarInputLayer = AdditionalSystemViewContainer(context, windowManagerWrapper,
            taskInfo.taskId, handlePosition.x, handlePosition.y, handleWidth, handleHeight,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        val view = statusBarInputLayer?.view ?: error("Unable to find statusBarInputLayer View")
        val lp = statusBarInputLayer?.lp ?: error("Unable to find statusBarInputLayer " +
                "LayoutParams")
        lp.title = "Handle Input Layer of task " + taskInfo.taskId
        lp.setTrustedOverlay()
        // Make this window a spy window to enable it to pilfer pointers from the system-wide
        // gesture listener that receives events before window. This is to prevent notification
        // shade gesture when we swipe down to enter desktop.
        lp.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
        view.setOnHoverListener { _, event ->
            captionHandle.onHoverEvent(event)
        }
        // Caption handle is located within the status bar region, meaning the
        // DisplayPolicy will attempt to transfer this input to status bar if it's
        // a swipe down. Pilfer here to keep the gesture in handle alone.
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == ACTION_DOWN) {
                inputManager.pilferPointers(v.viewRootImpl.inputToken)
            }
            captionHandle.dispatchTouchEvent(event)
            return@setOnTouchListener true
        }
        windowManagerWrapper.updateViewLayout(view, lp)
    }

    private fun updateStatusBarInputLayer(globalPosition: Point) {
        statusBarInputLayer?.setPosition(
            SurfaceControl.Transaction(),
            globalPosition.x.toFloat(),
            globalPosition.y.toFloat()
        ) ?: return
    }

    /**
     * Remove the input layer from [WindowManager]. Should be used when caption handle
     * is not visible.
     */
    fun disposeStatusBarInputLayer() {
        statusBarInputLayerExists = false
        handler.post {
            statusBarInputLayer?.releaseView()
            statusBarInputLayer = null
        }
    }

    private fun getCaptionHandleBarColor(taskInfo: RunningTaskInfo): Int {
        return if (shouldUseLightCaptionColors(taskInfo)) {
            context.getColor(R.color.desktop_mode_caption_handle_bar_light)
        } else {
            context.getColor(R.color.desktop_mode_caption_handle_bar_dark)
        }
    }

    /**
     * Whether the caption items should use the 'light' color variant so that there's good contrast
     * with the caption background color.
     */
    private fun shouldUseLightCaptionColors(taskInfo: RunningTaskInfo): Boolean {
        return taskInfo.taskDescription
            ?.let { taskDescription ->
                if (Color.alpha(taskDescription.statusBarColor) != 0 &&
                    taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
                    Color.valueOf(taskDescription.statusBarColor).luminance() < 0.5
                } else {
                    taskDescription.systemBarsAppearance and APPEARANCE_LIGHT_STATUS_BARS == 0
                }
            } ?: false
    }

    /** Animate appearance/disappearance of caption handle as the handle menu is animated. */
    private fun animateCaptionHandleAlpha(startValue: Float, endValue: Float) {
        val animator =
            ObjectAnimator.ofFloat(captionHandle, View.ALPHA, startValue, endValue).apply {
                duration = CAPTION_HANDLE_ANIMATION_DURATION
                interpolator = Interpolators.FAST_OUT_SLOW_IN
            }
        animator.start()
    }
}
