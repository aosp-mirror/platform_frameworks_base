package com.android.systemui.media

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import com.android.systemui.Gefingerpoken
import com.android.systemui.util.animation.physicsAnimator

/**
 * A ScrollView used in Media that doesn't limit itself to the childs bounds. This is useful
 * when only measuring children but not the parent, when trying to apply a new scroll position
 */
class MediaScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
)
    : HorizontalScrollView(context, attrs, defStyleAttr) {

    lateinit var contentContainer: ViewGroup
        private set
    var touchListener: Gefingerpoken? = null

    /**
     * The target value of the translation X animation. Only valid if the physicsAnimator is running
     */
    var animationTargetX = 0.0f

    /**
     * Get the current content translation. This is usually the normal translationX of the content,
     * but when animating, it might differ
     */
    fun getContentTranslation() = if (contentContainer.physicsAnimator.isRunning()) {
        animationTargetX
    } else {
        contentContainer.translationX
    }

    /**
     * Convert between the absolute (left-to-right) and relative (start-to-end) scrollX of the media
     * carousel.  The player indices are always relative (start-to-end) and the scrollView.scrollX
     * is always absolute.  This function is its own inverse.
     */
    private fun transformScrollX(scrollX: Int): Int = if (isLayoutRtl) {
        contentContainer.width - width - scrollX
    } else {
        scrollX
    }

    /**
     * Get the layoutDirection-relative (start-to-end) scroll X position of the carousel.
     */
    var relativeScrollX: Int
        get() = transformScrollX(scrollX)
        set(value) {
            scrollX = transformScrollX(value)
        }

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

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        var intercept = false
        touchListener?.let {
            intercept = it.onInterceptTouchEvent(ev)
        }
        return super.onInterceptTouchEvent(ev) || intercept
    }

    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        var touch = false
        touchListener?.let {
            touch = it.onTouchEvent(ev)
        }
        return super.onTouchEvent(ev) || touch
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        contentContainer = getChildAt(0) as ViewGroup
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        if (getContentTranslation() != 0.0f) {
            // When we're dismissing we ignore all the scrolling
            return false
        }
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX,
                scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent)
    }

    /**
     * Cancel the current touch event going on.
     */
    fun cancelCurrentScroll() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        super.onTouchEvent(event)
        event.recycle()
    }
}