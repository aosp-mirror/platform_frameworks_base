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

package com.android.systemui.scene.ui.viewmodel

import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.SwipeDirection
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.GoneToSplitShade
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class GoneSceneViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    shadeInteractor: ShadeInteractor,
) {
    val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        shadeInteractor.shadeMode
            .map { shadeMode -> destinationScenes(shadeMode = shadeMode) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = destinationScenes(shadeMode = shadeInteractor.shadeMode.value)
            )

    private fun destinationScenes(shadeMode: ShadeMode): Map<UserAction, UserActionResult> {
        return buildMap {
            if (shadeMode is ShadeMode.Single) {
                this[
                    Swipe(
                        pointerCount = 2,
                        fromSource = Edge.Top,
                        direction = SwipeDirection.Down,
                    )] = UserActionResult(Scenes.QuickSettings)
            }

            // TODO(b/338577208): Remove this once we add Dual Shade invocation zones.
            if (shadeMode is ShadeMode.Dual) {
                this[
                    Swipe(
                        pointerCount = 2,
                        fromSource = Edge.Top,
                        direction = SwipeDirection.Down,
                    )] = UserActionResult(Scenes.QuickSettingsShade)
            }

            val downSceneKey =
                if (shadeMode is ShadeMode.Dual) Scenes.NotificationsShade else Scenes.Shade
            val downTransitionKey = GoneToSplitShade.takeIf { shadeMode is ShadeMode.Split }
            this[Swipe(direction = SwipeDirection.Down)] =
                UserActionResult(downSceneKey, downTransitionKey)
        }
    }
}
