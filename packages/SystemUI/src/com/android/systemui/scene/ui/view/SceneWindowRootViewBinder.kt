/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.scene.ui.view

import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.compose.ComposeFacade
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object SceneWindowRootViewBinder {

    /** Binds between the view and view-model pertaining to a specific scene container. */
    fun bind(
        view: ViewGroup,
        viewModel: SceneContainerViewModel,
        windowInsets: StateFlow<WindowInsets?>,
        containerConfig: SceneContainerConfig,
        sharedNotificationContainer: SharedNotificationContainer,
        flags: SceneContainerFlags,
        scenes: Set<Scene>,
        onVisibilityChangedInternal: (isVisible: Boolean) -> Unit,
        dataSourceDelegator: SceneDataSourceDelegator,
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

        view.repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    view.setViewTreeOnBackPressedDispatcherOwner(
                        object : OnBackPressedDispatcherOwner {
                            override val onBackPressedDispatcher =
                                OnBackPressedDispatcher().apply {
                                    setOnBackInvokedDispatcher(
                                        view.viewRootImpl.onBackInvokedDispatcher
                                    )
                                }

                            override val lifecycle: Lifecycle = this@repeatWhenAttached.lifecycle
                        }
                    )

                    view.addView(
                        ComposeFacade.createSceneContainerView(
                                scope = this,
                                context = view.context,
                                viewModel = viewModel,
                                windowInsets = windowInsets,
                                sceneByKey = sortedSceneByKey,
                                dataSourceDelegator = dataSourceDelegator,
                            )
                            .also { it.id = R.id.scene_container_root_composable }
                    )

                    val legacyView = view.requireViewById<View>(R.id.legacy_window_root)
                    legacyView.isVisible = false

                    // This moves the SharedNotificationContainer to the WindowRootView just after
                    //  the SceneContainerView. This SharedNotificationContainer should contain NSSL
                    //  due to the NotificationStackScrollLayoutSection (legacy) or
                    //  NotificationSection (scene container) moving it there.
                    if (flags.flexiNotifsEnabled()) {
                        (sharedNotificationContainer.parent as? ViewGroup)?.removeView(
                            sharedNotificationContainer
                        )
                        view.addView(sharedNotificationContainer)
                    }

                    launch {
                        viewModel.isVisible.collect { isVisible ->
                            onVisibilityChangedInternal(isVisible)
                        }
                    }
                }

                // Here when destroyed.
                view.removeAllViews()
            }
        }
    }
}
