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
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.Edge
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
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
    private val lockscreenContent: Lazy<LockscreenContent>,
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
            lockscreenContent = lockscreenContent,
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
private fun LockscreenScene(
    lockscreenContent: Lazy<LockscreenContent>,
    modifier: Modifier = Modifier,
) {
    lockscreenContent
        .get()
        .Content(
            modifier = modifier.fillMaxSize(),
        )
}
