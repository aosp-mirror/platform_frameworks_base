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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.communal.ui.viewmodel.CommunalUserActionsViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Scene
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** The communal scene shows glanceable hub when the device is locked and docked. */
@SysUISingleton
class CommunalScene
@Inject
constructor(
    private val contentViewModel: CommunalViewModel,
    actionsViewModelFactory: CommunalUserActionsViewModel.Factory,
    private val communalColors: CommunalColors,
    private val communalContent: CommunalContent,
) : ExclusiveActivatable(), Scene {
    override val key = Scenes.Communal

    private val actionsViewModel: CommunalUserActionsViewModel = actionsViewModelFactory.create()

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override suspend fun onActivated(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        val backgroundType by
            contentViewModel.communalBackground.collectAsStateWithLifecycle(
                initialValue = CommunalBackgroundType.ANIMATED
            )

        CommunalScene(
            backgroundType = backgroundType,
            colors = communalColors,
            content = communalContent,
            viewModel = contentViewModel,
            modifier = modifier.horizontalNestedScrollToScene(),
        )
    }
}
