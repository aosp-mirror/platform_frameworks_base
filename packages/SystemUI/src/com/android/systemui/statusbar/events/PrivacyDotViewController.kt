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

package com.android.systemui.statusbar.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.UiThread
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

import com.android.systemui.animation.Interpolators
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher
import com.android.systemui.statusbar.phone.StatusBarMarginUpdatedListener

import java.lang.IllegalStateException
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Understands how to keep the persistent privacy dot in the corner of the screen in
 * ScreenDecorations, which does not rotate with the device.
 *
 * The basic principle here is that each dot will sit in a box that is equal to the margins of the
 * status bar (specifically the status_bar_contents view in PhoneStatusBarView). Each dot container
 * will have its gravity set towards the corner (i.e., top-right corner gets top|right gravity), and
 * the contained ImageView will be set to center_vertical and away from the corner horizontally. The
 * Views will match the status bar top padding and status bar height so that the dot can appear to
 * reside directly after the status bar system contents (basically to the right of the battery).
 *
 * NOTE: any operation that modifies views directly must run on the provided executor, because
 * these views are owned by ScreenDecorations and it runs in its own thread
 */

@SysUISingleton
class PrivacyDotViewController @Inject constructor(
    @Main private val mainExecutor: Executor,
    private val locationPublisher: StatusBarLocationPublisher,
    private val animationScheduler: SystemStatusAnimationScheduler
) {
    private var rotation = 0
    private var leftSize = 0
    private var rightSize = 0

    private var sbHeightPortrait = 0
    private var sbHeightLandscape = 0

    private var hasMultipleHeights = false
    private var needsHeightUpdate = false
    private var needsRotationUpdate = false
    private var needsMarginUpdate = false

    private lateinit var tl: View
    private lateinit var tr: View
    private lateinit var bl: View
    private lateinit var br: View

    // Track which corner is active (based on orientation + RTL)
    private var designatedCorner: View? = null

    // Privacy dots are created in ScreenDecoration's UiThread, which is not the main thread
    private var uiExecutor: Executor? = null

    private val views: Sequence<View>
        get() = if (!this::tl.isInitialized) sequenceOf() else sequenceOf(tl, tr, br, bl)

    init {
        locationPublisher.addCallback(object : StatusBarMarginUpdatedListener {
            override fun onStatusBarMarginUpdated(marginLeft: Int, marginRight: Int) {
                setStatusBarMargins(marginLeft, marginRight)
            }
        })
    }

    fun setUiExecutor(e: Executor) {
        uiExecutor = e
    }

    @UiThread
    fun updateRotation(rot: Int) {
        dlog("updateRotation: ")
        if (rot == rotation) {
            return
        }

        // A rotation has started, hide the views to avoid flicker
        setCornerVisibilities(View.INVISIBLE)

        if (hasMultipleHeights && (rotation % 2) != (rot % 2)) {
            // we've changed from vertical to horizontal; update status bar height
            needsHeightUpdate = true
        }

        rotation = rot
        needsRotationUpdate = true
    }

    @UiThread
    private fun updateHeights(rot: Int) {
        val height = when (rot) {
            0, 2 -> sbHeightPortrait
            1, 3 -> sbHeightLandscape
            else -> 0
        }

        views.forEach { it.layoutParams.height = height }
    }

    // Update the gravity and margins of the privacy views
    @UiThread
    private fun updateRotations() {
        // To keep a view in the corner, its gravity is always the description of its current corner
        // Therefore, just figure out which view is in which corner. This turns out to be something
        // like (myCorner - rot) mod 4, where topLeft = 0, topRight = 1, etc. and portrait = 0, and
        // rotating the device counter-clockwise increments rotation by 1

        views.forEach { corner ->
            val rotatedCorner = rotatedCorner(cornerForView(corner))
            (corner.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = rotatedCorner.toGravity()
            }

            // Set the dot's view gravity to hug the status bar
            (corner.findViewById<View>(R.id.privacy_dot)
                    .layoutParams as FrameLayout.LayoutParams)
                        .gravity = rotatedCorner.innerGravity()
        }
    }

    @UiThread
    private fun updateCornerSizes() {
        views.forEach { corner ->
            val rotatedCorner = rotatedCorner(cornerForView(corner))
            val w = widthForCorner(rotatedCorner)
            Log.d(TAG, "updateCornerSizes: setting (${cornerForView(corner)}) to $w")
            (corner.layoutParams as FrameLayout.LayoutParams).width = w
            corner.requestLayout()
        }
    }

    // Designated view will be the one at statusbar's view.END
    @UiThread
    private fun selectDesignatedCorner(): View? {
        if (!this::tl.isInitialized) {
            return null
        }

        val isRtl = tl.isLayoutRtl

        return when (rotation) {
            0 -> if (isRtl) tl else tr
            1 -> if (isRtl) tr else br
            2 -> if (isRtl) br else bl
            3 -> if (isRtl) bl else tl
            else -> throw IllegalStateException("unknown rotation")
        }
    }

    // Track the current designated corner and maybe animate to a new rotation
    @UiThread
    private fun updateDesignatedCorner(newCorner: View) {
        designatedCorner = newCorner

        if (animationScheduler.hasPersistentDot) {
            fadeInDot()
        }
    }

    @UiThread
    private fun fadeInDot() {
        designatedCorner?.let { dot ->
            dot.visibility = View.VISIBLE
            dot.alpha = 0f
            dot.animate()
                .alpha(1.0f)
                .setDuration(300)
                .start()
        }
    }

    @UiThread
    private fun setCornerVisibilities(vis: Int) {
        views.forEach { corner ->
            corner.visibility = vis
        }
    }

    private fun cornerForView(v: View): Int {
        return when (v) {
            tl -> TOP_LEFT
            tr -> TOP_RIGHT
            bl -> BOTTOM_LEFT
            br -> BOTTOM_RIGHT
            else -> throw IllegalArgumentException("not a corner view")
        }
    }

    private fun rotatedCorner(corner: Int): Int {
        var modded = corner - rotation
        if (modded < 0) {
            modded += 4
        }

        return modded
    }

    private fun widthForCorner(corner: Int): Int {
        return when (corner) {
            TOP_LEFT, BOTTOM_LEFT -> leftSize
            TOP_RIGHT, BOTTOM_RIGHT -> rightSize
            else -> throw IllegalArgumentException("Unknown corner")
        }
    }

    fun initialize(topLeft: View, topRight: View, bottomLeft: View, bottomRight: View) {
        if (this::tl.isInitialized && this::tr.isInitialized &&
                this::bl.isInitialized && this::br.isInitialized) {
            if (tl == topLeft && tr == topRight && bl == bottomLeft && br == bottomRight) {
                return
            }
        }

        tl = topLeft
        tr = topRight
        bl = bottomLeft
        br = bottomRight

        designatedCorner = selectDesignatedCorner()
        mainExecutor.execute {
            animationScheduler.addCallback(systemStatusAnimationCallback)
        }
    }

    /**
     * Set the status bar height in portrait and landscape, in pixels. If they are the same you can
     * pass the same value twice
     */
    fun setStatusBarHeights(portrait: Int, landscape: Int) {
        sbHeightPortrait = portrait
        sbHeightLandscape = landscape

        hasMultipleHeights = portrait != landscape
    }

    /**
     * The dot view containers will fill the margin in order to position the dots correctly
     *
     * @param left the space between the status bar contents and the left side of the screen
     * @param right space between the status bar contents and the right side of the screen
     */
    private fun setStatusBarMargins(left: Int, right: Int) {
        leftSize = left
        rightSize = right

        needsMarginUpdate = true

        // Margins come after PhoneStatusBarView does a layout pass, and so will always happen
        // after rotation changes. It is safe to execute the updates from here
        uiExecutor?.execute {
            doUpdates(needsRotationUpdate, needsHeightUpdate, needsMarginUpdate)
        }
    }

    private fun doUpdates(rot: Boolean, height: Boolean, width: Boolean) {
        dlog("doUpdates: ")
        var newDesignatedCorner: View? = null

        if (rot) {
            needsRotationUpdate = false
            updateRotations()
            newDesignatedCorner = selectDesignatedCorner()
        }

        if (height) {
            needsHeightUpdate = false
            updateHeights(rotation)
        }

        if (width) {
            needsMarginUpdate = false
            updateCornerSizes()
        }

        if (newDesignatedCorner != null && newDesignatedCorner != designatedCorner) {
            updateDesignatedCorner(newDesignatedCorner)
        }
    }

    private val systemStatusAnimationCallback: SystemStatusAnimationCallback =
            object : SystemStatusAnimationCallback {
        override fun onSystemStatusAnimationTransitionToPersistentDot(
            showAnimation: Boolean
        ): Animator? {
            if (designatedCorner == null) {
                return null
            } else if (!showAnimation) {
                uiExecutor?.execute { fadeInDot() }
                return null
            }

            val alpha = ObjectAnimator.ofFloat(
                    designatedCorner, "alpha", 0f, 1f)
            alpha.duration = DURATION
            alpha.interpolator = Interpolators.ALPHA_OUT
            alpha.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    uiExecutor?.execute { designatedCorner?.visibility = View.VISIBLE }
                }
            })
            return alpha
        }

        override fun onHidePersistentDot(): Animator? {
            if (designatedCorner == null) {
                return null
            }

            val alpha = ObjectAnimator.ofFloat(
                    designatedCorner, "alpha", 1f, 0f)
            alpha.duration = DURATION
            alpha.interpolator = Interpolators.ALPHA_OUT
            alpha.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    uiExecutor?.execute { designatedCorner?.visibility = View.INVISIBLE }
                }
            })
            alpha.start()
            return null
        }
    }
}

private fun dlog(s: String) {
    if (DEBUG) {
        Log.d(TAG, s)
    }
}

const val TOP_LEFT = 0
const val TOP_RIGHT = 1
const val BOTTOM_RIGHT = 2
const val BOTTOM_LEFT = 3
private const val DURATION = 160L
private const val TAG = "PrivacyDotViewController"
private const val DEBUG = false

private fun Int.toGravity(): Int {
    return when (this) {
        TOP_LEFT -> Gravity.TOP or Gravity.LEFT
        TOP_RIGHT -> Gravity.TOP or Gravity.RIGHT
        BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.LEFT
        BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.RIGHT
        else -> throw IllegalArgumentException("Not a corner")
    }
}

private fun Int.innerGravity(): Int {
    return when (this) {
        TOP_LEFT -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
        TOP_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.LEFT
        BOTTOM_LEFT -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
        BOTTOM_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.LEFT
        else -> throw IllegalArgumentException("Not a corner")
    }
}
