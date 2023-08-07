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

import com.android.systemui.R
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.SceneKey
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the lockscreen scene. */
@SysUISingleton
class LockscreenSceneViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    authenticationInteractor: AuthenticationInteractor,
    private val bouncerInteractor: BouncerInteractor,
) {
    /** The icon for the "lock" button on the lockscreen. */
    val lockButtonIcon: StateFlow<Icon> =
        authenticationInteractor.isUnlocked
            .map { isUnlocked -> lockIcon(isUnlocked = isUnlocked) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = lockIcon(isUnlocked = authenticationInteractor.isUnlocked.value),
            )

    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: Flow<SceneKey> =
        authenticationInteractor.authenticationMethod.map { authenticationMethod ->
            if (authenticationMethod is AuthenticationMethodModel.Swipe) {
                SceneKey.Gone
            } else {
                SceneKey.Bouncer
            }
        }

    /** Notifies that the lock button on the lock screen was clicked. */
    fun onLockButtonClicked() {
        bouncerInteractor.showOrUnlockDevice()
    }

    /** Notifies that some content on the lock screen was clicked. */
    fun onContentClicked() {
        bouncerInteractor.showOrUnlockDevice()
    }

    private fun upDestinationSceneKey(
        isSwipeToUnlockEnabled: Boolean,
    ): SceneKey {
        return if (isSwipeToUnlockEnabled) SceneKey.Gone else SceneKey.Bouncer
    }

    private fun lockIcon(
        isUnlocked: Boolean,
    ): Icon {
        return Icon.Resource(
            res =
                if (isUnlocked) {
                    R.drawable.ic_device_lock_off
                } else {
                    R.drawable.ic_device_lock_on
                },
            contentDescription =
                ContentDescription.Resource(
                    res =
                        if (isUnlocked) {
                            R.string.accessibility_unlock_button
                        } else {
                            R.string.accessibility_lock_icon
                        }
                )
        )
    }
}
