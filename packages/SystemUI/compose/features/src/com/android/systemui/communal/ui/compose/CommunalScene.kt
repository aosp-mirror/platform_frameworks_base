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

package com.android.systemui.communal.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.model.Direction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.UserAction
import com.android.systemui.scene.ui.composable.ComposableScene
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The communal scene shows glanceable hub when the device is locked and docked. */
@SysUISingleton
class CommunalScene @Inject constructor() : ComposableScene {
    override val key = SceneKey.Communal

    override val destinationScenes: StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
                mapOf(
                    UserAction.Swipe(Direction.RIGHT) to SceneModel(SceneKey.Lockscreen),
                )
            )
            .asStateFlow()

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.White),
        ) {
            Text(
                modifier = Modifier.align(Alignment.Center),
                text = "Hello Communal!",
            )
        }
    }
}
