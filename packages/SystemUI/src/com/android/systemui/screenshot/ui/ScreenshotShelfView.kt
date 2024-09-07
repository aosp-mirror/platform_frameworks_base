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

package com.android.systemui.screenshot.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.Region
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.systemui.res.R
import com.android.systemui.screenshot.FloatingWindowUtil

class ScreenshotShelfView(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    lateinit var screenshotPreview: ImageView
    lateinit var blurredScreenshotPreview: ImageView
    private lateinit var screenshotStatic: ViewGroup
    var onTouchInterceptListener: ((MotionEvent) -> Boolean)? = null

    var userInteractionCallback: (() -> Unit)? = null

    private val displayMetrics = context.resources.displayMetrics
    private val tmpRect = Rect()
    private lateinit var actionsContainerBackground: View
    private lateinit var actionsContainer: View
    private lateinit var dismissButton: View

    // Prepare an internal `GestureDetector` to determine when we can initiate a touch-interception
    // session (with the client's provided `onTouchInterceptListener`). We delegate out to their
    // listener only for gestures that can't be handled by scrolling our `actionsContainer`.
    private val gestureDetector =
        GestureDetector(
            context,
            object : SimpleOnGestureListener() {
                override fun onScroll(
                    ev1: MotionEvent?,
                    ev2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    actionsContainer.getBoundsOnScreen(tmpRect)
                    val touchedInActionsContainer =
                        tmpRect.contains(ev2.rawX.toInt(), ev2.rawY.toInt())
                    val canHandleInternallyByScrolling =
                        touchedInActionsContainer
                        && actionsContainer.canScrollHorizontally(distanceX.toInt())
                    return !canHandleInternallyByScrolling
                }
            }
        )

    init {

        // Delegate to the client-provided `onTouchInterceptListener` if we've already initiated
        // touch-interception.
        setOnTouchListener({ _: View, ev: MotionEvent ->
            userInteractionCallback?.invoke()
            onTouchInterceptListener?.invoke(ev) ?: false
        })

        gestureDetector.setIsLongpressEnabled(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Get focus so that the key events go to the layout.
        isFocusableInTouchMode = true
        screenshotPreview = requireViewById(R.id.screenshot_preview)
        blurredScreenshotPreview = requireViewById(R.id.screenshot_preview_blur)
        screenshotStatic = requireViewById(R.id.screenshot_static)
        actionsContainerBackground = requireViewById(R.id.actions_container_background)
        actionsContainer = requireViewById(R.id.actions_container)
        dismissButton = requireViewById(R.id.screenshot_dismiss_button)

        // Configure to extend the timeout during ongoing gestures (i.e. scrolls) that are already
        // being handled by our child views.
        actionsContainer.setOnTouchListener({ _: View, ev: MotionEvent ->
            userInteractionCallback?.invoke()
            false
        })
    }

    fun getTouchRegion(gestureInsets: Insets): Region {
        val region = getSwipeRegion()

        // Receive touches in gesture insets so they don't cause TOUCH_OUTSIDE
        // left edge gesture region
        val insetRect = Rect(0, 0, gestureInsets.left, displayMetrics.heightPixels)
        region.op(insetRect, Region.Op.UNION)
        // right edge gesture region
        insetRect.set(
            displayMetrics.widthPixels - gestureInsets.right,
            0,
            displayMetrics.widthPixels,
            displayMetrics.heightPixels
        )
        region.op(insetRect, Region.Op.UNION)

        return region
    }

    fun updateInsets(insets: WindowInsets) {
        val orientation = mContext.resources.configuration.orientation
        val inPortrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val cutout = insets.displayCutout
        val navBarInsets = insets.getInsets(WindowInsets.Type.navigationBars())

        // When honoring the navbar or other obstacle offsets, include some extra padding above
        // the inset itself.
        val verticalPadding =
            mContext.resources.getDimensionPixelOffset(R.dimen.screenshot_shelf_vertical_margin)

        // Minimum bottom padding to always enforce (e.g. if there's no nav bar)
        val minimumBottomPadding =
            context.resources.getDimensionPixelOffset(
                R.dimen.overlay_action_container_minimum_edge_spacing
            )

        if (cutout == null) {
            screenshotStatic.setPadding(0, 0, 0, navBarInsets.bottom)
        } else {
            val waterfall = cutout.waterfallInsets
            if (inPortrait) {
                screenshotStatic.setPadding(
                    waterfall.left,
                    max(cutout.safeInsetTop, waterfall.top),
                    waterfall.right,
                    max(
                        navBarInsets.bottom + verticalPadding,
                        cutout.safeInsetBottom + verticalPadding,
                        waterfall.bottom + verticalPadding,
                        minimumBottomPadding,
                    )
                )
            } else {
                screenshotStatic.setPadding(
                    max(cutout.safeInsetLeft, waterfall.left),
                    waterfall.top,
                    max(cutout.safeInsetRight, waterfall.right),
                    max(
                        navBarInsets.bottom + verticalPadding,
                        waterfall.bottom + verticalPadding,
                        minimumBottomPadding,
                    )
                )
            }
        }
    }

    // Max function for two or more params.
    private fun max(first: Int, second: Int, vararg items: Int): Int {
        var largest = if (first > second) first else second
        for (item in items) {
            if (item > largest) {
                largest = item
            }
        }
        return largest
    }

    private fun getSwipeRegion(): Region {
        val swipeRegion = Region()
        val padding = FloatingWindowUtil.dpToPx(displayMetrics, -1 * TOUCH_PADDING_DP).toInt()
        swipeRegion.addInsetView(screenshotPreview, padding)
        swipeRegion.addInsetView(actionsContainerBackground, padding)
        swipeRegion.addInsetView(dismissButton, padding)
        findViewById<View>(R.id.screenshot_message_container)?.let {
            swipeRegion.addInsetView(it, padding)
        }
        return swipeRegion
    }

    private fun Region.addInsetView(view: View, padding: Int = 0) {
        view.getBoundsOnScreen(tmpRect)
        tmpRect.inset(padding, padding)
        this.op(tmpRect, Region.Op.UNION)
    }

    companion object {
        private const val TOUCH_PADDING_DP = 12f
    }

    override fun onInterceptHoverEvent(event: MotionEvent): Boolean {
        userInteractionCallback?.invoke()
        return super.onInterceptHoverEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        userInteractionCallback?.invoke()

        // Let the client-provided listener see all `DOWN` events so that they'll be able to
        // interpret the remainder of the gesture, even if interception starts partway-through.
        // TODO: is this really necessary? And if we don't go on to start interception, should we
        // follow up with `ACTION_CANCEL`?
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            onTouchInterceptListener?.invoke(ev)
        }

        // Only allow the client-provided touch interceptor to take over the gesture if our
        // top-level `GestureDetector` decides not to scroll the action container.
        return gestureDetector.onTouchEvent(ev)
    }
}
