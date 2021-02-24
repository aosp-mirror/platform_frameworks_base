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

package com.android.systemui.util

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.InsetDrawable

/**
 * [DrawableWrapper] to use in the progress of a slider.
 *
 * This drawable is used to change the bounds of the enclosed drawable depending on the level to
 * simulate a sliding progress, instead of using clipping or scaling. That way, the shape of the
 * edges is maintained.
 *
 * Meant to be used with a rounded ends background, it will also prevent deformation when the slider
 * is meant to be smaller than the rounded corner. The background should have rounded corners that
 * are half of the height.
 */
class RoundedCornerProgressDrawable @JvmOverloads constructor(
    drawable: Drawable? = null
) : InsetDrawable(drawable, 0) {

    companion object {
        private const val MAX_LEVEL = 10000 // Taken from Drawable
    }

    private var clipPath: Path = Path()

    init {
        setClipPath(Rect())
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        onLevelChange(level)
        return super.onLayoutDirectionChanged(layoutDirection)
    }

    override fun onBoundsChange(bounds: Rect) {
        setClipPath(bounds)
        super.onBoundsChange(bounds)
        onLevelChange(level)
    }

    private fun setClipPath(bounds: Rect) {
        clipPath.reset()
        clipPath.addRoundRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                bounds.height().toFloat() / 2,
                bounds.height().toFloat() / 2,
                Path.Direction.CW
        )
    }

    override fun onLevelChange(level: Int): Boolean {
        val db = drawable?.bounds!!
        val width = bounds.width() * level / MAX_LEVEL
        // Extra space on the left to keep the rounded shape on the right end
        val leftBound = bounds.left - bounds.height()
        drawable?.setBounds(leftBound, db.top, bounds.left + width, db.bottom)
        return super.onLevelChange(level)
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restore()
    }

    override fun getConstantState(): ConstantState? {
        // This should not be null as it was created with a state in the constructor.
        return RoundedCornerState(super.getConstantState()!!)
    }

    private class RoundedCornerState(private val wrappedState: ConstantState) : ConstantState() {
        override fun newDrawable(): Drawable {
            return newDrawable(null, null)
        }

        override fun newDrawable(res: Resources?, theme: Resources.Theme?): Drawable {
            val wrapper = wrappedState.newDrawable(res, theme) as DrawableWrapper
            return RoundedCornerProgressDrawable(wrapper.drawable)
        }

        override fun getChangingConfigurations(): Int {
            return wrappedState.changingConfigurations
        }
    }
}