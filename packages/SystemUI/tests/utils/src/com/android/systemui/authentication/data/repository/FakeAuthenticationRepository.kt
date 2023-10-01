/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.authentication.data.repository

import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockscreenCredential
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.systemui.authentication.data.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.authentication.shared.model.AuthenticationResultModel
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

class FakeAuthenticationRepository(
    private val currentTime: () -> Long,
) : AuthenticationRepository {

    private val _isAutoConfirmFeatureEnabled = MutableStateFlow(false)
    override val isAutoConfirmFeatureEnabled: StateFlow<Boolean> =
        _isAutoConfirmFeatureEnabled.asStateFlow()
    override val authenticationChallengeResult = MutableSharedFlow<Boolean>()

    override val hintedPinLength: Int = HINTING_PIN_LENGTH

    private val _isPatternVisible = MutableStateFlow(true)
    override val isPatternVisible: StateFlow<Boolean> = _isPatternVisible.asStateFlow()

    private val _throttling = MutableStateFlow(AuthenticationThrottlingModel())
    override val throttling: StateFlow<AuthenticationThrottlingModel> = _throttling.asStateFlow()

    private val _authenticationMethod =
        MutableStateFlow<AuthenticationMethodModel>(DEFAULT_AUTHENTICATION_METHOD)
    override val authenticationMethod: StateFlow<AuthenticationMethodModel> =
        _authenticationMethod.asStateFlow()

    override val minPatternLength: Int = 4

    private var failedAttemptCount = 0
    private var throttlingEndTimestamp = 0L
    private var credentialOverride: List<Any>? = null
    private var securityMode: SecurityMode = DEFAULT_AUTHENTICATION_METHOD.toSecurityMode()

    override suspend fun getAuthenticationMethod(): AuthenticationMethodModel {
        return authenticationMethod.value
    }

    fun setAuthenticationMethod(authenticationMethod: AuthenticationMethodModel) {
        _authenticationMethod.value = authenticationMethod
        securityMode = authenticationMethod.toSecurityMode()
    }

    fun overrideCredential(pin: List<Int>) {
        credentialOverride = pin
    }

    override suspend fun reportAuthenticationAttempt(isSuccessful: Boolean) {
        failedAttemptCount = if (isSuccessful) 0 else failedAttemptCount + 1
        authenticationChallengeResult.emit(isSuccessful)
    }

    override suspend fun getPinLength(): Int {
        return (credentialOverride ?: DEFAULT_PIN).size
    }

    override suspend fun getFailedAuthenticationAttemptCount(): Int {
        return failedAttemptCount
    }

    override suspend fun getThrottlingEndTimestamp(): Long {
        return throttlingEndTimestamp
    }

    override fun setThrottling(throttlingModel: AuthenticationThrottlingModel) {
        _throttling.value = throttlingModel
    }

    fun setAutoConfirmFeatureEnabled(isEnabled: Boolean) {
        _isAutoConfirmFeatureEnabled.value = isEnabled
    }

    override suspend fun setThrottleDuration(durationMs: Int) {
        throttlingEndTimestamp = if (durationMs > 0) currentTime() + durationMs else 0
    }

    override suspend fun checkCredential(
        credential: LockscreenCredential
    ): AuthenticationResultModel {
        val expectedCredential = credentialOverride ?: getExpectedCredential(securityMode)
        val isSuccessful =
            when {
                credential.type != getCurrentCredentialType(securityMode) -> false
                credential.type == LockPatternUtils.CREDENTIAL_TYPE_PIN ->
                    credential.isPin && credential.matches(expectedCredential)
                credential.type == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ->
                    credential.isPassword && credential.matches(expectedCredential)
                credential.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN ->
                    credential.isPattern && credential.matches(expectedCredential)
                else -> error("Unexpected credential type ${credential.type}!")
            }

        return if (
            isSuccessful || failedAttemptCount < MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING - 1
        ) {
            AuthenticationResultModel(
                isSuccessful = isSuccessful,
                throttleDurationMs = 0,
            )
        } else {
            AuthenticationResultModel(
                isSuccessful = false,
                throttleDurationMs = THROTTLE_DURATION_MS,
            )
        }
    }

    private fun getExpectedCredential(securityMode: SecurityMode): List<Any> {
        return when (val credentialType = getCurrentCredentialType(securityMode)) {
            LockPatternUtils.CREDENTIAL_TYPE_PIN -> credentialOverride ?: DEFAULT_PIN
            LockPatternUtils.CREDENTIAL_TYPE_PASSWORD -> "password".toList()
            LockPatternUtils.CREDENTIAL_TYPE_PATTERN -> PATTERN.toCells()
            else -> error("Unsupported credential type $credentialType!")
        }
    }

    companion object {
        val DEFAULT_AUTHENTICATION_METHOD = AuthenticationMethodModel.Pin
        val PATTERN =
            listOf(
                AuthenticationPatternCoordinate(2, 0),
                AuthenticationPatternCoordinate(2, 1),
                AuthenticationPatternCoordinate(2, 2),
                AuthenticationPatternCoordinate(1, 1),
                AuthenticationPatternCoordinate(0, 0),
                AuthenticationPatternCoordinate(0, 1),
                AuthenticationPatternCoordinate(0, 2),
            )
        const val MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING = 5
        const val THROTTLE_DURATION_MS = 30000
        const val HINTING_PIN_LENGTH = 6
        val DEFAULT_PIN = buildList { repeat(HINTING_PIN_LENGTH) { add(it + 1) } }

        private fun AuthenticationMethodModel.toSecurityMode(): SecurityMode {
            return when (this) {
                is AuthenticationMethodModel.Pin -> SecurityMode.PIN
                is AuthenticationMethodModel.Password -> SecurityMode.Password
                is AuthenticationMethodModel.Pattern -> SecurityMode.Pattern
                is AuthenticationMethodModel.None -> SecurityMode.None
            }
        }

        @LockPatternUtils.CredentialType
        private fun getCurrentCredentialType(
            securityMode: SecurityMode,
        ): Int {
            return when (securityMode) {
                SecurityMode.PIN,
                SecurityMode.SimPin,
                SecurityMode.SimPuk -> LockPatternUtils.CREDENTIAL_TYPE_PIN
                SecurityMode.Password -> LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                SecurityMode.Pattern -> LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                SecurityMode.None -> LockPatternUtils.CREDENTIAL_TYPE_NONE
                else -> error("Unsupported SecurityMode $securityMode!")
            }
        }

        private fun LockscreenCredential.matches(expectedCredential: List<Any>): Boolean {
            @Suppress("UNCHECKED_CAST")
            return when {
                isPin ->
                    credential.map { byte -> byte.toInt().toChar() - '0' } == expectedCredential
                isPassword -> credential.map { byte -> byte.toInt().toChar() } == expectedCredential
                isPattern ->
                    credential.contentEquals(
                        LockPatternUtils.patternToByteArray(
                            expectedCredential as List<LockPatternView.Cell>
                        )
                    )
                else -> error("Unsupported credential type $type!")
            }
        }

        private fun List<AuthenticationPatternCoordinate>.toCells(): List<LockPatternView.Cell> {
            return map { coordinate -> LockPatternView.Cell.of(coordinate.y, coordinate.x) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Module(includes = [FakeAuthenticationRepositoryModule.Bindings::class])
object FakeAuthenticationRepositoryModule {
    @Provides
    @SysUISingleton
    fun provideFake(
        scope: TestScope,
    ) = FakeAuthenticationRepository(currentTime = { scope.currentTime })

    @Module
    interface Bindings {
        @Binds fun bindFake(fake: FakeAuthenticationRepository): AuthenticationRepository
    }
}
