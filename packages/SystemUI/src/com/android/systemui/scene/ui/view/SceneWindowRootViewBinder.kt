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

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
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
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import java.time.Instant
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
                        )
                    )

                    val legacyView = view.requireViewById<View>(R.id.legacy_window_root)
                    view.addView(createVisibilityToggleView(legacyView))

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

    private var clickCount = 0
    private var lastClick = Instant.now()

    /**
     * A temporary UI to toggle on/off the visibility of the given [otherView]. It is toggled by
     * tapping 5 times in quick succession on the device camera (top center).
     */
    // TODO(b/291321285): Remove this when the Flexiglass UI is mature enough to turn off legacy
    //  SysUI altogether.
    private fun createVisibilityToggleView(otherView: View): View {
        val toggleView = View(otherView.context)
        otherView.isVisible = false
        toggleView.layoutParams = FrameLayout.LayoutParams(200, 200, Gravity.CENTER_HORIZONTAL)
        toggleView.setOnClickListener {
            val now = Instant.now()
            clickCount = if (now.minusSeconds(2) > lastClick) 1 else clickCount + 1
            if (clickCount == 5) {
                otherView.isVisible = !otherView.isVisible
                clickCount = 0
            }
            lastClick = now
        }
        return toggleView
    }
}
