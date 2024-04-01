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

package com.android.wm.shell.bubbles.bar

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.core.view.updateLayoutParams
import com.android.wm.shell.R
import com.android.wm.shell.bubbles.BubblePositioner
import com.android.wm.shell.common.bubbles.BaseBubblePinController
import com.android.wm.shell.common.bubbles.BubbleBarLocation

/**
 * Controller to manage pinning bubble bar to left or right when dragging starts from the bubble bar
 * expanded view
 */
class BubbleExpandedViewPinController(
    private val context: Context,
    private val container: FrameLayout,
    private val positioner: BubblePositioner
) : BaseBubblePinController() {

    private var dropTargetView: View? = null
    private val tempRect: Rect by lazy(LazyThreadSafetyMode.NONE) { Rect() }

    override fun getScreenCenterX(): Int {
        return positioner.screenRect.centerX()
    }

    override fun getExclusionRect(): RectF {
        val rect =
            RectF(
                0f,
                0f,
                context.resources.getDimension(R.dimen.bubble_bar_dismiss_zone_width),
                context.resources.getDimension(R.dimen.bubble_bar_dismiss_zone_height)
            )

        val screenRect = positioner.screenRect
        // Center it around the bottom center of the screen
        rect.offsetTo(
            screenRect.exactCenterX() - rect.width() / 2f,
            screenRect.bottom - rect.height()
        )
        return rect
    }

    override fun createDropTargetView(): View {
        return LayoutInflater.from(context)
            .inflate(R.layout.bubble_bar_drop_target, container, false /* attachToRoot */)
            .also { view: View ->
                dropTargetView = view
                // Add at index 0 to ensure it does not cover the bubble
                container.addView(view, 0)
            }
    }

    override fun getDropTargetView(): View? {
        return dropTargetView
    }

    override fun removeDropTargetView(view: View) {
        container.removeView(view)
        dropTargetView = null
    }

    override fun updateLocation(location: BubbleBarLocation) {
        val view = dropTargetView ?: return
        getBounds(location.isOnLeft(view.isLayoutRtl), tempRect)
        view.updateLayoutParams<FrameLayout.LayoutParams> {
            width = tempRect.width()
            height = tempRect.height()
        }
        view.x = tempRect.left.toFloat()
        view.y = tempRect.top.toFloat()
    }

    private fun getBounds(onLeft: Boolean, out: Rect) {
        positioner.getBubbleBarExpandedViewBounds(onLeft, false /* isOverflowExpanded */, out)
        val centerX = out.centerX()
        val centerY = out.centerY()
        out.scale(DROP_TARGET_SCALE)
        // Move rect center back to the same position as before scale
        out.offset(centerX - out.centerX(), centerY - out.centerY())
    }

    companion object {
        @VisibleForTesting const val DROP_TARGET_SCALE = 0.9f
    }
}
