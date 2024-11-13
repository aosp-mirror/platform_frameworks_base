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

package com.android.systemui.shade.ui.viewmodel

import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.combine

/**
 * Models the UI state for the user actions that the user can perform to navigate to other scenes.
 *
 * Different from the [ShadeSceneContentViewModel] which models the _content_ of the scene.
 */
class ShadeUserActionsViewModel
@AssistedInject
constructor(
    private val qsSceneAdapter: QSSceneAdapter,
    private val shadeInteractor: ShadeInteractor,
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        combine(
                shadeInteractor.shadeMode,
                qsSceneAdapter.isCustomizerShowing,
            ) { shadeMode, isCustomizerShowing ->
                buildMap<UserAction, UserActionResult> {
                    if (!isCustomizerShowing) {
                        set(
                            Swipe(SwipeDirection.Up),
                            UserActionResult(
                                SceneFamilies.Home,
                                ToSplitShade.takeIf { shadeMode is ShadeMode.Split }
                            )
                        )
                    }

                    // TODO(b/330200163) Add an else to be able to collapse the shade while
                    // customizing
                    if (shadeMode is ShadeMode.Single) {
                        set(Swipe(SwipeDirection.Down), UserActionResult(Scenes.QuickSettings))
                    }
                }
            }
            .collect { actions -> setActions(actions) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShadeUserActionsViewModel
    }
}
