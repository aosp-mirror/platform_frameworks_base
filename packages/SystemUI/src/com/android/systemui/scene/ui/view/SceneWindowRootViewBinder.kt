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
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.theme.PlatformTheme
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.DisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.ScreenDecorProvider
import com.android.systemui.keyguard.ui.composable.AlternateBouncer
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.setSnapshotBinding
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.scene.ui.composable.SceneContainer
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@ExperimentalCoroutinesApi
object SceneWindowRootViewBinder {

    /** Binds between the view and view-model pertaining to a specific scene container. */
    fun bind(
        view: ViewGroup,
        viewModelFactory: SceneContainerViewModel.Factory,
        motionEventHandlerReceiver: (SceneContainerViewModel.MotionEventHandler?) -> Unit,
        windowInsets: StateFlow<WindowInsets?>,
        containerConfig: SceneContainerConfig,
        sharedNotificationContainer: SharedNotificationContainer,
        scenes: Set<Scene>,
        overlays: Set<Overlay>,
        onVisibilityChangedInternal: (isVisible: Boolean) -> Unit,
        dataSourceDelegator: SceneDataSourceDelegator,
        alternateBouncerDependencies: AlternateBouncerDependencies,
    ) {
        val unsortedSceneByKey: Map<SceneKey, Scene> = scenes.associateBy { scene -> scene.key }
        val sortedSceneByKey: Map<SceneKey, Scene> =
            LinkedHashMap<SceneKey, Scene>(containerConfig.sceneKeys.size).apply {
                containerConfig.sceneKeys.forEach { sceneKey ->
                    val scene =
                        checkNotNull(unsortedSceneByKey[sceneKey]) {
                            "Scene not found for key \"$sceneKey\"!"
                        }

                    put(sceneKey, scene)
                }
            }

        val unsortedOverlayByKey: Map<OverlayKey, Overlay> =
            overlays.associateBy { overlay -> overlay.key }
        val sortedOverlayByKey: Map<OverlayKey, Overlay> =
            LinkedHashMap<OverlayKey, Overlay>(containerConfig.overlayKeys.size).apply {
                containerConfig.overlayKeys.forEach { overlayKey ->
                    val overlay =
                        checkNotNull(unsortedOverlayByKey[overlayKey]) {
                            "Overlay not found for key \"$overlayKey\"!"
                        }

                    put(overlayKey, overlay)
                }
            }

        view.repeatWhenAttached {
            view.viewModel(
                traceName = "SceneWindowRootViewBinder",
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { viewModelFactory.create(motionEventHandlerReceiver) },
            ) { viewModel ->
                try {
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
                                overlayByKey = sortedOverlayByKey,
                                dataSourceDelegator = dataSourceDelegator,
                                containerConfig = containerConfig,
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

                        // TODO(b/358354906): use an overlay for the alternate bouncer
                        view.addView(
                            createAlternateBouncerView(
                                context = view.context,
                                alternateBouncerDependencies = alternateBouncerDependencies,
                            )
                        )
                    }

                    view.setSnapshotBinding { onVisibilityChangedInternal(viewModel.isVisible) }
                    awaitCancellation()
                } finally {
                    // Here when destroyed.
                    view.removeAllViews()
                }
            }
        }
    }

    private fun createSceneContainerView(
        scope: CoroutineScope,
        context: Context,
        viewModel: SceneContainerViewModel,
        windowInsets: StateFlow<WindowInsets?>,
        sceneByKey: Map<SceneKey, Scene>,
        overlayByKey: Map<OverlayKey, Overlay>,
        dataSourceDelegator: SceneDataSourceDelegator,
        containerConfig: SceneContainerConfig,
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
                            sceneByKey = sceneByKey,
                            overlayByKey = overlayByKey,
                            initialSceneKey = containerConfig.initialSceneKey,
                            dataSourceDelegator = dataSourceDelegator,
                        )
                    }
                }
            }
        }
    }

    private fun createAlternateBouncerView(
        context: Context,
        alternateBouncerDependencies: AlternateBouncerDependencies,
    ): ComposeView {
        return ComposeView(context).apply {
            setContent {
                AlternateBouncer(
                    alternateBouncerDependencies = alternateBouncerDependencies,
                )
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
