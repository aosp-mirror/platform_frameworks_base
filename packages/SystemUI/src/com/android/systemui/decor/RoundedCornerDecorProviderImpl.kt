/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.decor

import android.content.Context
import android.view.DisplayCutout
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.systemui.R

class RoundedCornerDecorProviderImpl(
    override val viewId: Int,
    @DisplayCutout.BoundsPosition override val alignedBound1: Int,
    @DisplayCutout.BoundsPosition override val alignedBound2: Int,
    private val roundedCornerResDelegate: RoundedCornerResDelegate
) : CornerDecorProvider() {

    private val isTop = alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)

    override fun inflateView(
        context: Context,
        parent: ViewGroup,
        @Surface.Rotation rotation: Int
    ): View {
        return ImageView(context).also { view ->
            // View
            view.id = viewId
            initView(view, rotation)

            // LayoutParams
            val layoutSize = if (isTop) {
                roundedCornerResDelegate.topRoundedSize
            } else {
                roundedCornerResDelegate.bottomRoundedSize
            }
            val params = FrameLayout.LayoutParams(
                    layoutSize.width,
                    layoutSize.height,
                    alignedBound1.toLayoutGravity(rotation) or
                            alignedBound2.toLayoutGravity(rotation))

            // AddView
            parent.addView(view, params)
        }
    }

    private fun initView(view: ImageView, @Surface.Rotation rotation: Int) {
        view.setRoundedCornerImage(roundedCornerResDelegate, isTop)
        view.adjustRotation(alignedBounds, rotation)
        view.imageTintList = roundedCornerResDelegate.colorTintList
    }

    override fun onReloadResAndMeasure(
        view: View,
        reloadToken: Int,
        @Surface.Rotation rotation: Int,
        displayUniqueId: String?
    ) {
        roundedCornerResDelegate.updateDisplayUniqueId(displayUniqueId, reloadToken)

        initView((view as ImageView), rotation)

        val layoutSize = if (isTop) {
            roundedCornerResDelegate.topRoundedSize
        } else {
            roundedCornerResDelegate.bottomRoundedSize
        }
        (view.layoutParams as FrameLayout.LayoutParams).let {
            it.width = layoutSize.width
            it.height = layoutSize.height
            it.gravity = alignedBound1.toLayoutGravity(rotation) or
                    alignedBound2.toLayoutGravity(rotation)
            view.setLayoutParams(it)
        }
    }
}

@DisplayCutout.BoundsPosition
private fun Int.toLayoutGravity(@Surface.Rotation rotation: Int): Int = when (rotation) {
    Surface.ROTATION_0 -> when (this) {
        DisplayCutout.BOUNDS_POSITION_LEFT -> Gravity.LEFT
        DisplayCutout.BOUNDS_POSITION_TOP -> Gravity.TOP
        DisplayCutout.BOUNDS_POSITION_RIGHT -> Gravity.RIGHT
        else /* DisplayCutout.BOUNDS_POSITION_BOTTOM */ -> Gravity.BOTTOM
    }
    Surface.ROTATION_90 -> when (this) {
        DisplayCutout.BOUNDS_POSITION_LEFT -> Gravity.BOTTOM
        DisplayCutout.BOUNDS_POSITION_TOP -> Gravity.LEFT
        DisplayCutout.BOUNDS_POSITION_RIGHT -> Gravity.TOP
        else /* DisplayCutout.BOUNDS_POSITION_BOTTOM */ -> Gravity.RIGHT
    }
    Surface.ROTATION_270 -> when (this) {
        DisplayCutout.BOUNDS_POSITION_LEFT -> Gravity.TOP
        DisplayCutout.BOUNDS_POSITION_TOP -> Gravity.RIGHT
        DisplayCutout.BOUNDS_POSITION_RIGHT -> Gravity.BOTTOM
        else /* DisplayCutout.BOUNDS_POSITION_BOTTOM */ -> Gravity.LEFT
    }
    else /* Surface.ROTATION_180 */ -> when (this) {
        DisplayCutout.BOUNDS_POSITION_LEFT -> Gravity.RIGHT
        DisplayCutout.BOUNDS_POSITION_TOP -> Gravity.BOTTOM
        DisplayCutout.BOUNDS_POSITION_RIGHT -> Gravity.LEFT
        else /* DisplayCutout.BOUNDS_POSITION_BOTTOM */ -> Gravity.TOP
    }
}

private fun ImageView.setRoundedCornerImage(
    resDelegate: RoundedCornerResDelegate,
    isTop: Boolean
) {
    val drawable = if (isTop)
        resDelegate.topRoundedDrawable
    else
        resDelegate.bottomRoundedDrawable

    if (drawable != null) {
        setImageDrawable(drawable)
    } else {
        setImageResource(
                if (isTop)
                    R.drawable.rounded_corner_top
                else
                    R.drawable.rounded_corner_bottom
        )
    }
}

/**
 * Configures the rounded corner drawable's view matrix based on the gravity.
 *
 * The gravity describes which corner to configure for, and the drawable we are rotating is assumed
 * to be oriented for the top-left corner of the device regardless of the target corner.
 * Therefore we need to rotate 180 degrees to get a bottom-left corner, and mirror in the x- or
 * y-axis for the top-right and bottom-left corners.
 */
private fun ImageView.adjustRotation(alignedBounds: List<Int>, @Surface.Rotation rotation: Int) {
    var newRotation = 0F
    var newScaleX = 1F
    var newScaleY = 1F

    val isTop = alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
    val isLeft = alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT)
    when (rotation) {
        Surface.ROTATION_0 -> when {
            isTop && isLeft -> {}
            isTop && !isLeft -> { newScaleX = -1F }
            !isTop && isLeft -> { newScaleY = -1F }
            else /* !isTop && !isLeft */ -> { newRotation = 180F }
        }
        Surface.ROTATION_90 -> when {
            isTop && isLeft -> { newScaleY = -1F }
            isTop && !isLeft -> {}
            !isTop && isLeft -> { newRotation = 180F }
            else /* !isTop && !isLeft */ -> { newScaleX = -1F }
        }
        Surface.ROTATION_270 -> when {
            isTop && isLeft -> { newScaleX = -1F }
            isTop && !isLeft -> { newRotation = 180F }
            !isTop && isLeft -> {}
            else /* !isTop && !isLeft */ -> { newScaleY = -1F }
        }
        else /* Surface.ROTATION_180 */ -> when {
            isTop && isLeft -> { newRotation = 180F }
            isTop && !isLeft -> { newScaleY = -1F }
            !isTop && isLeft -> { newScaleX = -1F }
            else /* !isTop && !isLeft */ -> {}
        }
    }

    this.rotation = newRotation
    this.scaleX = newScaleX
    this.scaleY = newScaleY
}