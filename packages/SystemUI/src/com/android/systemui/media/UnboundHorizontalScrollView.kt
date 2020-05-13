package com.android.systemui.media

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * A Horizontal scrollview that doesn't limit itself to the childs bounds. This is useful
 * when only measuring children but not the parent, when trying to apply a new scroll position
 */
class UnboundHorizontalScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : HorizontalScrollView(context, attrs, defStyleAttr) {

    /**
     * Allow all scrolls to go through, use base implementation
     */
    override fun scrollTo(x: Int, y: Int) {
        if (mScrollX != x || mScrollY != y) {
            val oldX: Int = mScrollX
            val oldY: Int = mScrollY
            mScrollX = x
            mScrollY = y
            invalidateParentCaches()
            onScrollChanged(mScrollX, mScrollY, oldX, oldY)
            if (!awakenScrollBars()) {
                postInvalidateOnAnimation()
            }
        }
    }
}