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
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent.ACTION_DOWN
import android.view.SurfaceControl
import android.view.View
import android.view.View.OnClickListener
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
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
) : WindowDecorationViewHolder<AppHandleViewHolder.HandleData>(rootView) {

    companion object {
        private const val CAPTION_HANDLE_ANIMATION_DURATION: Long = 100
    }

    data class HandleData(
        val taskInfo: RunningTaskInfo,
        val position: Point,
        val width: Int,
        val height: Int,
        val isCaptionVisible: Boolean
    ) : Data()

    private lateinit var taskInfo: RunningTaskInfo
    private val position: Point = Point()
    private var width: Int = 0
    private var height: Int = 0
    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: ImageButton = rootView.requireViewById(R.id.caption_handle)
    private val inputManager = context.getSystemService(InputManager::class.java)

    // An invisible View that takes up the same coordinates as captionHandle but is layered
    // above the status bar. The purpose of this View is to receive input intended for
    // captionHandle.
    private var statusBarInputLayer: AdditionalSystemViewContainer? = null

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnClickListener(onCaptionButtonClickListener)
        captionHandle.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                when (eventType) {
                    AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
                    AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                        // Caption Handle itself can't get a11y focus because it's under the status
                        // bar, so pass through TYPE_VIEW_HOVER a11y events to the status bar
                        // input layer, so that it can get a11y focus on the caption handle's behalf
                        statusBarInputLayer?.view?.sendAccessibilityEvent(eventType)
                    }
                    else -> super.sendAccessibilityEvent(host, eventType)
                }
            }
        }
    }

    override fun bindData(data: HandleData) {
        bindData(data.taskInfo, data.position, data.width, data.height, data.isCaptionVisible)
    }

    private fun bindData(
        taskInfo: RunningTaskInfo,
        position: Point,
        width: Int,
        height: Int,
        isCaptionVisible: Boolean
    ) {
        captionHandle.imageTintList = ColorStateList.valueOf(getCaptionHandleBarColor(taskInfo))
        this.taskInfo = taskInfo
        this.position.set(position)
        this.width = width
        this.height = height
        if (!isCaptionVisible && statusBarInputLayer != null) {
            detachStatusBarInputLayer()
            return
        }
    }

    fun bindStatusBarInputLayer(
        statusBarLayer: AdditionalSystemViewContainer
    ) {
        // Input layer view modification takes a significant amount of time;
        // post them so we don't hold up DesktopModeWindowDecoration#relayout.
        if (statusBarLayer == statusBarInputLayer) {
            handler.post { updateStatusBarInputLayer(position) }
            return
        }
        // Remove the old input layer when changing to a new one.
        if (statusBarInputLayer != null) detachStatusBarInputLayer()
        if (statusBarLayer.view.visibility == View.INVISIBLE) {
            statusBarLayer.view.visibility = View.VISIBLE
        }
        statusBarInputLayer = statusBarLayer
        statusBarInputLayer?.let {
            inputLayer -> setupAppHandleA11y(inputLayer.view)
        }
        handler.post {
            val view = statusBarInputLayer?.view
                ?: error("Unable to find statusBarInputLayer View")
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
            view.setOnHoverListener { _, event ->
                captionHandle.onHoverEvent(event)
            }
            val lp = statusBarInputLayer?.view?.layoutParams as WindowManager.LayoutParams
            lp.x = position.x
            lp.y = position.y
            lp.width = width
            lp.height = height
        }
    }

    override fun onHandleMenuOpened() {
        animateCaptionHandleAlpha(startValue = 1f, endValue = 0f)
    }

    override fun onHandleMenuClosed() {
        animateCaptionHandleAlpha(startValue = 0f, endValue = 1f)
    }

    private fun setupAppHandleA11y(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo
            ) {
                // Allow the status bar input layer to be a11y clickable so it can interact with
                // a11y services on behalf of caption handle (due to being under status bar)
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityAction.ACTION_CLICK)
                host.isClickable = true
            }

            override fun performAccessibilityAction(
                host: View,
                action: Int,
                args: Bundle?
            ): Boolean {
                // Passthrough the a11y click action so the caption handle, so that app handle menu
                // is opened on a11y click, similar to a real click
                if (action == AccessibilityAction.ACTION_CLICK.id) {
                    captionHandle.performClick()
                }
                return super.performAccessibilityAction(host, action, args)
            }

            override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onPopulateAccessibilityEvent(host, event)
                // When the status bar input layer is focused, use the content description of the
                // caption handle so that it appears as "App handle" and not "Unlabelled view"
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                    event.text.add(captionHandle.contentDescription)
                }
            }
        }

        // Update a11y action text so that Talkback announces "Press double tap to open app handle
        // menu" while focused on status bar input layer
        ViewCompat.replaceAccessibilityAction(
            view, AccessibilityActionCompat.ACTION_CLICK, "Open app handle menu", null
        )
    }

    private fun updateStatusBarInputLayer(globalPosition: Point) {
        statusBarInputLayer?.setPosition(
            SurfaceControl.Transaction(),
            globalPosition.x.toFloat(),
            globalPosition.y.toFloat()
        ) ?: return
    }

    /**
     * Remove the input listeners from the input layer and remove it from this view holder.
     */
    fun detachStatusBarInputLayer() {
        statusBarInputLayer?.view?.setOnTouchListener(null)
        statusBarInputLayer?.view?.setOnHoverListener(null)
        statusBarInputLayer = null
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
                    taskInfo.windowingMode == WINDOWING_MODE_FREEFORM
                ) {
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
