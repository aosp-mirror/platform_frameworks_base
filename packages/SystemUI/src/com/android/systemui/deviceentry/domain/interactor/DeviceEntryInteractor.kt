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

package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts application business logic related to device entry.
 *
 * Device entry occurs when the user successfully dismisses (or bypasses) the lockscreen, regardless
 * of the authentication method used.
 */
@SysUISingleton
class DeviceEntryInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    repository: DeviceEntryRepository,
    private val authenticationInteractor: AuthenticationInteractor,
    private val sceneInteractor: SceneInteractor,
    deviceEntryFaceAuthRepository: DeviceEntryFaceAuthRepository,
    trustRepository: TrustRepository,
    flags: SceneContainerFlags,
) {
    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method, unless in cases when the current
     * authentication method is not "secure" (for example, None and Swipe); in such cases, the value
     * of this flow will always be `true`, even if the lockscreen is showing and still needs to be
     * dismissed by the user to proceed.
     */
    val isUnlocked: StateFlow<Boolean> =
        combine(
                repository.isUnlocked,
                authenticationInteractor.authenticationMethod,
            ) { isUnlocked, authenticationMethod ->
                !authenticationMethod.isSecure || isUnlocked
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Whether the device has been entered (i.e. the lockscreen has been dismissed, by any method).
     * This can be `false` when the device is unlocked, e.g. when the user still needs to swipe away
     * the non-secure lockscreen, even though they've already authenticated.
     *
     * Note: This does not imply that the lockscreen is visible or not.
     */
    val isDeviceEntered: StateFlow<Boolean> =
        sceneInteractor.desiredScene
            .map { it.key }
            .filter { currentScene ->
                currentScene == SceneKey.Gone || currentScene == SceneKey.Lockscreen
            }
            .map { it == SceneKey.Gone }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    // Authenticated by a TrustAgent like trusted device, location, etc or by face auth.
    private val passivelyAuthenticated =
        merge(
            trustRepository.isCurrentUserTrusted,
            deviceEntryFaceAuthRepository.isAuthenticated,
        )

    /**
     * Whether it's currently possible to swipe up to enter the device without requiring
     * authentication. This returns `false` whenever the lockscreen has been dismissed.
     *
     * Note: `true` doesn't mean the lockscreen is visible. It may be occluded or covered by other
     * UI.
     */
    val canSwipeToEnter =
        combine(
                authenticationInteractor.authenticationMethod.map {
                    it == AuthenticationMethodModel.Swipe
                },
                passivelyAuthenticated,
                isDeviceEntered
            ) { isSwipeAuthMethod, passivelyAuthenticated, isDeviceEntered ->
                (isSwipeAuthMethod || passivelyAuthenticated) && !isDeviceEntered
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /**
     * Attempt to enter the device and dismiss the lockscreen. If authentication is required to
     * unlock the device it will transition to bouncer.
     */
    fun attemptDeviceEntry() {
        // TODO (b/307768356),
        //       1. Check if the device is already authenticated by trust agent/passive biometrics
        //       2. show SPFS/UDFPS bouncer if it is available AlternateBouncerInteractor.show
        //       3. For face auth only setups trigger face auth, delay transitioning to bouncer for
        //          a small amount of time.
        //       4. Transition to bouncer scene
        applicationScope.launch {
            if (isAuthenticationRequired()) {
                sceneInteractor.changeScene(
                    scene = SceneModel(SceneKey.Bouncer),
                    loggingReason = "request to unlock device while authentication required",
                )
            } else {
                sceneInteractor.changeScene(
                    scene = SceneModel(SceneKey.Gone),
                    loggingReason = "request to unlock device while authentication isn't required",
                )
            }
        }
    }

    /**
     * Returns `true` if the device currently requires authentication before entry is granted;
     * `false` if the device can be entered without authenticating first.
     */
    suspend fun isAuthenticationRequired(): Boolean {
        return !isUnlocked.value && authenticationInteractor.getAuthenticationMethod().isSecure
    }

    /**
     * Whether lock screen bypass is enabled. When enabled, the lock screen will be automatically
     * dismissed once the authentication challenge is completed. For example, completing a biometric
     * authentication challenge via face unlock or fingerprint sensor can automatically bypass the
     * lock screen.
     */
    val isBypassEnabled: StateFlow<Boolean> = repository.isBypassEnabled

    init {
        if (flags.isEnabled()) {
            applicationScope.launch {
                authenticationInteractor.authenticationChallengeResult.collectLatest { successful ->
                    if (successful) {
                        repository.reportSuccessfulAuthentication()
                    }
                }
            }
        }
    }
}
