package com.android.keyguard

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.LinearLayout

class KeyguardStatusContainer(
    context: Context,
    attrs: AttributeSet,
) : LinearLayout(context, attrs) {
    private var drawAlpha: Int = 255

    protected override fun onSetAlpha(alpha: Int): Boolean {
        drawAlpha = alpha
        return true
    }

    protected override fun dispatchDraw(canvas: Canvas) {
        val restoreTo = KeyguardClockFrame.saveCanvasAlpha(this, canvas, drawAlpha)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(restoreTo)
    }
}
