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

package com.android.systemui.media.taptotransfer.common

import android.annotation.LayoutRes
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import com.android.internal.widget.CachingIconView
import com.android.systemui.R

/**
 * A superclass controller that provides common functionality for showing chips on the sender device
 * and the receiver device.
 *
 * Subclasses need to override and implement [updateChipView], which is where they can control what
 * gets displayed to the user.
 */
abstract class MediaTttChipControllerCommon<T : MediaTttChipState>(
    private val context: Context,
    private val windowManager: WindowManager,
    @LayoutRes private val chipLayoutRes: Int
) {
    /** The window layout parameters we'll use when attaching the view to a window. */
    @SuppressLint("WrongConstant") // We're allowed to use TYPE_VOLUME_OVERLAY
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        title = "Media Tap-To-Transfer Chip View"
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /** The chip view currently being displayed. Null if the chip is not being displayed. */
    var chipView: ViewGroup? = null

    /**
     * Displays the chip with the current state.
     *
     * This method handles inflating and attaching the view, then delegates to [updateChipView] to
     * display the correct information in the chip.
     */
    fun displayChip(chipState: T) {
        val oldChipView = chipView
        if (chipView == null) {
            chipView = LayoutInflater
                .from(context)
                .inflate(chipLayoutRes, null) as ViewGroup
        }
        val currentChipView = chipView!!

        updateChipView(chipState, currentChipView)

        // Add view if necessary
        if (oldChipView == null) {
            windowManager.addView(chipView, windowLayoutParams)
        }
    }


    /** Hides the chip. */
    fun removeChip() {
        if (chipView == null) { return }
        windowManager.removeView(chipView)
        chipView = null
    }

    /**
     * A method implemented by subclasses to update [currentChipView] based on [chipState].
     */
    abstract fun updateChipView(chipState: T, currentChipView: ViewGroup)

    /**
     * An internal method to set the icon on the view.
     *
     * This is in the common superclass since both the sender and the receiver show an icon.
     */
    internal fun setIcon(chipState: T, currentChipView: ViewGroup) {
        currentChipView.findViewById<CachingIconView>(R.id.app_icon).apply {
            this.setImageDrawable(chipState.appIconDrawable)
        }
    }
}
