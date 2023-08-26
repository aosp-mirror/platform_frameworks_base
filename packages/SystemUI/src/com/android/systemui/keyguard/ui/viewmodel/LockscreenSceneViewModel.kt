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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.model.SceneKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Models UI state and handles user input for the lockscreen scene. */
@SysUISingleton
class LockscreenSceneViewModel
@Inject
constructor(
    authenticationInteractor: AuthenticationInteractor,
    private val bouncerInteractor: BouncerInteractor,
) {
    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: Flow<SceneKey> =
        authenticationInteractor.isUnlocked.map { isUnlocked ->
            if (isUnlocked) {
                SceneKey.Gone
            } else {
                SceneKey.Bouncer
            }
        }

    /** Notifies that the lock button on the lock screen was clicked. */
    fun onLockButtonClicked() {
        bouncerInteractor.showOrUnlockDevice()
    }
}
