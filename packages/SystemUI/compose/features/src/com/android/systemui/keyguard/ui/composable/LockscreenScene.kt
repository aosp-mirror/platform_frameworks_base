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

package com.android.systemui.keyguard.ui.composable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.animateSceneFloatAsState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.ComposableScene
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The lock screen scene shows when the device is locked. */
@SysUISingleton
class LockscreenScene
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    viewModel: LockscreenSceneViewModel,
    private val lockscreenContent: Lazy<LockscreenContent>,
) : ComposableScene {
    override val key = Scenes.Lockscreen

    override val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        combine(
                viewModel.upDestinationSceneKey,
                viewModel.leftDestinationSceneKey,
                viewModel.downFromTopEdgeDestinationSceneKey,
            ) { upKey, leftKey, downFromTopEdgeKey ->
                destinationScenes(
                    up = upKey,
                    left = leftKey,
                    downFromTopEdge = downFromTopEdgeKey,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    destinationScenes(
                        up = viewModel.upDestinationSceneKey.value,
                        left = viewModel.leftDestinationSceneKey.value,
                        downFromTopEdge = viewModel.downFromTopEdgeDestinationSceneKey.value,
                    )
            )

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        LockscreenScene(
            lockscreenContent = lockscreenContent,
            modifier = modifier,
        )
    }

    private fun destinationScenes(
        up: SceneKey?,
        left: SceneKey?,
        downFromTopEdge: SceneKey?,
    ): Map<UserAction, UserActionResult> {
        return buildMap {
            up?.let { this[Swipe(SwipeDirection.Up)] = UserActionResult(up) }
            left?.let { this[Swipe(SwipeDirection.Left)] = UserActionResult(left) }
            downFromTopEdge?.let {
                this[Swipe(fromSource = Edge.Top, direction = SwipeDirection.Down)] =
                    UserActionResult(downFromTopEdge)
            }
            this[Swipe(direction = SwipeDirection.Down)] = UserActionResult(Scenes.Shade)
        }
    }
}

@Composable
private fun SceneScope.LockscreenScene(
    lockscreenContent: Lazy<LockscreenContent>,
    modifier: Modifier = Modifier,
) {
    animateSceneFloatAsState(
        value = QuickSettings.SharedValues.SquishinessValues.LockscreenSceneStarting,
        key = QuickSettings.SharedValues.TilesSquishiness,
    )

    lockscreenContent
        .get()
        .Content(
            modifier = modifier.fillMaxSize(),
        )
}
