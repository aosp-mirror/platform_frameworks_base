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
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.Region
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.res.R
import com.android.systemui.screenshot.FloatingWindowUtil

class ScreenshotShelfView(context: Context, attrs: AttributeSet? = null) :
    ConstraintLayout(context, attrs) {
    lateinit var screenshotPreview: ImageView
    var onTouchInterceptListener: ((MotionEvent) -> Boolean)? = null

    private val displayMetrics = context.resources.displayMetrics
    private val tmpRect = Rect()
    private lateinit var actionsContainerBackground: View
    private lateinit var dismissButton: View

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Get focus so that the key events go to the layout.
        isFocusableInTouchMode = true
        screenshotPreview = requireViewById(R.id.screenshot_preview)
        actionsContainerBackground = requireViewById(R.id.actions_container_background)
        dismissButton = requireViewById(R.id.screenshot_dismiss_button)
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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (onTouchInterceptListener?.invoke(ev) == true) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }
}
