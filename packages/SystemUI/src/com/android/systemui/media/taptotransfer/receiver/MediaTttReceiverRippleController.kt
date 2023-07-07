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
 */
package com.android.systemui.media.taptotransfer.receiver

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.WindowManager
import com.android.settingslib.Utils
import com.android.systemui.R
import javax.inject.Inject

/**
 * A controller responsible for the animation of the ripples shown in media tap-to-transfer on the
 * receiving device.
 */
class MediaTttReceiverRippleController
@Inject
constructor(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    private var maxRippleWidth: Float = 0f
    private var maxRippleHeight: Float = 0f

    /** Expands the icon and main ripple to in-progress state */
    fun expandToInProgressState(
        mainRippleView: ReceiverChipRippleView,
        iconRippleView: ReceiverChipRippleView,
    ) {
        expandRipple(mainRippleView, isIconRipple = false)
        expandRipple(iconRippleView, isIconRipple = true)
    }

    private fun expandRipple(rippleView: ReceiverChipRippleView, isIconRipple: Boolean) {
        if (rippleView.rippleInProgress()) {
            // Skip if ripple is still playing
            return
        }

        // In case the device orientation changes, we need to reset the layout.
        rippleView.addOnLayoutChangeListener(
            View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v == null) return@OnLayoutChangeListener

                val layoutChangedRippleView = v as ReceiverChipRippleView
                if (isIconRipple) {
                    layoutIconRipple(layoutChangedRippleView)
                } else {
                    layoutRipple(layoutChangedRippleView)
                }
                layoutChangedRippleView.invalidate()
            }
        )
        rippleView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(view: View?) {}

                override fun onViewAttachedToWindow(view: View?) {
                    if (view == null) {
                        return
                    }
                    val attachedRippleView = view as ReceiverChipRippleView
                    if (isIconRipple) {
                        layoutIconRipple(attachedRippleView)
                    } else {
                        layoutRipple(attachedRippleView)
                    }
                    attachedRippleView.expandRipple()
                    attachedRippleView.removeOnAttachStateChangeListener(this)
                }
            }
        )
    }

    /** Expands the ripple to cover the screen. */
    fun expandToSuccessState(rippleView: ReceiverChipRippleView, onAnimationEnd: Runnable?) {
        layoutRipple(rippleView, isFullScreen = true)
        rippleView.expandToFull(maxRippleHeight, onAnimationEnd)
    }

    /** Collapses the ripple. */
    fun collapseRipple(rippleView: ReceiverChipRippleView, onAnimationEnd: Runnable? = null) {
        rippleView.collapseRipple(onAnimationEnd)
    }

    private fun layoutRipple(rippleView: ReceiverChipRippleView, isFullScreen: Boolean = false) {
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val height = windowBounds.height().toFloat()
        val width = windowBounds.width().toFloat()

        if (isFullScreen) {
            maxRippleHeight = height * 2f
            maxRippleWidth = width * 2f
        } else {
            maxRippleHeight = getRippleSize()
            maxRippleWidth = getRippleSize()
        }
        rippleView.setMaxSize(maxRippleWidth, maxRippleHeight)
        // Center the ripple on the bottom of the screen in the middle.
        rippleView.setCenter(width * 0.5f, height)
        rippleView.setColor(getRippleColor(), RIPPLE_OPACITY)
    }

    private fun layoutIconRipple(iconRippleView: ReceiverChipRippleView) {
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val height = windowBounds.height().toFloat()
        val width = windowBounds.width().toFloat()
        val radius = getReceiverIconSize().toFloat()

        iconRippleView.setMaxSize(radius * 0.8f, radius * 0.8f)
        iconRippleView.setCenter(
            width * 0.5f,
            height - getReceiverIconSize() * 0.5f - getReceiverIconBottomMargin()
        )
        iconRippleView.setColor(getRippleColor(), RIPPLE_OPACITY)
    }

    private fun getRippleColor(): Int {
        var colorStateList =
            ColorStateList.valueOf(
                Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColorAccent)
            )
        return colorStateList.withLStar(TONE_PERCENT).defaultColor
    }

    /** Returns the size of the ripple. */
    internal fun getRippleSize(): Float {
        return getReceiverIconSize() * 4f
    }

    /** Returns the size of the icon of the receiver. */
    internal fun getReceiverIconSize(): Int {
        return context.resources.getDimensionPixelSize(R.dimen.media_ttt_icon_size_receiver)
    }

    /** Return the bottom margin of the icon of the receiver. */
    internal fun getReceiverIconBottomMargin(): Int {
        // Adding a margin to make sure ripple behind the icon is not cut by the screen bounds.
        return context.resources.getDimensionPixelSize(
            R.dimen.media_ttt_receiver_icon_bottom_margin
        )
    }

    companion object {
        const val RIPPLE_OPACITY = 70
        const val TONE_PERCENT = 95f
    }
}
