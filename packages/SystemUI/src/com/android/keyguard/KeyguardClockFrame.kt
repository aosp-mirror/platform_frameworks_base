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
        // Ignore alpha passed from View, prefer to compute it from set values
        drawAlpha = (255 * this.alpha * transitionAlpha).toInt()
        return true
    }

    protected override fun dispatchDraw(canvas: Canvas) {
        saveCanvasAlpha(this, canvas, drawAlpha) { super.dispatchDraw(it) }
    }

    companion object {
        @JvmStatic
        fun saveCanvasAlpha(view: View, canvas: Canvas, alpha: Int, drawFunc: (Canvas) -> Unit) {
            if (alpha <= 0) {
                // Zero Alpha -> skip drawing phase
                return
            }

            if (alpha >= 255) {
                // Max alpha -> no need for layer
                drawFunc(canvas)
                return
            }

            // Find x & y of view on screen
            var (x, y) =
                run {
                    val locationOnScreen = IntArray(2)
                    view.getLocationOnScreen(locationOnScreen)
                    Pair(locationOnScreen[0].toFloat(), locationOnScreen[1].toFloat())
                }

            val restoreTo =
                canvas.saveLayerAlpha(-1f * x, -1f * y, x + view.width, y + view.height, alpha)
            drawFunc(canvas)
            canvas.restoreToCount(restoreTo)
        }
    }
}
