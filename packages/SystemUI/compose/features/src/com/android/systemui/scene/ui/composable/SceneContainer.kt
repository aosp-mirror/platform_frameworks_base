/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.scene.ui.composable

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.observableTransitionState
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffectFactory
import com.android.systemui.lifecycle.rememberActivated
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.ribbon.ui.composable.BottomRightCornerRibbon
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.view.SceneJankMonitor
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.shade.ui.composable.isFullWidthShade
import javax.inject.Provider

/**
 * Renders a container of a collection of "scenes" that the user can switch between using certain
 * user actions (for instance, swiping up and down) or that can be switched automatically based on
 * application business logic in response to certain events (for example, the device unlocking).
 *
 * It's possible for the application to host several such scene containers, the configuration system
 * allows configuring each container with its own set of scenes. Scenes can be present in multiple
 * containers.
 *
 * @param viewModel The UI state holder for this container.
 * @param sceneByKey Mapping of [Scene] by [SceneKey], ordered by z-order such that the last scene
 *   is rendered on top of all other scenes. It's critical that this map contains exactly and only
 *   the scenes on this container. In other words: (a) there should be no scene in this map that is
 *   not in the configuration for this container and (b) all scenes in the configuration must have
 *   entries in this map.
 * @param overlayByKey Mapping of [Overlay] by [OverlayKey], ordered by z-order such that the last
 *   overlay is rendered on top of all other overlays. It's critical that this map contains exactly
 *   and only the overlays on this container. In other words: (a) there should be no overlay in this
 *   map that is not in the configuration for this container and (b) all overlays in the
 *   configuration must have entries in this map.
 * @param modifier A modifier.
 */
@Composable
fun SceneContainer(
    viewModel: SceneContainerViewModel,
    sceneByKey: Map<SceneKey, Scene>,
    overlayByKey: Map<OverlayKey, Overlay>,
    initialSceneKey: SceneKey,
    transitionsBuilder: SceneContainerTransitionsBuilder,
    dataSourceDelegator: SceneDataSourceDelegator,
    qsSceneAdapter: Provider<QSSceneAdapter>,
    sceneJankMonitorFactory: SceneJankMonitor.Factory,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    val view = LocalView.current
    val sceneJankMonitor =
        rememberActivated(traceName = "sceneJankMonitor") { sceneJankMonitorFactory.create() }

    val hapticFeedback = LocalHapticFeedback.current
    val sceneTransitions =
        remember(hapticFeedback) {
            transitionsBuilder.build(viewModel.hapticsViewModel.getRevealHaptics(hapticFeedback))
        }

    val state =
        rememberMutableSceneTransitionLayoutState(
            initialScene = initialSceneKey,
            canChangeScene = { toScene -> viewModel.canChangeScene(toScene) },
            transitions = sceneTransitions,
            onTransitionStart = { transition ->
                sceneJankMonitor.onTransitionStart(
                    view = view,
                    from = transition.fromContent,
                    to = transition.toContent,
                    cuj = transition.cuj,
                )
            },
            onTransitionEnd = { transition ->
                sceneJankMonitor.onTransitionEnd(
                    from = transition.fromContent,
                    to = transition.toContent,
                    cuj = transition.cuj,
                )
            },
        )

    DisposableEffect(state) {
        val dataSource = SceneTransitionLayoutDataSource(state, coroutineScope)
        dataSourceDelegator.setDelegate(dataSource)
        onDispose { dataSourceDelegator.setDelegate(null) }
    }

    DisposableEffect(viewModel, state) {
        viewModel.setTransitionState(state.observableTransitionState())
        onDispose { viewModel.setTransitionState(null) }
    }

    val actionableContentKey =
        viewModel.getActionableContentKey(state.currentScene, state.currentOverlays, overlayByKey)
    val userActionsByContentKey: MutableMap<ContentKey, Map<UserAction, UserActionResult>> =
        remember {
            mutableStateMapOf()
        }
    LaunchedEffect(actionableContentKey) {
        try {
            val actionableContent: ActionableContent =
                checkNotNull(
                    overlayByKey[actionableContentKey] ?: sceneByKey[actionableContentKey]
                ) {
                    "invalid ContentKey: $actionableContentKey"
                }
            viewModel.filteredUserActions(actionableContent.userActions).collect { userActions ->
                userActionsByContentKey[actionableContentKey] =
                    viewModel.resolveSceneFamilies(userActions)
            }
        } finally {
            userActionsByContentKey[actionableContentKey] = emptyMap()
        }
    }

    // Overlays use the offset overscroll effect when shown on large screens, otherwise they
    // stretch. All scenes use the OffsetOverscrollEffect.
    val offsetOverscrollEffectFactory = rememberOffsetOverscrollEffectFactory()
    val stretchOverscrollEffectFactory = checkNotNull(LocalOverscrollFactory.current)
    val overlayEffectFactory =
        if (isFullWidthShade()) stretchOverscrollEffectFactory else offsetOverscrollEffectFactory

    // Inflate qsView here so that shade has the correct qqs height in the first measure pass after
    // rebooting.
    if (
        viewModel.allContentKeys.contains(Scenes.QuickSettings) ||
            viewModel.allContentKeys.contains(Scenes.Shade)
    ) {
        val qsAdapter = qsSceneAdapter.get()
        QuickSettingsTheme {
            val context = LocalContext.current
            val qsView by qsAdapter.qsView.collectAsStateWithLifecycle()
            LaunchedEffect(context) {
                if (qsView == null) {
                    qsAdapter.inflate(context)
                }
            }
        }
    }

    Box(
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(false)
                    viewModel.onSceneContainerUserInputStarted()
                }
            }
    ) {
        SceneRevealScrim(
            viewModel = viewModel.lightRevealScrim,
            wallpaperViewModel = viewModel.wallpaperViewModel,
            modifier = Modifier.fillMaxSize(),
        )

        SceneTransitionLayout(
            state = state,
            modifier = modifier.fillMaxSize(),
            swipeSourceDetector = viewModel.edgeDetector,
        ) {
            sceneByKey.forEach { (sceneKey, scene) ->
                scene(
                    key = sceneKey,
                    userActions = userActionsByContentKey.getOrDefault(sceneKey, emptyMap()),
                    effectFactory = offsetOverscrollEffectFactory,
                ) {
                    // Activate the scene.
                    LaunchedEffect(scene) { scene.activate() }

                    // Render the scene.
                    with(scene) {
                        this@scene.Content(
                            modifier = Modifier.element(sceneKey.rootElementKey).fillMaxSize()
                        )
                    }
                }
            }
            overlayByKey.forEach { (overlayKey, overlay) ->
                overlay(
                    key = overlayKey,
                    userActions = userActionsByContentKey.getOrDefault(overlayKey, emptyMap()),
                    effectFactory = overlayEffectFactory,
                ) {
                    // Activate the overlay.
                    LaunchedEffect(overlay) { overlay.activate() }

                    // Render the overlay.
                    with(overlay) { this@overlay.Content(Modifier) }
                }
            }
        }

        BottomRightCornerRibbon(
            content = { Text(text = "flexi\uD83E\uDD43", color = Color.White) },
            colorSaturation = { viewModel.ribbonColorSaturation },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
