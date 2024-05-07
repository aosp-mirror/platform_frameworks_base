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

package com.android.systemui.qs.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.ui.viewmodel.QuickSettingsShadeSceneViewModel
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.viewmodel.OverlayShadeViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@SysUISingleton
class QuickSettingsShadeScene
@Inject
constructor(
    viewModel: QuickSettingsShadeSceneViewModel,
    private val overlayShadeViewModel: OverlayShadeViewModel,
) : ComposableScene {

    override val key = Scenes.QuickSettingsShade

    override val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        viewModel.destinationScenes

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        OverlayShade(
            viewModel = overlayShadeViewModel,
            modifier = modifier,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "Quick settings grid",
                modifier = Modifier.padding(QuickSettingsShade.Dimensions.Padding)
            )
        }
    }
}

object QuickSettingsShade {
    object Dimensions {
        val Padding = 16.dp
    }
}
