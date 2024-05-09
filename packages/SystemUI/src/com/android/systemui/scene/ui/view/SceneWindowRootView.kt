package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import kotlinx.coroutines.flow.MutableStateFlow

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

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private val windowInsets: MutableStateFlow<WindowInsets?> = MutableStateFlow(null)

    fun init(
        viewModel: SceneContainerViewModel,
        containerConfig: SceneContainerConfig,
        sharedNotificationContainer: SharedNotificationContainer,
        scenes: Set<Scene>,
        layoutInsetController: LayoutInsetsController,
        sceneDataSourceDelegator: SceneDataSourceDelegator,
    ) {
        this.viewModel = viewModel
        setLayoutInsetsController(layoutInsetController)
        SceneWindowRootViewBinder.bind(
            view = this@SceneWindowRootView,
            viewModel = viewModel,
            windowInsets = windowInsets,
            containerConfig = containerConfig,
            sharedNotificationContainer = sharedNotificationContainer,
            scenes = scenes,
            onVisibilityChangedInternal = { isVisible ->
                super.setVisibility(if (isVisible) View.VISIBLE else View.INVISIBLE)
            },
            dataSourceDelegator = sceneDataSourceDelegator,
        )
    }

    override fun setVisibility(visibility: Int) {
        // Do nothing. We don't want external callers to invoke this. Instead, we drive our own
        // visibility from our view-binder.
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets {
        this.windowInsets.value = windowInsets
        return windowInsets
    }
}
