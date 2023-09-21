package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel

/** A root view of the main SysUI window that supports scenes. */
class SceneWindowRootView(
    context: Context,
    attrs: AttributeSet?,
) :
    WindowRootView(
        context,
        attrs,
    ) {

    private lateinit var viewModel: SceneContainerViewModel

    fun init(
        viewModel: SceneContainerViewModel,
        containerConfig: SceneContainerConfig,
        scenes: Set<Scene>,
        layoutInsetController: LayoutInsetsController,
    ) {
        this.viewModel = viewModel
        setLayoutInsetsController(layoutInsetController)
        SceneWindowRootViewBinder.bind(
            view = this@SceneWindowRootView,
            viewModel = viewModel,
            containerConfig = containerConfig,
            scenes = scenes,
            onVisibilityChangedInternal = { isVisible ->
                super.setVisibility(if (isVisible) View.VISIBLE else View.INVISIBLE)
            }
        )
    }

    override fun setVisibility(visibility: Int) {
        // Do nothing. We don't want external callers to invoke this. Instead, we drive our own
        // visibility from our view-binder.
    }
}
