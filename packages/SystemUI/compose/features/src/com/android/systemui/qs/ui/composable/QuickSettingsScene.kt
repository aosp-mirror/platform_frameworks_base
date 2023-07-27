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

package com.android.systemui.qs.ui.composable

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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneViewModel
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Quick Settings (AKA "QS") scene shows the quick setting tiles. */
@SysUISingleton
class QuickSettingsScene
@Inject
constructor(
    private val viewModel: QuickSettingsSceneViewModel,
) : ComposableScene {
    override val key = SceneKey.QuickSettings

    override fun destinationScenes(): StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
                mapOf(
                    UserAction.Swipe(Direction.UP) to SceneModel(SceneKey.Shade),
                )
            )
            .asStateFlow()

    @Composable
    override fun Content(
        modifier: Modifier,
    ) {
        QuickSettingsScene(
            viewModel = viewModel,
            modifier = modifier,
        )
    }
}

@Composable
private fun QuickSettingsScene(
    viewModel: QuickSettingsSceneViewModel,
    modifier: Modifier = Modifier,
) {
    // TODO(b/280887232): implement the real UI.

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text("Quick settings", style = MaterialTheme.typography.headlineMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.onContentClicked() }) { Text("Open some content") }
            }
        }
    }
}
