package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.compose.ComposeFacade

/** A view that can serve as the root of the main SysUI window. */
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

    private fun isRoot(): Boolean {
        // TODO(b/283300105): remove this check once there's only one subclass of WindowRootView.
        return parent.let { it !is View || it.id == android.R.id.content }
    }
}
