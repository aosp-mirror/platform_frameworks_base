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

package com.android.systemui.authentication.domain.interactor

import android.app.admin.DevicePolicyManager
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Hosts application business logic related to authentication. */
@SysUISingleton
class AuthenticationInteractor
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val repository: AuthenticationRepository,
) {
    /**
     * The currently-configured authentication method. This determines how the authentication
     * challenge is completed in order to unlock an otherwise locked device.
     */
    val authenticationMethod: StateFlow<AuthenticationMethodModel> = repository.authenticationMethod

    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method.
     *
     * Note that this state has no real bearing on whether the lock screen is showing or dismissed.
     */
    val isUnlocked: StateFlow<Boolean> =
        combine(authenticationMethod, repository.isUnlocked) { authMethod, isUnlocked ->
                isUnlockedWithAuthMethod(
                    isUnlocked = isUnlocked,
                    authMethod = authMethod,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    isUnlockedWithAuthMethod(
                        isUnlocked = repository.isUnlocked.value,
                        authMethod = repository.authenticationMethod.value,
                    )
            )

    /**
     * Whether lock screen bypass is enabled. When enabled, the lock screen will be automatically
     * dismisses once the authentication challenge is completed. For example, completing a biometric
     * authentication challenge via face unlock or fingerprint sensor can automatically bypass the
     * lock screen.
     */
    val isBypassEnabled: StateFlow<Boolean> = repository.isBypassEnabled

    /**
     * Number of consecutively failed authentication attempts. This resets to `0` when
     * authentication succeeds.
     */
    val failedAuthenticationAttempts: StateFlow<Int> = repository.failedAuthenticationAttempts

    init {
        // UNLOCKS WHEN AUTH METHOD REMOVED.
        //
        // Unlocks the device if the auth method becomes None.
        applicationScope.launch {
            repository.authenticationMethod.collect {
                if (it is AuthenticationMethodModel.None) {
                    unlockDevice()
                }
            }
        }
    }

    /**
     * Returns `true` if the device currently requires authentication before content can be viewed;
     * `false` if content can be displayed without unlocking first.
     */
    fun isAuthenticationRequired(): Boolean {
        return !isUnlocked.value && authenticationMethod.value.isSecure
    }

    /**
     * Unlocks the device, assuming that the authentication challenge has been completed
     * successfully.
     */
    fun unlockDevice() {
        repository.setUnlocked(true)
    }

    /**
     * Locks the device. From now on, the device will remain locked until [authenticate] is called
     * with the correct input.
     */
    fun lockDevice() {
        repository.setUnlocked(false)
    }

    /**
     * Attempts to authenticate the user and unlock the device.
     *
     * If [tryAutoConfirm] is `true`, authentication is attempted if and only if the auth method
     * supports auto-confirming, and the input's length is at least the code's length. Otherwise,
     * `null` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return `true` if the authentication succeeded and the device is now unlocked; `false` when
     *   authentication failed, `null` if the check was not performed.
     */
    fun authenticate(input: List<Any>, tryAutoConfirm: Boolean = false): Boolean? {
        val authMethod = this.authenticationMethod.value
        if (tryAutoConfirm) {
            if ((authMethod as? AuthenticationMethodModel.Pin)?.autoConfirm != true) {
                // Do not attempt to authenticate unless the PIN lock is set to auto-confirm.
                return null
            }

            if (input.size < authMethod.code.size) {
                // Do not attempt to authenticate if the PIN has not yet the required amount of
                // digits. This intentionally only skip for shorter PINs; if the PIN is longer, the
                // layer above might have throttled this check, and the PIN should be rejected via
                // the auth code below.
                return null
            }
        }

        val isSuccessful =
            when (authMethod) {
                is AuthenticationMethodModel.Pin -> input.asCode() == authMethod.code
                is AuthenticationMethodModel.Password -> input.asPassword() == authMethod.password
                is AuthenticationMethodModel.Pattern -> input.asPattern() == authMethod.coordinates
                else -> true
            }

        if (isSuccessful) {
            repository.setFailedAuthenticationAttempts(0)
            repository.setUnlocked(true)
        } else {
            repository.setFailedAuthenticationAttempts(
                repository.failedAuthenticationAttempts.value + 1
            )
        }

        return isSuccessful
    }

    /** Triggers a biometric-powered unlock of the device. */
    fun biometricUnlock() {
        // TODO(b/280883900): only allow this if the biometric is enabled and there's a match.
        repository.setUnlocked(true)
    }

    /** See [authenticationMethod]. */
    fun setAuthenticationMethod(authenticationMethod: AuthenticationMethodModel) {
        repository.setAuthenticationMethod(authenticationMethod)
    }

    /** See [isBypassEnabled]. */
    fun toggleBypassEnabled() {
        repository.setBypassEnabled(!repository.isBypassEnabled.value)
    }

    companion object {
        private fun isUnlockedWithAuthMethod(
            isUnlocked: Boolean,
            authMethod: AuthenticationMethodModel,
        ): Boolean {
            return if (authMethod is AuthenticationMethodModel.None) {
                true
            } else {
                isUnlocked
            }
        }

        /**
         * Returns a PIN code from the given list. It's assumed the given list elements are all
         * [Int] in the range [0-9].
         */
        private fun List<Any>.asCode(): List<Int>? {
            if (isEmpty() || size > DevicePolicyManager.MAX_PASSWORD_LENGTH) {
                return null
            }

            return map {
                require(it is Int && it in 0..9) {
                    "Pin is required to be Int in range [0..9], but got $it"
                }
                it
            }
        }

        /**
         * Returns a password from the given list. It's assumed the given list elements are all
         * [Char].
         */
        private fun List<Any>.asPassword(): String {
            val anyList = this
            return buildString { anyList.forEach { append(it as Char) } }
        }

        /**
         * Returns a list of [AuthenticationMethodModel.Pattern.PatternCoordinate] from the given
         * list. It's assumed the given list elements are all
         * [AuthenticationMethodModel.Pattern.PatternCoordinate].
         */
        private fun List<Any>.asPattern():
            List<AuthenticationMethodModel.Pattern.PatternCoordinate> {
            val anyList = this
            return buildList {
                anyList.forEach { add(it as AuthenticationMethodModel.Pattern.PatternCoordinate) }
            }
        }
    }
}
