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

/** Models UI state and handles user input for the shade scene. */
class ShadeSceneViewModel
@AssistedInject
constructor(
    @Application private val applicationScope: CoroutineScope,
    lockScreenSceneInteractorFactory: LockScreenSceneInteractor.Factory,
    @Assisted private val containerName: String,
) {
    private val lockScreenInteractor: LockScreenSceneInteractor =
        lockScreenSceneInteractorFactory.create(containerName)

    /** The key of the scene we should switch to when swiping up. */
    val upDestinationSceneKey: StateFlow<SceneKey> =
        lockScreenInteractor.isDeviceLocked
            .map { isLocked -> upDestinationSceneKey(isLocked = isLocked) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    upDestinationSceneKey(
                        isLocked = lockScreenInteractor.isDeviceLocked.value,
                    ),
            )

    /** Notifies that some content in the shade was clicked. */
    fun onContentClicked() {
        lockScreenInteractor.dismissLockScreen()
    }

    private fun upDestinationSceneKey(
        isLocked: Boolean,
    ): SceneKey {
        return if (isLocked) SceneKey.LockScreen else SceneKey.Gone
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): ShadeSceneViewModel
    }
}
