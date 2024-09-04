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

import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.util.kotlin.filterValuesNotNull
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
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        shadeInteractor.isShadeTouchable
            .flatMapLatest { isShadeTouchable ->
                if (!isShadeTouchable) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        deviceEntryInteractor.isUnlocked,
                        communalInteractor.isCommunalAvailable,
                        shadeInteractor.shadeMode,
                    ) { isDeviceUnlocked, isCommunalAvailable, shadeMode ->
                        val notifShadeSceneKey =
                            UserActionResult(
                                toScene = SceneFamilies.NotifShade,
                                transitionKey =
                                    ToSplitShade.takeIf { shadeMode is ShadeMode.Split },
                            )

                        mapOf(
                                Swipe.Left to
                                    UserActionResult(Scenes.Communal).takeIf {
                                        isCommunalAvailable
                                    },
                                Swipe.Up to if (isDeviceUnlocked) Scenes.Gone else Scenes.Bouncer,

                                // Swiping down from the top edge goes to QS (or shade if in split
                                // shade mode).
                                swipeDownFromTop(pointerCount = 1) to
                                    if (shadeMode is ShadeMode.Single) {
                                        UserActionResult(Scenes.QuickSettings)
                                    } else {
                                        notifShadeSceneKey
                                    },

                                // TODO(b/338577208): Remove once we add Dual Shade invocation zones
                                swipeDownFromTop(pointerCount = 2) to
                                    UserActionResult(
                                        toScene = SceneFamilies.QuickSettings,
                                        transitionKey =
                                            ToSplitShade.takeIf { shadeMode is ShadeMode.Split }
                                    ),

                                // Swiping down, not from the edge, always navigates to the notif
                                // shade scene.
                                swipeDown(pointerCount = 1) to notifShadeSceneKey,
                                swipeDown(pointerCount = 2) to notifShadeSceneKey,
                            )
                            .filterValuesNotNull()
                    }
                }
            }
            .collect { setActions(it) }
    }

    private fun swipeDownFromTop(pointerCount: Int): Swipe {
        return Swipe(
            SwipeDirection.Down,
            fromSource = Edge.Top,
            pointerCount = pointerCount,
        )
    }

    private fun swipeDown(pointerCount: Int): Swipe {
        return Swipe(
            SwipeDirection.Down,
            pointerCount = pointerCount,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): LockscreenUserActionsViewModel
    }
}
