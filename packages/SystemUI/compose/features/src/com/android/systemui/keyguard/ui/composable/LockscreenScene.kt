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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
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
) : ComposableScene {
    override val key = SceneKey.Lockscreen

    override fun destinationScenes(): StateFlow<Map<UserAction, SceneModel>> =
        viewModel.upDestinationSceneKey
            .map { pageKey -> destinationScenes(up = pageKey) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = destinationScenes(up = viewModel.upDestinationSceneKey.value)
            )

    @Composable
    override fun Content(
        modifier: Modifier,
    ) {
        LockscreenScene(
            viewModel = viewModel,
            modifier = modifier,
        )
    }

    private fun destinationScenes(
        up: SceneKey,
    ): Map<UserAction, SceneModel> {
        return mapOf(
            UserAction.Swipe(Direction.UP) to SceneModel(up),
            UserAction.Swipe(Direction.DOWN) to SceneModel(SceneKey.Shade)
        )
    }
}

@Composable
private fun LockscreenScene(
    viewModel: LockscreenSceneViewModel,
    modifier: Modifier = Modifier,
) {
    // TODO(b/280879610): implement the real UI.

    val lockButtonIcon: Icon by viewModel.lockButtonIcon.collectAsState()

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text("Lockscreen", style = MaterialTheme.typography.headlineMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.onLockButtonClicked() }) { Icon(lockButtonIcon) }

                Button(onClick = { viewModel.onContentClicked() }) { Text("Open some content") }
            }
        }
    }
}
