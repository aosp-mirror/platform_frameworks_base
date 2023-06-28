package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import kotlinx.coroutines.launch

/** A root view of the main SysUI window that supports scenes. */
class SceneWindowRootView(
    context: Context,
    attrs: AttributeSet?,
) :
    WindowRootView(
        context,
        attrs,
    ) {
    fun init(
        viewModel: SceneContainerViewModel,
        containerConfig: SceneContainerConfig,
        scenes: Set<Scene>,
    ) {
        val unsortedSceneByKey: Map<SceneKey, Scene> = scenes.associateBy { scene -> scene.key }
        val sortedSceneByKey: Map<SceneKey, Scene> = buildMap {
            containerConfig.sceneKeys.forEach { sceneKey ->
                val scene =
                    checkNotNull(unsortedSceneByKey[sceneKey]) {
                        "Scene not found for key \"$sceneKey\"!"
                    }

                put(sceneKey, scene)
            }
        }

        repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    setViewTreeOnBackPressedDispatcherOwner(
                        object : OnBackPressedDispatcherOwner {
                            override val onBackPressedDispatcher =
                                OnBackPressedDispatcher().apply {
                                    setOnBackInvokedDispatcher(viewRootImpl.onBackInvokedDispatcher)
                                }

                            override val lifecycle: Lifecycle =
                                this@repeatWhenAttached.lifecycle
                        }
                    )

                    addView(
                        ComposeFacade.createSceneContainerView(
                            context = context,
                            viewModel = viewModel,
                            sceneByKey = sortedSceneByKey,
                        )
                    )
                }

                // Here when destroyed.
                removeAllViews()
            }
        }
    }
}
