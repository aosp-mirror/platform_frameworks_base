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

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)

package com.android.systemui.keyguard.ui.composable

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.qualifiers.KeyguardRootView
import com.android.systemui.keyguard.ui.viewmodel.KeyguardLongPressViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.notifications.ui.composable.NotificationStack
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.Edge
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The lock screen scene shows when the device is locked. */
@SysUISingleton
class LockscreenScene
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: LockscreenSceneViewModel,
    @KeyguardRootView private val viewProvider: () -> @JvmSuppressWildcards View,
) : ComposableScene {
    override val key = SceneKey.Lockscreen

    override val destinationScenes: StateFlow<Map<UserAction, SceneModel>> =
        viewModel.upDestinationSceneKey
            .map { pageKey ->
                destinationScenes(up = pageKey, left = viewModel.leftDestinationSceneKey)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    destinationScenes(
                        up = viewModel.upDestinationSceneKey.value,
                        left = viewModel.leftDestinationSceneKey,
                    )
            )

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        LockscreenScene(
            viewProvider = viewProvider,
            viewModel = viewModel,
            modifier = modifier,
        )
    }

    private fun destinationScenes(
        up: SceneKey?,
        left: SceneKey?,
    ): Map<UserAction, SceneModel> {
        return buildMap {
            up?.let { this[UserAction.Swipe(Direction.UP)] = SceneModel(up) }
            left?.let { this[UserAction.Swipe(Direction.LEFT)] = SceneModel(left) }
            this[UserAction.Swipe(fromEdge = Edge.TOP, direction = Direction.DOWN)] =
                SceneModel(SceneKey.QuickSettings)
            this[UserAction.Swipe(direction = Direction.DOWN)] = SceneModel(SceneKey.Shade)
        }
    }
}

@Composable
private fun SceneScope.LockscreenScene(
    viewProvider: () -> View,
    viewModel: LockscreenSceneViewModel,
    modifier: Modifier = Modifier,
) {
    fun findSettingsMenu(): View {
        return viewProvider().requireViewById(R.id.keyguard_settings_button)
    }

    Box(
        modifier = modifier,
    ) {
        LongPressSurface(
            viewModel = viewModel.longPress,
            isSettingsMenuVisible = { findSettingsMenu().isVisible },
            settingsMenuBounds = {
                val bounds = android.graphics.Rect()
                findSettingsMenu().getHitRect(bounds)
                bounds.toComposeRect()
            },
            modifier = Modifier.fillMaxSize(),
        )

        AndroidView(
            factory = { _ ->
                val keyguardRootView = viewProvider()
                // Remove the KeyguardRootView from any parent it might already have in legacy code
                // just in case (a view can't have two parents).
                (keyguardRootView.parent as? ViewGroup)?.removeView(keyguardRootView)
                keyguardRootView
            },
            modifier = Modifier.fillMaxSize(),
        )

        val notificationStackPosition by
            viewModel.keyguardRoot.notificationPositionOnLockscreen.collectAsState()

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                NotificationStack(
                    viewModel = viewModel.notifications,
                    isScrimVisible = false,
                )
            }
        ) { measurables, constraints ->
            check(measurables.size == 1)
            val height = notificationStackPosition.height.toInt()
            val childConstraints = constraints.copy(minHeight = height, maxHeight = height)
            val placeable = measurables[0].measure(childConstraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val start = (constraints.maxWidth - placeable.measuredWidth) / 2
                placeable.placeRelative(x = start, y = notificationStackPosition.top.toInt())
            }
        }
    }
}

@Composable
private fun LongPressSurface(
    viewModel: KeyguardLongPressViewModel,
    isSettingsMenuVisible: () -> Boolean,
    settingsMenuBounds: () -> Rect,
    modifier: Modifier = Modifier,
) {
    val isEnabled: Boolean by viewModel.isLongPressHandlingEnabled.collectAsState(initial = false)

    Box(
        modifier =
            modifier
                .combinedClickable(
                    enabled = isEnabled,
                    onLongClick = viewModel::onLongPress,
                    onClick = {},
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val pointerInputChange = awaitFirstDown()
                        if (
                            isSettingsMenuVisible() &&
                                !settingsMenuBounds().contains(pointerInputChange.position)
                        ) {
                            viewModel.onTouchedOutside()
                        }
                    }
                },
    )
}
