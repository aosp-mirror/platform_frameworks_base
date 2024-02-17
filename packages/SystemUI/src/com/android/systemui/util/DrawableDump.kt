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

package com.android.systemui.util

import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log

fun dumpToString(drawable: Drawable?): String =
    if (Compile.IS_DEBUG) StringBuilder().appendDrawable(drawable).toString()
    else drawable.toString()

fun getSolidColor(drawable: Drawable?): String =
    if (Compile.IS_DEBUG) hexColorString(getSolidColors(drawable)?.defaultColor)
    else if (drawable == null) "null" else "?"

private fun getSolidColors(drawable: Drawable?): ColorStateList? {
    return when (drawable) {
        is GradientDrawable -> {
            return drawable.getStateField<ColorStateList>("mSolidColors")
        }
        is LayerDrawable -> {
            for (iLayer in 0 until drawable.numberOfLayers) {
                getSolidColors(drawable.getDrawable(iLayer))?.let {
                    return it
                }
            }
            null
        }
        is DrawableWrapper -> {
            return getSolidColors(drawable.drawable)
        }
        else -> null
    }
}

private fun StringBuilder.appendDrawable(drawable: Drawable?): StringBuilder {
    if (drawable == null) {
        append("null")
        return this
    }
    append("<")
    append(drawable.javaClass.simpleName)

    drawable.getStateField<ColorStateList>("mTint", fieldRequired = false)?.let {
        append(" tint=")
        appendColors(it)
        append(" blendMode=")
        append(drawable.getStateField<BlendMode>("mBlendMode"))
    }
    drawable.colorFilter
        ?.takeUnless { drawable is DrawableWrapper }
        ?.let {
            append(" colorFilter=")
            appendColorFilter(it)
        }
    when (drawable) {
        is DrawableWrapper -> {
            append(" wrapped=")
            appendDrawable(drawable.drawable)
        }
        is LayerDrawable -> {
            if (drawable is RippleDrawable) {
                drawable.getStateField<ColorStateList>("mColor")?.let {
                    append(" color=")
                    appendColors(it)
                }
                drawable.effectColor?.let {
                    append(" effectColor=")
                    appendColors(it)
                }
            }
            append(" layers=[")
            for (iLayer in 0 until drawable.numberOfLayers) {
                if (iLayer != 0) append(", ")
                appendDrawable(drawable.getDrawable(iLayer))
            }
            append("]")
        }
        is GradientDrawable -> {
            drawable
                .getStateField<Int>("mShape")
                ?.takeIf { it != 0 }
                ?.let {
                    append(" shape=")
                    append(it)
                }
            drawable.getStateField<ColorStateList>("mSolidColors")?.let {
                append(" solidColors=")
                appendColors(it)
            }
            drawable.getStateField<ColorStateList>("mStrokeColors")?.let {
                append(" strokeColors=")
                appendColors(it)
            }
            drawable.colors?.let {
                append(" gradientColors=[")
                it.forEachIndexed { iColor, color ->
                    if (iColor != 0) append(", ")
                    append(hexColorString(color))
                }
                append("]")
            }
        }
    }
    append(">")
    return this
}

private inline fun <reified T> Drawable.getStateField(
    name: String,
    fieldRequired: Boolean = true
): T? {
    val state = this.constantState ?: return null
    val clazz = state.javaClass
    return try {
        val field = clazz.getDeclaredField(name)
        field.isAccessible = true
        field.get(state) as T?
    } catch (ex: Exception) {
        if (fieldRequired) {
            Log.w(TAG, "Missing ${clazz.simpleName}.$name: ${T::class.simpleName}", ex)
        } else if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Missing ${clazz.simpleName}.$name: ${T::class.simpleName} ($ex)")
        }
        null
    }
}

private fun Appendable.appendColors(colorStateList: ColorStateList?) {
    if (colorStateList == null) {
        append("null")
        return
    }
    val colors = colorStateList.colors
    if (colors.size == 1) {
        append(hexColorString(colors[0]))
        return
    }
    append("<ColorStateList size=")
    append(colors.size.toString())
    append(" default=")
    append(hexColorString(colorStateList.defaultColor))
    append(">")
}

private fun Appendable.appendColorFilter(colorFilter: ColorFilter?) {
    if (colorFilter == null) {
        append("null")
        return
    }
    append("<")
    append(colorFilter.javaClass.simpleName)
    when (colorFilter) {
        is PorterDuffColorFilter -> {
            append(" color=")
            append(hexColorString(colorFilter.color))
            append(" mode=")
            append(colorFilter.mode.toString())
        }
        is BlendModeColorFilter -> {
            append(" color=")
            append(hexColorString(colorFilter.color))
            append(" mode=")
            append(colorFilter.mode.toString())
        }
        is LightingColorFilter -> {
            append(" multiply=")
            append(hexColorString(colorFilter.colorMultiply))
            append(" add=")
            append(hexColorString(colorFilter.colorAdd))
        }
        else -> append(" unhandled")
    }
    append(">")
}

private const val TAG = "DrawableDump"
