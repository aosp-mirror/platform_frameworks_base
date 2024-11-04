/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.dream.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dreams.ui.viewmodel.DreamUserActionsViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Scene
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The dream scene shows when a dream activity is showing. */
@SysUISingleton
class DreamScene
@Inject
constructor(private val actionsViewModelFactory: DreamUserActionsViewModel.Factory) :
    ExclusiveActivatable(), Scene {
    override val key = Scenes.Dream

    private val actionsViewModel: DreamUserActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override suspend fun onActivated(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        Box(modifier = modifier.fillMaxSize()) {
            // Render a sleep emoji to make the scene appear visible.
            Text(
                modifier = Modifier.padding(16.dp).align(Alignment.BottomStart),
                text = "\uD83D\uDCA4",
            )
        }
    }
}
