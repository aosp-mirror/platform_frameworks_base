package com.android.systemui.statusbar.notification.row

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class PlaceHolderDrawable(private val width: Int, private val height: Int) : Drawable() {

    companion object {
        fun createFrom(other: Drawable): PlaceHolderDrawable {
            return PlaceHolderDrawable(other.intrinsicWidth, other.intrinsicHeight)
        }
    }

    override fun getIntrinsicWidth(): Int {
        return width
    }

    override fun getIntrinsicHeight(): Int {
        return height
    }

    override fun draw(canvas: Canvas) {}
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }
}
