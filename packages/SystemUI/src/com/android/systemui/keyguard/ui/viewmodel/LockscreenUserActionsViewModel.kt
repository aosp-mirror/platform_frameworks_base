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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.dualShadeActions
import com.android.systemui.shade.ui.viewmodel.singleShadeActions
import com.android.systemui.shade.ui.viewmodel.splitShadeActions
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Models UI state and handles user input for the lockscreen scene. */
class LockscreenUserActionsViewModel
@AssistedInject
constructor(
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val communalInteractor: CommunalInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val occlusionInteractor: SceneContainerOcclusionInteractor,
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        shadeInteractor.isShadeTouchable
            .flatMapLatest { isShadeTouchable ->
                if (!isShadeTouchable) {
                    return@flatMapLatest flowOf(emptyMap())
                }

                combine(
                    deviceEntryInteractor.isUnlocked,
                    communalInteractor.isCommunalAvailable,
                    shadeInteractor.shadeMode,
                    occlusionInteractor.isOccludingActivityShown,
                ) { isDeviceUnlocked, isCommunalAvailable, shadeMode, isOccluded ->
                    buildList {
                            if (isCommunalAvailable) {
                                add(Swipe.Start to Scenes.Communal)
                            }

                            add(Swipe.Up to if (isDeviceUnlocked) Scenes.Gone else Scenes.Bouncer)

                            addAll(
                                when (shadeMode) {
                                    ShadeMode.Single ->
                                        singleShadeActions(isDownFromTopEdgeEnabled = !isOccluded)
                                    ShadeMode.Split -> splitShadeActions()
                                    ShadeMode.Dual -> dualShadeActions()
                                }
                            )
                        }
                        .associate { it }
                }
            }
            .collect { setActions(it) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): LockscreenUserActionsViewModel
    }
}
