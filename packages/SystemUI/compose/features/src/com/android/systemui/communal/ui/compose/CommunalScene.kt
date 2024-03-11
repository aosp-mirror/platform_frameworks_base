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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The communal scene shows glanceable hub when the device is locked and docked. */
@SysUISingleton
class CommunalScene
@Inject
constructor(
    private val viewModel: CommunalViewModel,
    private val dialogFactory: SystemUIDialogFactory,
) : ComposableScene {
    override val key = Scenes.Communal

    override val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        MutableStateFlow<Map<UserAction, UserActionResult>>(
                mapOf(
                    Swipe(SwipeDirection.Right) to UserActionResult(Scenes.Lockscreen),
                )
            )
            .asStateFlow()

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        CommunalHub(modifier, viewModel, dialogFactory)
    }
}
