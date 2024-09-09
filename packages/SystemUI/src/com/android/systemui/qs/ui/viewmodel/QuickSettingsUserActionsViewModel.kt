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

package com.android.systemui.qs.ui.viewmodel

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Models the UI state needed to figure out which user actions can trigger navigation from the quick
 * settings scene to other scenes.
 *
 * Different from [QuickSettingsSceneContentViewModel] that models UI state needed for rendering the
 * content of the quick settings scene.
 */
class QuickSettingsUserActionsViewModel
@AssistedInject
constructor(
    private val qsSceneAdapter: QSSceneAdapter,
    sceneBackInteractor: SceneBackInteractor,
) : UserActionsViewModel() {

    private val backScene: Flow<SceneKey> =
        sceneBackInteractor.backScene
            .filter { it != Scenes.QuickSettings }
            .map { it ?: Scenes.Shade }

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        combine(
                qsSceneAdapter.isCustomizerShowing,
                backScene,
            ) { isCustomizing, backScene ->
                buildMap<UserAction, UserActionResult> {
                    if (isCustomizing) {
                        // TODO(b/332749288) Empty map so there are no back handlers and back can
                        // close
                        // customizer

                        // TODO(b/330200163) Add an Up from Bottom to be able to collapse the shade
                        // while customizing
                    } else {
                        put(Back, UserActionResult(backScene))
                        put(Swipe(SwipeDirection.Up), UserActionResult(backScene))
                        put(
                            Swipe(fromSource = Edge.Bottom, direction = SwipeDirection.Up),
                            UserActionResult(SceneFamilies.Home),
                        )
                    }
                }
            }
            .collect { actions -> setActions(actions) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickSettingsUserActionsViewModel
    }
}
