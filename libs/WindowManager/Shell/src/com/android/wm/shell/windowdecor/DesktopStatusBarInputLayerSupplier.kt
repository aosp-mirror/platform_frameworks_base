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

package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.split.SplitScreenConstants
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer

/**
 * Supplier for [AdditionalSystemViewContainer] objects to be used for forwarding input
 * events through status bar to an app handle. Currently supports two simultaneous input layers.
 *
 * The supplier will pick one of two input layer view containers to use: one for tasks in
 * fullscreen or top/left split stage, and one for tasks in right split stage.
 */
class DesktopStatusBarInputLayerSupplier(
    private val context: Context,
    @ShellMainThread handler: Handler
) {
    private val inputLayers: MutableList<AdditionalSystemViewContainer> = mutableListOf()

    init {
        // Post this as creation of the input layer views is a relatively expensive operation.
        handler.post {
            repeat(TOTAL_INPUT_LAYERS) {
                inputLayers.add(createInputLayer())
            }
        }
    }

    private fun createInputLayer(): AdditionalSystemViewContainer {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        )
        lp.title = "Desktop status bar input layer"
        lp.gravity = Gravity.LEFT or Gravity.TOP
        lp.setTrustedOverlay()

        // Make this window a spy window to enable it to pilfer pointers from the system-wide
        // gesture listener that receives events before window. This is to prevent notification
        // shade gesture when we swipe down to enter desktop.
        lp.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val view = View(context)
        view.visibility = View.INVISIBLE
        return AdditionalSystemViewContainer(
            WindowManagerWrapper(
                context.getSystemService<WindowManager>(WindowManager::class.java)
            ),
            view,
            lp
        )
    }

    /**
     * Decide which cached status bar input layer should be used for a decoration, if any.
     *
     * [splitPosition] and [isLeftRightSplit] are used to determine which input layer we use.
     * The first one is reserved for fullscreen tasks or tasks in top/left split,
     * while the second one is exclusively used for tasks in right split stage. Note we care about
     * left-right vs top-bottom split as the bottom stage should not use an input layer.
     */
    fun getStatusBarInputLayer(
        taskInfo: RunningTaskInfo,
        @SplitScreenConstants.SplitPosition splitPosition: Int,
        isLeftRightSplit: Boolean
    ): AdditionalSystemViewContainer? {
        if (!taskInfo.isVisibleRequested) return null
        // Fullscreen and top/left split tasks will use the first input layer.
        if (taskInfo.windowingMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
            || splitPosition == SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
        ) {
            return inputLayers[LEFT_TOP_INPUT_LAYER]
        }
        // Right split tasks will use the second one.
        if (isLeftRightSplit && splitPosition == SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
        ) {
            return inputLayers[RIGHT_SPLIT_INPUT_LAYER]
        }
        // Which leaves bottom split and freeform tasks, which do not need an input layer
        // as the status bar is not blocking them.
        return null
    }

    companion object {
        private const val TOTAL_INPUT_LAYERS = 2
        // Input layer index for fullscreen tasks and tasks in top-left split
        private const val LEFT_TOP_INPUT_LAYER = 0
        // Input layer index for tasks in right split stage. Does not include bottom split as that
        // stage is not blocked by status bar.
        private const val RIGHT_SPLIT_INPUT_LAYER = 1
    }
}
