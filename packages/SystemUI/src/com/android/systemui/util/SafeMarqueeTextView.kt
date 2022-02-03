package com.android.systemui.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.TextView

/**
 * A TextField that doesn't relayout when changing from marquee to ellipsis.
 */
@SuppressLint("AppCompatCustomView")
open class SafeMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : TextView(context, attrs, defStyleAttr, defStyleRes) {

    private var safelyIgnoreLayout = false
    private val hasStableWidth
        get() = layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT

    override fun requestLayout() {
        if (safelyIgnoreLayout) {
            return
        }
        super.requestLayout()
    }

    override fun startMarquee() {
        val wasIgnoring = safelyIgnoreLayout
        safelyIgnoreLayout = hasStableWidth
        super.startMarquee()
        safelyIgnoreLayout = wasIgnoring
    }

    override fun stopMarquee() {
        val wasIgnoring = safelyIgnoreLayout
        safelyIgnoreLayout = hasStableWidth
        super.stopMarquee()
        safelyIgnoreLayout = wasIgnoring
    }
}