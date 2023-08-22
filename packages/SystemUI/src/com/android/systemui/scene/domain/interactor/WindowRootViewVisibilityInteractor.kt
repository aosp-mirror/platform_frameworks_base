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
 * limitations under the License
 */

package com.android.systemui.scene.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Business logic about the visibility of various parts of the window root view. */
@SysUISingleton
class WindowRootViewVisibilityInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    private val windowRootViewVisibilityRepository: WindowRootViewVisibilityRepository,
    keyguardRepository: KeyguardRepository,
) {

    /**
     * True if lockscreen (including AOD) or the shade is visible and false otherwise. Notably,
     * false if the bouncer is visible.
     *
     * TODO(b/297080059): Use [SceneInteractor] as the source of truth if the scene flag is on.
     */
    val isLockscreenOrShadeVisible: StateFlow<Boolean> =
        windowRootViewVisibilityRepository.isLockscreenOrShadeVisible

    /**
     * True if lockscreen (including AOD) or the shade is visible **and** the user is currently
     * interacting with the device, false otherwise. Notably, false if the bouncer is visible and
     * false if the device is asleep.
     */
    val isLockscreenOrShadeVisibleAndInteractive: StateFlow<Boolean> =
        combine(
                isLockscreenOrShadeVisible,
                keyguardRepository.wakefulness,
            ) { isKeyguardAodOrShadeVisible, wakefulness ->
                isKeyguardAodOrShadeVisible && wakefulness.isDeviceInteractive()
            }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    fun setIsLockscreenOrShadeVisible(visible: Boolean) {
        windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(visible)
    }
}
