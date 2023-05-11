package com.android.keyguard

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class KeyguardClockFrame(
    context: Context,
    attrs: AttributeSet,
) : FrameLayout(context, attrs) {
    private var drawAlpha: Int = 255

    protected override fun onSetAlpha(alpha: Int): Boolean {
        drawAlpha = alpha
        return true
    }

    protected override fun dispatchDraw(canvas: Canvas) {
        val restoreTo = saveCanvasAlpha(this, canvas, drawAlpha)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(restoreTo)
    }

    companion object {
        @JvmStatic
        fun saveCanvasAlpha(view: View, canvas: Canvas, alpha: Int): Int {
            var (x, y) =
                run {
                    val locationOnScreen = IntArray(2)
                    view.getLocationOnScreen(locationOnScreen)
                    Pair(locationOnScreen[0].toFloat(), locationOnScreen[1].toFloat())
                }

            return canvas.saveLayerAlpha(-1f * x, -1f * y, x + view.width, y + view.height, alpha)
        }
    }
}
