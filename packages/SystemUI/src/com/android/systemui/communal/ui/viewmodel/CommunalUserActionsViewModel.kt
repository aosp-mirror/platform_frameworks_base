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

package com.android.systemui.communal.ui.viewmodel

import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.dualShadeActions
import com.android.systemui.shade.ui.viewmodel.singleShadeActions
import com.android.systemui.shade.ui.viewmodel.splitShadeActions
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Provides scene container user actions and results. */
class CommunalUserActionsViewModel
@AssistedInject
constructor(
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
    private val shadeInteractor: ShadeInteractor,
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        shadeInteractor.isShadeTouchable
            .flatMapLatestConflated { isShadeTouchable ->
                if (!isShadeTouchable) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked },
                        shadeInteractor.shadeMode,
                    ) { isDeviceUnlocked, shadeMode ->
                        buildList {
                                val bouncerOrGone =
                                    if (isDeviceUnlocked) Scenes.Gone else Scenes.Bouncer
                                add(Swipe.Up to bouncerOrGone)

                                // "Home" is either Lockscreen, or Gone - if the device is entered.
                                add(Swipe.End to SceneFamilies.Home)

                                addAll(
                                    when (shadeMode) {
                                        ShadeMode.Single -> singleShadeActions()
                                        ShadeMode.Split -> splitShadeActions()
                                        ShadeMode.Dual -> dualShadeActions()
                                    }
                                )
                            }
                            .associate { it }
                    }
                }
            }
            .collect { setActions(it) }
    }

    @AssistedFactory
    interface Factory {
        fun create(): CommunalUserActionsViewModel
    }
}
