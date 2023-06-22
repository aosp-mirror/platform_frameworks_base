package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
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

    /**
     * Returns `true` if this view is the true root of the view-hierarchy; `false` otherwise.
     *
     * Please see the class-level documentation to understand why this is possible.
     */
    private fun isRoot(): Boolean {
        // TODO(b/283300105): remove this check once there's only one subclass of WindowRootView.
        return parent.let { it !is View || it.id == android.R.id.content }
    }
}
