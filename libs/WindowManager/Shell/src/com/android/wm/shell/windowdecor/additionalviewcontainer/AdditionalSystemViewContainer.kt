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

package com.android.wm.shell.windowdecor.additionalviewcontainer

import android.annotation.LayoutRes
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.wm.shell.windowdecor.WindowManagerWrapper

/**
 * An [AdditionalViewContainer] that uses the system [WindowManager] instance. Intended
 * for view containers that should be above the status bar layer.
 */
class AdditionalSystemViewContainer(
    private val windowManagerWrapper: WindowManagerWrapper,
    override val view: View,
    val lp: LayoutParams
) : AdditionalViewContainer() {

    /** Provide a layout id of a view to inflate for this view container. */
    constructor(
        context: Context,
        windowManagerWrapper: WindowManagerWrapper,
        taskId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        flags: Int,
        @LayoutRes layoutId: Int
    ) : this(
        windowManagerWrapper = windowManagerWrapper,
        view = LayoutInflater.from(context).inflate(layoutId, null /* parent */),
        lp = createLayoutParams(x, y, width, height, flags, taskId)
    )

    /** Provide a view directly for this view container */
    constructor(
        windowManagerWrapper: WindowManagerWrapper,
        taskId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        flags: Int,
        view: View,
        forciblyShownTypes: Int = 0
    ) : this(
        windowManagerWrapper = windowManagerWrapper,
        view = view,
        lp = createLayoutParams(x, y, width, height, flags, taskId).apply {
            this.forciblyShownTypes = forciblyShownTypes
        }
    )

    /** Do not supply a view at all, instead creating the view container with a basic view. */
    constructor(
        context: Context,
        windowManagerWrapper: WindowManagerWrapper,
        taskId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        flags: Int
    ) : this(
        windowManagerWrapper = windowManagerWrapper,
        lp = createLayoutParams(x, y, width, height, flags, taskId),
        view = View(context)
    )

    init {
        windowManagerWrapper.addView(view, lp)
    }

    override fun releaseView() {
        windowManagerWrapper.removeViewImmediate(view)
    }

    override fun setPosition(t: SurfaceControl.Transaction, x: Float, y: Float) {
        lp.apply {
            this.x = x.toInt()
            this.y = y.toInt()
        }
        windowManagerWrapper.updateViewLayout(view, lp)
    }

    class Factory {
        fun create(
            windowManagerWrapper: WindowManagerWrapper,
            taskId: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int,
            view: View,
        ): AdditionalSystemViewContainer =
            AdditionalSystemViewContainer(
                windowManagerWrapper = windowManagerWrapper,
                view = view,
                lp = createLayoutParams(x, y, width, height, flags, taskId)
            )
    }
    companion object {
        fun createLayoutParams(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int,
            taskId: Int
        ): LayoutParams {
            return LayoutParams(
                width, height, x, y,
                LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                flags,
                PixelFormat.TRANSPARENT
            ).apply {
                title = "Additional view container of Task=$taskId"
                gravity = Gravity.LEFT or Gravity.TOP
                setTrustedOverlay()
            }
        }
    }
}
