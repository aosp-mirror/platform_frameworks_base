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

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.PromptInfo
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockscreenCredential
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.data.repository.PromptRepository
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Business logic for BiometricPrompt's CredentialViews, which primarily includes checking a users
 * PIN, pattern, or password credential instead of a biometric.
 *
 * This is used to cache the calling app's options that were given to the underlying authenticate
 * APIs and should be set before any UI is shown to the user.
 *
 * There can be at most one request active at a given time. Use [resetPrompt] when no request is
 * active to clear the cache.
 *
 * Views that use any biometric should use [PromptSelectorInteractor] instead.
 */
class PromptCredentialInteractor
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val biometricPromptRepository: PromptRepository,
    private val credentialInteractor: CredentialInteractor,
) {
    /** If the prompt is currently showing. */
    val isShowing: Flow<Boolean> = biometricPromptRepository.isShowing

    /** Metadata about the current credential prompt, including app-supplied preferences. */
    val prompt: Flow<BiometricPromptRequest.Credential?> =
        combine(
                biometricPromptRepository.promptInfo,
                biometricPromptRepository.challenge,
                biometricPromptRepository.userId,
                biometricPromptRepository.kind
            ) { promptInfo, challenge, userId, kind ->
                if (promptInfo == null || userId == null || challenge == null) {
                    return@combine null
                }

                when (kind) {
                    PromptKind.Pin ->
                        BiometricPromptRequest.Credential.Pin(
                            info = promptInfo,
                            userInfo = userInfo(userId),
                            operationInfo = operationInfo(challenge)
                        )
                    PromptKind.Pattern ->
                        BiometricPromptRequest.Credential.Pattern(
                            info = promptInfo,
                            userInfo = userInfo(userId),
                            operationInfo = operationInfo(challenge),
                            stealthMode = credentialInteractor.isStealthModeActive(userId)
                        )
                    PromptKind.Password ->
                        BiometricPromptRequest.Credential.Password(
                            info = promptInfo,
                            userInfo = userInfo(userId),
                            operationInfo = operationInfo(challenge)
                        )
                    else -> null
                }
            }
            .distinctUntilChanged()

    private fun userInfo(userId: Int): BiometricUserInfo =
        BiometricUserInfo(
            userId = userId,
            deviceCredentialOwnerId = credentialInteractor.getCredentialOwnerOrSelfId(userId)
        )

    private fun operationInfo(challenge: Long): BiometricOperationInfo =
        BiometricOperationInfo(gatekeeperChallenge = challenge)

    /** Most recent error due to [verifyCredential]. */
    private val _verificationError = MutableStateFlow<CredentialStatus.Fail?>(null)
    val verificationError: Flow<CredentialStatus.Fail?> = _verificationError.asStateFlow()

    /** Update the current request to use credential-based authentication instead of biometrics. */
    fun useCredentialsForAuthentication(
        promptInfo: PromptInfo,
        @Utils.CredentialType kind: Int,
        userId: Int,
        challenge: Long,
        opPackageName: String,
    ) {
        biometricPromptRepository.setPrompt(
            promptInfo,
            userId,
            challenge,
            kind.asBiometricPromptCredential(),
            opPackageName,
        )
    }

    /** Unset the current authentication request. */
    fun resetPrompt() {
        biometricPromptRepository.unsetPrompt()
    }

    /**
     * Check a credential and return the attestation token (HAT) if successful.
     *
     * This method will not return if credential checks are being throttled until the throttling has
     * expired and the user can try again. It will periodically update the [verificationError] until
     * cancelled or the throttling has completed. If the request is not throttled, but unsuccessful,
     * the [verificationError] will be set and an optional
     * [CredentialStatus.Fail.Error.urgentMessage] message may be provided to indicate additional
     * hints to the user (i.e. device will be wiped on next failure, etc.).
     *
     * The check happens on the background dispatcher given in the constructor.
     */
    suspend fun checkCredential(
        request: BiometricPromptRequest.Credential,
        text: CharSequence? = null,
        pattern: List<LockPatternView.Cell>? = null,
    ): CredentialStatus =
        withContext(bgDispatcher) {
            val credential =
                when (request) {
                    is BiometricPromptRequest.Credential.Pin ->
                        LockscreenCredential.createPinOrNone(text ?: "")
                    is BiometricPromptRequest.Credential.Password ->
                        LockscreenCredential.createPasswordOrNone(text ?: "")
                    is BiometricPromptRequest.Credential.Pattern ->
                        LockscreenCredential.createPattern(pattern ?: listOf())
                }

            credential.use { c -> verifyCredential(request, c) }
        }

    private suspend fun verifyCredential(
        request: BiometricPromptRequest.Credential,
        credential: LockscreenCredential?
    ): CredentialStatus {
        if (credential == null || credential.isNone) {
            return CredentialStatus.Fail.Error()
        }

        val finalStatus =
            credentialInteractor
                .verifyCredential(request, credential)
                .onEach { status ->
                    when (status) {
                        is CredentialStatus.Success -> _verificationError.value = null
                        is CredentialStatus.Fail -> _verificationError.value = status
                    }
                }
                .lastOrNull()

        return finalStatus ?: CredentialStatus.Fail.Error()
    }

    /**
     * Report a user-visible error.
     *
     * Use this instead of calling [verifyCredential] when it is not necessary because the check
     * will obviously fail (i.e. too short, empty, etc.)
     */
    fun setVerificationError(error: CredentialStatus.Fail.Error?) {
        if (error != null) {
            _verificationError.value = error
        } else {
            resetVerificationError()
        }
    }

    /** Clear the current error message, if any. */
    fun resetVerificationError() {
        _verificationError.value = null
    }
}

// TODO(b/251476085): remove along with Utils.CredentialType
/** Convert a [Utils.CredentialType] to the corresponding [PromptKind]. */
private fun @receiver:Utils.CredentialType Int.asBiometricPromptCredential(): PromptKind =
    when (this) {
        Utils.CREDENTIAL_PIN -> PromptKind.Pin
        Utils.CREDENTIAL_PASSWORD -> PromptKind.Password
        Utils.CREDENTIAL_PATTERN -> PromptKind.Pattern
        else -> PromptKind.Biometric()
    }
