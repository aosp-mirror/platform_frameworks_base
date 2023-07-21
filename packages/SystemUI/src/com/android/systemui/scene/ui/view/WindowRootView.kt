package com.android.systemui.scene.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Pair
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.view.updateMargins
import com.android.systemui.R
import com.android.systemui.compose.ComposeFacade

/**
 * A view that can serve as the root of the main SysUI window (but might not, see below for more
 * information regarding this confusing comment).
 *
 * Naturally, only one view may ever be at the root of a view hierarchy tree. Under certain
 * conditions, the view hierarchy tree in the scene-containing window view may actually have one
 * [WindowRootView] acting as the true root view and another [WindowRootView] which doesn't and is,
 * instead, a child of the true root view. To discern which one is which, please use the [isRoot]
 * method.
 */
open class WindowRootView(
    context: Context,
    attrs: AttributeSet?,
) :
    FrameLayout(
        context,
        attrs,
    ) {

    private lateinit var layoutInsetsController: LayoutInsetsController
    private var leftInset = 0
    private var rightInset = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (ComposeFacade.isComposeAvailable() && isRoot()) {
            ComposeFacade.composeInitializer().onAttachedToWindow(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (ComposeFacade.isComposeAvailable() && isRoot()) {
            ComposeFacade.composeInitializer().onDetachedFromWindow(this)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): FrameLayout.LayoutParams? {
        return LayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): FrameLayout.LayoutParams? {
        return LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets? {
        val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        if (fitsSystemWindows) {
            val paddingChanged = insets.top != paddingTop || insets.bottom != paddingBottom

            // Drop top inset, and pass through bottom inset.
            if (paddingChanged) {
                setPadding(0, 0, 0, 0)
            }
        } else {
            val changed =
                paddingLeft != 0 || paddingRight != 0 || paddingTop != 0 || paddingBottom != 0
            if (changed) {
                setPadding(0, 0, 0, 0)
            }
        }
        leftInset = 0
        rightInset = 0

        val displayCutout = rootWindowInsets.displayCutout
        val pairInsets: Pair<Int, Int> =
            layoutInsetsController.getinsets(windowInsets, displayCutout)
        leftInset = pairInsets.first
        rightInset = pairInsets.second
        applyMargins()
        return windowInsets
    }

    fun setLayoutInsetsController(layoutInsetsController: LayoutInsetsController) {
        this.layoutInsetsController = layoutInsetsController
    }

    private fun applyMargins() {
        val count = childCount
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.layoutParams is LayoutParams) {
                val layoutParams = child.layoutParams as LayoutParams
                if (
                    !layoutParams.ignoreRightInset &&
                        (layoutParams.rightMargin != rightInset ||
                            layoutParams.leftMargin != leftInset)
                ) {
                    layoutParams.updateMargins(left = leftInset, right = rightInset)
                    child.requestLayout()
                }
            }
        }
    }

    /**
     * Returns `true` if this view is the true root of the view-hierarchy; `false` otherwise.
     *
     * Please see the class-level documentation to understand why this is possible.
     */
    private fun isRoot(): Boolean {
        // TODO(b/283300105): remove this check once there's only one subclass of WindowRootView.
        return parent.let { it !is View || it.id == android.R.id.content }
    }

    /** Controller responsible for calculating insets for the shade window. */
    interface LayoutInsetsController {

        /** Update the insets and calculate them accordingly. */
        fun getinsets(
            windowInsets: WindowInsets?,
            displayCutout: DisplayCutout?,
        ): Pair<Int, Int>
    }

    private class LayoutParams : FrameLayout.LayoutParams {
        var ignoreRightInset = false

        constructor(
            width: Int,
            height: Int,
        ) : super(
            width,
            height,
        )

        @SuppressLint("CustomViewStyleable")
        constructor(
            context: Context,
            attrs: AttributeSet?,
        ) : super(
            context,
            attrs,
        ) {
            val obtainedAttributes =
                context.obtainStyledAttributes(attrs, R.styleable.StatusBarWindowView_Layout)
            ignoreRightInset =
                obtainedAttributes.getBoolean(
                    R.styleable.StatusBarWindowView_Layout_ignoreRightInset,
                    false
                )
            obtainedAttributes.recycle()
        }
    }
}
