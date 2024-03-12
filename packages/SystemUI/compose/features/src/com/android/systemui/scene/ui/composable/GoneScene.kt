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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.animateSceneFloatAsState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.viewmodel.GoneSceneViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * "Gone" is not a real scene but rather the absence of scenes when we want to skip showing any
 * content from the scene framework.
 */
@SysUISingleton
class GoneScene
@Inject
constructor(
    private val viewModel: GoneSceneViewModel,
) : ComposableScene {
    override val key = Scenes.Gone

    override val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        viewModel.destinationScenes

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        animateSceneFloatAsState(
            value = QuickSettings.SharedValues.SquishinessValues.GoneSceneStarting,
            key = QuickSettings.SharedValues.TilesSquishiness,
        )
        Spacer(modifier.fillMaxSize())
    }
}
