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

import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.animation.scene.SceneKey
import com.android.compose.theme.PlatformTheme
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.DisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.ScreenDecorProvider
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.scene.ui.composable.SceneContainer
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object SceneWindowRootViewBinder {

    /** Binds between the view and view-model pertaining to a specific scene container. */
    fun bind(
        view: ViewGroup,
        viewModel: SceneContainerViewModel,
        windowInsets: StateFlow<WindowInsets?>,
        containerConfig: SceneContainerConfig,
        sharedNotificationContainer: SharedNotificationContainer,
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
                        createSceneContainerView(
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
                    if (SceneContainerFlag.isEnabled) {
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

    private fun createSceneContainerView(
        scope: CoroutineScope,
        context: Context,
        viewModel: SceneContainerViewModel,
        windowInsets: StateFlow<WindowInsets?>,
        sceneByKey: Map<SceneKey, Scene>,
        dataSourceDelegator: SceneDataSourceDelegator,
    ): View {
        return ComposeView(context).apply {
            setContent {
                PlatformTheme {
                    ScreenDecorProvider(
                        displayCutout = displayCutoutFromWindowInsets(scope, context, windowInsets),
                        screenCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)
                    ) {
                        SceneContainer(
                            viewModel = viewModel,
                            sceneByKey =
                                sceneByKey.mapValues { (_, scene) -> scene as ComposableScene },
                            dataSourceDelegator = dataSourceDelegator,
                        )
                    }
                }
            }
        }
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private fun displayCutoutFromWindowInsets(
        scope: CoroutineScope,
        context: Context,
        windowInsets: StateFlow<WindowInsets?>,
    ): StateFlow<DisplayCutout> =
        windowInsets
            .map {
                val boundingRect = it?.displayCutout?.boundingRectTop
                val width = boundingRect?.let { boundingRect.right - boundingRect.left } ?: 0
                val left = boundingRect?.left?.toDp(context) ?: 0.dp
                val top = boundingRect?.top?.toDp(context) ?: 0.dp
                val right = boundingRect?.right?.toDp(context) ?: 0.dp
                val bottom = boundingRect?.bottom?.toDp(context) ?: 0.dp
                val location =
                    when {
                        width <= 0f -> CutoutLocation.NONE
                        left <= 0.dp -> CutoutLocation.LEFT
                        right >= getDisplayWidth(context) -> CutoutLocation.RIGHT
                        else -> CutoutLocation.CENTER
                    }
                val viewDisplayCutout = it?.displayCutout
                DisplayCutout(
                    left,
                    top,
                    right,
                    bottom,
                    location,
                    viewDisplayCutout,
                )
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DisplayCutout())

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private fun getDisplayWidth(context: Context): Dp {
        val point = Point()
        checkNotNull(context.display).getRealSize(point)
        return point.x.toDp(context)
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private fun Int.toDp(context: Context): Dp {
        return (this.toFloat() / context.resources.displayMetrics.density).dp
    }
}
