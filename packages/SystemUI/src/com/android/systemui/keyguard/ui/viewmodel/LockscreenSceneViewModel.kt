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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the lockscreen scene. */
@SysUISingleton
class LockscreenSceneViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    deviceEntryInteractor: DeviceEntryInteractor,
    communalInteractor: CommunalInteractor,
    shadeInteractor: ShadeInteractor,
    val longPress: KeyguardLongPressViewModel,
    val notifications: NotificationsPlaceholderViewModel,
) {
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
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
                        destinationScenes(
                            isDeviceUnlocked = isDeviceUnlocked,
                            isCommunalAvailable = isCommunalAvailable,
                            shadeMode = shadeMode,
                        )
                    }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    destinationScenes(
                        isDeviceUnlocked = deviceEntryInteractor.isUnlocked.value,
                        isCommunalAvailable = false,
                        shadeMode = shadeInteractor.shadeMode.value,
                    ),
            )

    private fun destinationScenes(
        isDeviceUnlocked: Boolean,
        isCommunalAvailable: Boolean,
        shadeMode: ShadeMode,
    ): Map<UserAction, UserActionResult> {
        val shadeSceneKey =
            UserActionResult(
                toScene =
                    if (shadeMode is ShadeMode.Dual) Scenes.NotificationsShade else Scenes.Shade,
                transitionKey = ToSplitShade.takeIf { shadeMode is ShadeMode.Split },
            )

        val quickSettingsIfSingleShade =
            if (shadeMode is ShadeMode.Single) UserActionResult(Scenes.QuickSettings)
            else shadeSceneKey

        return mapOf(
                Swipe.Left to UserActionResult(Scenes.Communal).takeIf { isCommunalAvailable },
                Swipe.Up to if (isDeviceUnlocked) Scenes.Gone else Scenes.Bouncer,

                // Swiping down from the top edge goes to QS (or shade if in split shade mode).
                swipeDownFromTop(pointerCount = 1) to quickSettingsIfSingleShade,
                swipeDownFromTop(pointerCount = 2) to
                    // TODO(b/338577208): Remove 'Dual' once we add Dual Shade invocation zones.
                    if (shadeMode is ShadeMode.Dual) {
                        UserActionResult(Scenes.QuickSettingsShade)
                    } else {
                        quickSettingsIfSingleShade
                    },

                // Swiping down, not from the edge, always navigates to the shade scene.
                swipeDown(pointerCount = 1) to shadeSceneKey,
                swipeDown(pointerCount = 2) to shadeSceneKey,
            )
            .filterValues { it != null }
            .mapValues { checkNotNull(it.value) }
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
}
