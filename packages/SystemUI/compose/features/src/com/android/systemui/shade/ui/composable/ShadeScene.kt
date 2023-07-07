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

package com.android.systemui.shade.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The shade scene shows scrolling list of notifications and some of the quick setting tiles. */
class ShadeScene(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: ShadeSceneViewModel,
) : ComposableScene {
    override val key = SceneKey.Shade

    override fun destinationScenes(
        containerName: String,
    ): StateFlow<Map<UserAction, SceneModel>> =
        viewModel.upDestinationSceneKey
            .map { sceneKey -> destinationScenes(up = sceneKey) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = destinationScenes(up = viewModel.upDestinationSceneKey.value),
            )

    @Composable
    override fun Content(
        containerName: String,
        modifier: Modifier,
    ) {
        ShadeScene(
            viewModel = viewModel,
            modifier = modifier,
        )
    }

    private fun destinationScenes(
        up: SceneKey,
    ): Map<UserAction, SceneModel> {
        return mapOf(
            UserAction.Swipe(Direction.UP) to SceneModel(up),
            UserAction.Swipe(Direction.DOWN) to SceneModel(SceneKey.QuickSettings),
        )
    }
}

@Composable
private fun ShadeScene(
    viewModel: ShadeSceneViewModel,
    modifier: Modifier = Modifier,
) {
    // TODO(b/280887022): implement the real UI.

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text("Shade", style = MaterialTheme.typography.headlineMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.onContentClicked() },
                ) {
                    Text("Open some content")
                }
            }
        }
    }
}
