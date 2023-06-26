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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Hosts business and application state accessing logic for the lockscreen scene. */
class LockscreenSceneInteractor
@AssistedInject
constructor(
    @Application applicationScope: CoroutineScope,
    private val authenticationInteractor: AuthenticationInteractor,
    bouncerInteractorFactory: BouncerInteractor.Factory,
    private val sceneInteractor: SceneInteractor,
    @Assisted private val containerName: String,
) {
    private val bouncerInteractor: BouncerInteractor =
        bouncerInteractorFactory.create(containerName)

    /** Whether the device is currently locked. */
    val isDeviceLocked: StateFlow<Boolean> =
        authenticationInteractor.isUnlocked
            .map { !it }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = !authenticationInteractor.isUnlocked.value,
            )

    /** Whether it's currently possible to swipe up to dismiss the lockscreen. */
    val isSwipeToDismissEnabled: StateFlow<Boolean> =
        authenticationInteractor.isUnlocked
            .map { isUnlocked ->
                !isUnlocked &&
                    authenticationInteractor.getAuthenticationMethod() is
                        AuthenticationMethodModel.Swipe
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    init {
        // LOCKING SHOWS Lockscreen.
        //
        // Move to the lockscreen scene if the device becomes locked while in any scene.
        applicationScope.launch {
            authenticationInteractor.isUnlocked
                .map { !it }
                .distinctUntilChanged()
                .collect { isLocked ->
                    if (isLocked) {
                        sceneInteractor.setCurrentScene(
                            containerName = containerName,
                            scene = SceneModel(SceneKey.Lockscreen),
                        )
                    }
                }
        }

        // BYPASS UNLOCK.
        //
        // Moves to the gone scene if bypass is enabled and the device becomes unlocked while in the
        // lockscreen scene.
        applicationScope.launch {
            combine(
                    authenticationInteractor.isBypassEnabled,
                    authenticationInteractor.isUnlocked,
                    sceneInteractor.currentScene(containerName),
                    ::Triple,
                )
                .collect { (isBypassEnabled, isUnlocked, currentScene) ->
                    if (isBypassEnabled && isUnlocked && currentScene.key == SceneKey.Lockscreen) {
                        sceneInteractor.setCurrentScene(
                            containerName = containerName,
                            scene = SceneModel(SceneKey.Gone),
                        )
                    }
                }
        }
    }

    /** Attempts to dismiss the lockscreen. This will cause the bouncer to show, if needed. */
    fun dismissLockscreen() {
        bouncerInteractor.showOrUnlockDevice(containerName = containerName)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): LockscreenSceneInteractor
    }
}
