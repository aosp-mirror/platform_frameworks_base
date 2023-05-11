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
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.LockScreenSceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user input for the lock screen scene. */
class LockScreenSceneViewModel
@AssistedInject
constructor(
    @Application applicationScope: CoroutineScope,
    interactorFactory: LockScreenSceneInteractor.Factory,
    @Assisted containerName: String,
) {
    private val interactor: LockScreenSceneInteractor = interactorFactory.create(containerName)

    /** The icon for the "lock" button on the lock screen. */
    val lockButtonIcon: StateFlow<Icon> =
        interactor.isDeviceLocked
            .map { isLocked -> lockIcon(isLocked = isLocked) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = lockIcon(isLocked = interactor.isDeviceLocked.value),
            )

    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: StateFlow<SceneKey> =
        interactor.isSwipeToDismissEnabled
            .map { isSwipeToUnlockEnabled -> upDestinationSceneKey(isSwipeToUnlockEnabled) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = upDestinationSceneKey(interactor.isSwipeToDismissEnabled.value),
            )

    /** Notifies that the lock button on the lock screen was clicked. */
    fun onLockButtonClicked() {
        interactor.dismissLockScreen()
    }

    /** Notifies that some content on the lock screen was clicked. */
    fun onContentClicked() {
        interactor.dismissLockScreen()
    }

    private fun upDestinationSceneKey(
        isSwipeToUnlockEnabled: Boolean,
    ): SceneKey {
        return if (isSwipeToUnlockEnabled) SceneKey.Gone else SceneKey.Bouncer
    }

    private fun lockIcon(
        isLocked: Boolean,
    ): Icon {
        return Icon.Resource(
            res =
                if (isLocked) {
                    R.drawable.ic_device_lock_on
                } else {
                    R.drawable.ic_device_lock_off
                },
            contentDescription =
                ContentDescription.Resource(
                    res =
                        if (isLocked) {
                            R.string.accessibility_lock_icon
                        } else {
                            R.string.accessibility_unlock_button
                        }
                )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): LockScreenSceneViewModel
    }
}
