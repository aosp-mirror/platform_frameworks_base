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
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.util.AttributeSet
import com.android.systemui.R
import org.xmlpull.v1.XmlPullParser

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
class RoundedCornerProgressDrawable(drawable: Drawable?) : DrawableWrapper(drawable) {

    constructor() : this(null)

    companion object {
        private const val MAX_LEVEL = 10000 // Taken from Drawable
    }

    private var clipPath: Path = Path()

    init {
        setClipPath(Rect())
    }

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        val a = obtainAttributes(r, theme, attrs, R.styleable.RoundedCornerProgressDrawable)

        // Inflation will advance the XmlPullParser and AttributeSet.
        super.inflate(r, parser, attrs, theme)

        updateStateFromTypedArray(a)
        if (drawable == null) {
            throw IllegalStateException("${this::class.java.simpleName} needs a drawable")
        }
        a.recycle()
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        onLevelChange(level)
        return super.onLayoutDirectionChanged(layoutDirection)
    }

    private fun updateStateFromTypedArray(a: TypedArray) {
        if (a.hasValue(R.styleable.RoundedCornerProgressDrawable_android_drawable)) {
            setDrawable(a.getDrawable(R.styleable.RoundedCornerProgressDrawable_android_drawable))
        }
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
}