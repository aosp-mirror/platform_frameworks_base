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
package com.android.systemui.shared.shadow

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.TextView
import com.android.systemui.shared.R
import com.android.systemui.shared.shadow.DoubleShadowTextHelper.ShadowInfo
import com.android.systemui.shared.shadow.DoubleShadowTextHelper.applyShadows

/** Extension of [TextView] which draws two shadows on the text (ambient and key shadows} */
class DoubleShadowTextView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : TextView(context, attrs, defStyleAttr, defStyleRes) {
    private val mKeyShadowInfo: ShadowInfo
    private val mAmbientShadowInfo: ShadowInfo

    init {
        val attributes =
            context.obtainStyledAttributes(
                attrs,
                R.styleable.DoubleShadowTextView,
                defStyleAttr,
                defStyleRes
            )
        val drawableSize: Int
        val drawableInsetSize: Int
        try {
            val keyShadowBlur =
                attributes.getDimensionPixelSize(R.styleable.DoubleShadowTextView_keyShadowBlur, 0)
            val keyShadowOffsetX =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_keyShadowOffsetX,
                    0
                )
            val keyShadowOffsetY =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_keyShadowOffsetY,
                    0
                )
            val keyShadowAlpha =
                attributes.getFloat(R.styleable.DoubleShadowTextView_keyShadowAlpha, 0f)
            mKeyShadowInfo =
                ShadowInfo(
                    keyShadowBlur.toFloat(),
                    keyShadowOffsetX.toFloat(),
                    keyShadowOffsetY.toFloat(),
                    keyShadowAlpha
                )
            val ambientShadowBlur =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_ambientShadowBlur,
                    0
                )
            val ambientShadowOffsetX =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_ambientShadowOffsetX,
                    0
                )
            val ambientShadowOffsetY =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_ambientShadowOffsetY,
                    0
                )
            val ambientShadowAlpha =
                attributes.getFloat(R.styleable.DoubleShadowTextView_ambientShadowAlpha, 0f)
            mAmbientShadowInfo =
                ShadowInfo(
                    ambientShadowBlur.toFloat(),
                    ambientShadowOffsetX.toFloat(),
                    ambientShadowOffsetY.toFloat(),
                    ambientShadowAlpha
                )
            drawableSize =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_drawableIconSize,
                    0
                )
            drawableInsetSize =
                attributes.getDimensionPixelSize(
                    R.styleable.DoubleShadowTextView_drawableIconInsetSize,
                    0
                )
        } finally {
            attributes.recycle()
        }

        val drawables = arrayOf<Drawable?>(null, null, null, null)
        for ((index, drawable) in compoundDrawablesRelative.withIndex()) {
            if (drawable == null) continue
            drawables[index] =
                DoubleShadowIconDrawable(
                    mKeyShadowInfo,
                    mAmbientShadowInfo,
                    drawable,
                    drawableSize,
                    drawableInsetSize
                )
        }
        setCompoundDrawablesRelative(drawables[0], drawables[1], drawables[2], drawables[3])
    }

    public override fun onDraw(canvas: Canvas) {
        applyShadows(mKeyShadowInfo, mAmbientShadowInfo, this, canvas) { super.onDraw(canvas) }
    }
}
