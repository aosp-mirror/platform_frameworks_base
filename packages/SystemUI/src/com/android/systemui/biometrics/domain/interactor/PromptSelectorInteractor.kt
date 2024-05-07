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

import android.hardware.biometrics.Flags
import android.hardware.biometrics.PromptInfo
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.Utils.getCredentialType
import com.android.systemui.biometrics.Utils.isDeviceCredentialAllowed
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.PromptRepository
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Business logic for BiometricPrompt's biometric view variants (face, fingerprint, coex, etc.).
 *
 * This is used to cache the calling app's options that were given to the underlying authenticate
 * APIs and should be set before any UI is shown to the user.
 *
 * There can be at most one request active at a given time. Use [resetPrompt] when no request is
 * active to clear the cache.
 *
 * Views that use credential fallback should use [PromptCredentialInteractor] instead.
 */
interface PromptSelectorInteractor {

    /** Static metadata about the current prompt. */
    val prompt: Flow<BiometricPromptRequest.Biometric?>

    /** The kind of prompt to use (biometric, pin, pattern, etc.). */
    val promptKind: StateFlow<PromptKind>

    /** If using a credential is allowed. */
    val isCredentialAllowed: Flow<Boolean>

    /**
     * The kind of credential the user may use as a fallback or [PromptKind.None] if unknown or not
     * [isCredentialAllowed]. This is separate from [promptKind], even if [promptKind] is
     * [PromptKind.Biometric], [credentialKind] should still be one of pin/pattern/password.
     */
    val credentialKind: Flow<PromptKind>

    /**
     * If the API caller or the user's personal preferences require explicit confirmation after
     * successful authentication.
     */
    val isConfirmationRequired: Flow<Boolean>

    /** Fingerprint sensor type */
    val sensorType: Flow<FingerprintSensorType>

    /** Switch to the credential view. */
    fun onSwitchToCredential()

    /**
     * Update the kind of prompt (biometric prompt w/ or w/o sensor icon, pin view, pattern view,
     * etc).
     */
    fun setPrompt(
        promptInfo: PromptInfo,
        effectiveUserId: Int,
        modalities: BiometricModalities,
        challenge: Long,
        opPackageName: String,
        onSwitchToCredential: Boolean,
    )

    /** Unset the current authentication request. */
    fun resetPrompt()
}

@SysUISingleton
class PromptSelectorInteractorImpl
@Inject
constructor(
    fingerprintPropertyRepository: FingerprintPropertyRepository,
    private val promptRepository: PromptRepository,
    private val lockPatternUtils: LockPatternUtils,
) : PromptSelectorInteractor {

    override val prompt: Flow<BiometricPromptRequest.Biometric?> =
        combine(
            promptRepository.promptInfo,
            promptRepository.challenge,
            promptRepository.userId,
            promptRepository.promptKind,
            promptRepository.opPackageName,
        ) { promptInfo, challenge, userId, kind, opPackageName ->
            if (
                promptInfo == null || userId == null || challenge == null || opPackageName == null
            ) {
                return@combine null
            }

            when (kind) {
                is PromptKind.Biometric ->
                    BiometricPromptRequest.Biometric(
                        info = promptInfo,
                        userInfo = BiometricUserInfo(userId = userId),
                        operationInfo = BiometricOperationInfo(gatekeeperChallenge = challenge),
                        modalities = kind.activeModalities,
                        opPackageName = opPackageName,
                    )
                else -> null
            }
        }

    override val promptKind: StateFlow<PromptKind> = promptRepository.promptKind

    override val isConfirmationRequired: Flow<Boolean> =
        promptRepository.isConfirmationRequired.distinctUntilChanged()

    override val isCredentialAllowed: Flow<Boolean> =
        promptRepository.promptInfo
            .map { info -> if (info != null) isDeviceCredentialAllowed(info) else false }
            .distinctUntilChanged()

    override val credentialKind: Flow<PromptKind> =
        combine(prompt, isCredentialAllowed) { prompt, isAllowed ->
            if (prompt != null && isAllowed) {
                getCredentialType(lockPatternUtils, prompt.userInfo.deviceCredentialOwnerId)
            } else {
                PromptKind.None
            }
        }

    override val sensorType: Flow<FingerprintSensorType> = fingerprintPropertyRepository.sensorType

    override fun onSwitchToCredential() {
        val modalities: BiometricModalities =
            if (promptRepository.promptKind.value.isBiometric())
                (promptRepository.promptKind.value as PromptKind.Biometric).activeModalities
            else BiometricModalities()
        setPrompt(
            promptRepository.promptInfo.value!!,
            promptRepository.userId.value!!,
            modalities,
            promptRepository.challenge.value!!,
            promptRepository.opPackageName.value!!,
            true /*onSwitchToCredential*/
        )
    }

    override fun setPrompt(
        promptInfo: PromptInfo,
        effectiveUserId: Int,
        modalities: BiometricModalities,
        challenge: Long,
        opPackageName: String,
        onSwitchToCredential: Boolean,
    ) {
        val hasCredentialViewShown = promptKind.value.isCredential()
        val showBpForCredential =
            Flags.customBiometricPrompt() &&
                com.android.systemui.Flags.constraintBp() &&
                !Utils.isBiometricAllowed(promptInfo) &&
                isDeviceCredentialAllowed(promptInfo) &&
                promptInfo.contentView != null &&
                !promptInfo.isContentViewMoreOptionsButtonUsed
        val showBpWithoutIconForCredential = showBpForCredential && !hasCredentialViewShown
        var kind: PromptKind = PromptKind.None
        if (onSwitchToCredential) {
            kind = getCredentialType(lockPatternUtils, effectiveUserId)
        } else if (Utils.isBiometricAllowed(promptInfo) || showBpWithoutIconForCredential) {
            // TODO(b/330908557): check to show one pane or two pane
            kind = PromptKind.Biometric(modalities)
        } else if (isDeviceCredentialAllowed(promptInfo)) {
            kind = getCredentialType(lockPatternUtils, effectiveUserId)
        }

        promptRepository.setPrompt(
            promptInfo = promptInfo,
            userId = effectiveUserId,
            gatekeeperChallenge = challenge,
            kind = kind,
            opPackageName = opPackageName,
        )
    }

    override fun resetPrompt() {
        promptRepository.unsetPrompt()
    }
}
