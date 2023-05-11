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
import com.android.systemui.util.kotlin.pairwise
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

/** Hosts business and application state accessing logic for the lock screen scene. */
class LockScreenSceneInteractor
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

    /** Whether it's currently possible to swipe up to dismiss the lock screen. */
    val isSwipeToDismissEnabled: StateFlow<Boolean> =
        combine(
                authenticationInteractor.isUnlocked,
                authenticationInteractor.authenticationMethod,
            ) { isUnlocked, authMethod ->
                isSwipeToUnlockEnabled(
                    isUnlocked = isUnlocked,
                    authMethod = authMethod,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    isSwipeToUnlockEnabled(
                        isUnlocked = authenticationInteractor.isUnlocked.value,
                        authMethod = authenticationInteractor.authenticationMethod.value,
                    ),
            )

    init {
        // LOCKING SHOWS LOCK SCREEN.
        //
        // Move to the lock screen scene if the device becomes locked while in any scene.
        applicationScope.launch {
            authenticationInteractor.isUnlocked
                .map { !it }
                .distinctUntilChanged()
                .collect { isLocked ->
                    if (isLocked) {
                        sceneInteractor.setCurrentScene(
                            containerName = containerName,
                            scene = SceneModel(SceneKey.LockScreen),
                        )
                    }
                }
        }

        // BYPASS UNLOCK.
        //
        // Moves to the gone scene if bypass is enabled and the device becomes unlocked while in the
        // lock screen scene.
        applicationScope.launch {
            combine(
                    authenticationInteractor.isBypassEnabled,
                    authenticationInteractor.isUnlocked,
                    sceneInteractor.currentScene(containerName),
                    ::Triple,
                )
                .collect { (isBypassEnabled, isUnlocked, currentScene) ->
                    if (isBypassEnabled && isUnlocked && currentScene.key == SceneKey.LockScreen) {
                        sceneInteractor.setCurrentScene(
                            containerName = containerName,
                            scene = SceneModel(SceneKey.Gone),
                        )
                    }
                }
        }

        // SWIPE TO DISMISS LOCK SCREEN.
        //
        // If switched from the lock screen to the gone scene and the auth method was a swipe,
        // unlocks the device.
        applicationScope.launch {
            combine(
                    authenticationInteractor.authenticationMethod,
                    sceneInteractor.currentScene(containerName).pairwise(),
                    ::Pair,
                )
                .collect { (authMethod, scenes) ->
                    val (previousScene, currentScene) = scenes
                    if (
                        authMethod is AuthenticationMethodModel.Swipe &&
                            previousScene.key == SceneKey.LockScreen &&
                            currentScene.key == SceneKey.Gone
                    ) {
                        authenticationInteractor.unlockDevice()
                    }
                }
        }

        // DISMISS LOCK SCREEN IF AUTH METHOD IS REMOVED.
        //
        // If the auth method becomes None while on the lock screen scene, dismisses the lock
        // screen.
        applicationScope.launch {
            combine(
                    authenticationInteractor.authenticationMethod,
                    sceneInteractor.currentScene(containerName),
                    ::Pair,
                )
                .collect { (authMethod, scene) ->
                    if (
                        scene.key == SceneKey.LockScreen &&
                            authMethod == AuthenticationMethodModel.None
                    ) {
                        sceneInteractor.setCurrentScene(
                            containerName = containerName,
                            scene = SceneModel(SceneKey.Gone),
                        )
                    }
                }
        }
    }

    /** Attempts to dismiss the lock screen. This will cause the bouncer to show, if needed. */
    fun dismissLockScreen() {
        bouncerInteractor.showOrUnlockDevice(containerName = containerName)
    }

    private fun isSwipeToUnlockEnabled(
        isUnlocked: Boolean,
        authMethod: AuthenticationMethodModel,
    ): Boolean {
        return !isUnlocked && authMethod is AuthenticationMethodModel.Swipe
    }

    @AssistedFactory
    interface Factory {
        fun create(
            containerName: String,
        ): LockScreenSceneInteractor
    }
}
