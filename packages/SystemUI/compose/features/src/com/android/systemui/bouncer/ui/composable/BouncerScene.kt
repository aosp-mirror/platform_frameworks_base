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

package com.android.systemui.bouncer.ui.composable

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
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The bouncer scene displays authentication challenges like PIN, password, or pattern. */
@SysUISingleton
class BouncerScene
@Inject
constructor(
    private val viewModel: BouncerViewModel,
) : ComposableScene {
    override val key = SceneKey.Bouncer

    override fun destinationScenes(): StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
                mapOf(
                    UserAction.Back to SceneModel(SceneKey.LockScreen),
                )
            )
            .asStateFlow()

    @Composable
    override fun Content(
        modifier: Modifier,
    ) {
        // TODO(b/280877228): implement the real UI.

        Box(modifier = modifier) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Bouncer", style = MaterialTheme.typography.headlineLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { viewModel.onAuthenticateButtonClicked() }) {
                        Text("Authenticate")
                    }
                }
            }
        }
    }
}
