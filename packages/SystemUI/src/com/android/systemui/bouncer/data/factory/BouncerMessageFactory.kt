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

package com.android.systemui.bouncer.data.factory

import android.annotation.IntDef
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_AFTER_LOCKOUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEFAULT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_FACE_LOCKED_OUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_FINGERPRINT_LOCKED_OUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_FACE_INPUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_FINGERPRINT_INPUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NONE
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_PREPARE_FOR_UPDATE
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_PRIMARY_AUTH_LOCKED_OUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TIMEOUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TRUSTAGENT_EXPIRED
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_USER_REQUEST
import com.android.systemui.R.string.bouncer_face_not_recognized
import com.android.systemui.R.string.keyguard_enter_password
import com.android.systemui.R.string.keyguard_enter_pattern
import com.android.systemui.R.string.keyguard_enter_pin
import com.android.systemui.R.string.kg_bio_too_many_attempts_password
import com.android.systemui.R.string.kg_bio_too_many_attempts_pattern
import com.android.systemui.R.string.kg_bio_too_many_attempts_pin
import com.android.systemui.R.string.kg_bio_try_again_or_password
import com.android.systemui.R.string.kg_bio_try_again_or_pattern
import com.android.systemui.R.string.kg_bio_try_again_or_pin
import com.android.systemui.R.string.kg_face_locked_out
import com.android.systemui.R.string.kg_fp_locked_out
import com.android.systemui.R.string.kg_fp_not_recognized
import com.android.systemui.R.string.kg_primary_auth_locked_out_password
import com.android.systemui.R.string.kg_primary_auth_locked_out_pattern
import com.android.systemui.R.string.kg_primary_auth_locked_out_pin
import com.android.systemui.R.string.kg_prompt_after_dpm_lock
import com.android.systemui.R.string.kg_prompt_after_update_password
import com.android.systemui.R.string.kg_prompt_after_update_pattern
import com.android.systemui.R.string.kg_prompt_after_update_pin
import com.android.systemui.R.string.kg_prompt_after_user_lockdown_password
import com.android.systemui.R.string.kg_prompt_after_user_lockdown_pattern
import com.android.systemui.R.string.kg_prompt_after_user_lockdown_pin
import com.android.systemui.R.string.kg_prompt_auth_timeout
import com.android.systemui.R.string.kg_prompt_password_auth_timeout
import com.android.systemui.R.string.kg_prompt_pattern_auth_timeout
import com.android.systemui.R.string.kg_prompt_pin_auth_timeout
import com.android.systemui.R.string.kg_prompt_reason_restart_password
import com.android.systemui.R.string.kg_prompt_reason_restart_pattern
import com.android.systemui.R.string.kg_prompt_reason_restart_pin
import com.android.systemui.R.string.kg_prompt_unattended_update
import com.android.systemui.R.string.kg_too_many_failed_attempts_countdown
import com.android.systemui.R.string.kg_trust_agent_disabled
import com.android.systemui.R.string.kg_unlock_with_password_or_fp
import com.android.systemui.R.string.kg_unlock_with_pattern_or_fp
import com.android.systemui.R.string.kg_unlock_with_pin_or_fp
import com.android.systemui.R.string.kg_wrong_input_try_fp_suggestion
import com.android.systemui.R.string.kg_wrong_password_try_again
import com.android.systemui.R.string.kg_wrong_pattern_try_again
import com.android.systemui.R.string.kg_wrong_pin_try_again
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.bouncer.shared.model.Message
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import javax.inject.Inject

@SysUISingleton
class BouncerMessageFactory
@Inject
constructor(
    private val biometricSettingsRepository: BiometricSettingsRepository,
    private val securityModel: KeyguardSecurityModel,
) {

    fun createFromPromptReason(
        @BouncerPromptReason reason: Int,
        userId: Int,
        secondaryMsgOverride: String? = null
    ): BouncerMessageModel? {
        val pair =
            getBouncerMessage(
                reason,
                securityModel.getSecurityMode(userId),
                biometricSettingsRepository.isFingerprintAuthCurrentlyAllowed.value
            )
        return pair?.let {
            BouncerMessageModel(
                message = Message(messageResId = pair.first, animate = false),
                secondaryMessage =
                    secondaryMsgOverride?.let {
                        Message(message = secondaryMsgOverride, animate = false)
                    }
                        ?: Message(messageResId = pair.second, animate = false)
            )
        }
    }

    /**
     * Helper method that provides the relevant bouncer message that should be shown for different
     * scenarios indicated by [reason]. [securityMode] & [fpAuthIsAllowed] parameters are used to
     * provide a more specific message.
     */
    private fun getBouncerMessage(
        @BouncerPromptReason reason: Int,
        securityMode: SecurityMode,
        fpAuthIsAllowed: Boolean = false
    ): Pair<Int, Int>? {
        return when (reason) {
            // Primary auth locked out
            PROMPT_REASON_PRIMARY_AUTH_LOCKED_OUT -> primaryAuthLockedOut(securityMode)
            // Primary auth required reasons
            PROMPT_REASON_RESTART -> authRequiredAfterReboot(securityMode)
            PROMPT_REASON_TIMEOUT -> authRequiredAfterPrimaryAuthTimeout(securityMode)
            PROMPT_REASON_DEVICE_ADMIN -> authRequiredAfterAdminLockdown(securityMode)
            PROMPT_REASON_USER_REQUEST -> authRequiredAfterUserLockdown(securityMode)
            PROMPT_REASON_PREPARE_FOR_UPDATE -> authRequiredForUnattendedUpdate(securityMode)
            PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE -> authRequiredForMainlineUpdate(securityMode)
            PROMPT_REASON_FINGERPRINT_LOCKED_OUT -> fingerprintUnlockUnavailable(securityMode)
            PROMPT_REASON_AFTER_LOCKOUT -> biometricLockout(securityMode)
            // Non strong auth not available reasons
            PROMPT_REASON_FACE_LOCKED_OUT ->
                if (fpAuthIsAllowed) faceLockedOutButFingerprintAvailable(securityMode)
                else faceLockedOut(securityMode)
            PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT ->
                if (fpAuthIsAllowed) nonStrongAuthTimeoutWithFingerprintAllowed(securityMode)
                else nonStrongAuthTimeout(securityMode)
            PROMPT_REASON_TRUSTAGENT_EXPIRED ->
                if (fpAuthIsAllowed) trustAgentDisabledWithFingerprintAllowed(securityMode)
                else trustAgentDisabled(securityMode)
            // Auth incorrect input reasons.
            PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT ->
                if (fpAuthIsAllowed) incorrectSecurityInputWithFingerprint(securityMode)
                else incorrectSecurityInput(securityMode)
            PROMPT_REASON_INCORRECT_FACE_INPUT ->
                if (fpAuthIsAllowed) incorrectFaceInputWithFingerprintAllowed(securityMode)
                else incorrectFaceInput(securityMode)
            PROMPT_REASON_INCORRECT_FINGERPRINT_INPUT -> incorrectFingerprintInput(securityMode)
            // Default message
            PROMPT_REASON_DEFAULT ->
                if (fpAuthIsAllowed) defaultMessageWithFingerprint(securityMode)
                else defaultMessage(securityMode)
            else -> null
        }
    }

    fun emptyMessage(): BouncerMessageModel =
        BouncerMessageModel(Message(message = ""), Message(message = ""))
}

@Retention(AnnotationRetention.SOURCE)
@IntDef(
    PROMPT_REASON_TIMEOUT,
    PROMPT_REASON_DEVICE_ADMIN,
    PROMPT_REASON_USER_REQUEST,
    PROMPT_REASON_AFTER_LOCKOUT,
    PROMPT_REASON_PREPARE_FOR_UPDATE,
    PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT,
    PROMPT_REASON_TRUSTAGENT_EXPIRED,
    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
    PROMPT_REASON_INCORRECT_FACE_INPUT,
    PROMPT_REASON_INCORRECT_FINGERPRINT_INPUT,
    PROMPT_REASON_FACE_LOCKED_OUT,
    PROMPT_REASON_FINGERPRINT_LOCKED_OUT,
    PROMPT_REASON_DEFAULT,
    PROMPT_REASON_NONE,
    PROMPT_REASON_RESTART,
    PROMPT_REASON_PRIMARY_AUTH_LOCKED_OUT,
    PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE,
)
annotation class BouncerPromptReason

private fun defaultMessage(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun defaultMessageWithFingerprint(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, 0)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, 0)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectSecurityInput(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_wrong_pattern_try_again, 0)
        SecurityMode.Password -> Pair(kg_wrong_password_try_again, 0)
        SecurityMode.PIN -> Pair(kg_wrong_pin_try_again, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectSecurityInputWithFingerprint(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_wrong_pattern_try_again, kg_wrong_input_try_fp_suggestion)
        SecurityMode.Password -> Pair(kg_wrong_password_try_again, kg_wrong_input_try_fp_suggestion)
        SecurityMode.PIN -> Pair(kg_wrong_pin_try_again, kg_wrong_input_try_fp_suggestion)
        else -> Pair(0, 0)
    }
}

private fun incorrectFingerprintInput(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_fp_not_recognized, kg_bio_try_again_or_pattern)
        SecurityMode.Password -> Pair(kg_fp_not_recognized, kg_bio_try_again_or_password)
        SecurityMode.PIN -> Pair(kg_fp_not_recognized, kg_bio_try_again_or_pin)
        else -> Pair(0, 0)
    }
}

private fun incorrectFaceInput(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(bouncer_face_not_recognized, kg_bio_try_again_or_pattern)
        SecurityMode.Password -> Pair(bouncer_face_not_recognized, kg_bio_try_again_or_password)
        SecurityMode.PIN -> Pair(bouncer_face_not_recognized, kg_bio_try_again_or_pin)
        else -> Pair(0, 0)
    }
}

private fun incorrectFaceInputWithFingerprintAllowed(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, bouncer_face_not_recognized)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, bouncer_face_not_recognized)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, bouncer_face_not_recognized)
        else -> Pair(0, 0)
    }
}

private fun biometricLockout(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_bio_too_many_attempts_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_bio_too_many_attempts_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_bio_too_many_attempts_pin)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterReboot(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_reason_restart_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_reason_restart_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_reason_restart_pin)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterAdminLockdown(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_after_dpm_lock)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_after_dpm_lock)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_after_dpm_lock)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterUserLockdown(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_after_user_lockdown_pattern)
        SecurityMode.Password ->
            Pair(keyguard_enter_password, kg_prompt_after_user_lockdown_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_after_user_lockdown_pin)
        else -> Pair(0, 0)
    }
}

private fun authRequiredForUnattendedUpdate(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_unattended_update)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_unattended_update)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_unattended_update)
        else -> Pair(0, 0)
    }
}

private fun authRequiredForMainlineUpdate(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_after_update_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_after_update_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_after_update_pin)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterPrimaryAuthTimeout(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_pattern_auth_timeout)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_password_auth_timeout)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_pin_auth_timeout)
        else -> Pair(0, 0)
    }
}

private fun nonStrongAuthTimeout(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_auth_timeout)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_auth_timeout)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_auth_timeout)
        else -> Pair(0, 0)
    }
}

private fun nonStrongAuthTimeoutWithFingerprintAllowed(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, kg_prompt_auth_timeout)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, kg_prompt_auth_timeout)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, kg_prompt_auth_timeout)
        else -> Pair(0, 0)
    }
}

private fun faceLockedOut(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_face_locked_out)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_face_locked_out)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_face_locked_out)
        else -> Pair(0, 0)
    }
}

private fun faceLockedOutButFingerprintAvailable(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, kg_face_locked_out)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, kg_face_locked_out)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, kg_face_locked_out)
        else -> Pair(0, 0)
    }
}

private fun fingerprintUnlockUnavailable(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_fp_locked_out)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_fp_locked_out)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_fp_locked_out)
        else -> Pair(0, 0)
    }
}

private fun trustAgentDisabled(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_trust_agent_disabled)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_trust_agent_disabled)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_trust_agent_disabled)
        else -> Pair(0, 0)
    }
}

private fun trustAgentDisabledWithFingerprintAllowed(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, kg_trust_agent_disabled)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, kg_trust_agent_disabled)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, kg_trust_agent_disabled)
        else -> Pair(0, 0)
    }
}

private fun primaryAuthLockedOut(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern ->
            Pair(kg_too_many_failed_attempts_countdown, kg_primary_auth_locked_out_pattern)
        SecurityMode.Password ->
            Pair(kg_too_many_failed_attempts_countdown, kg_primary_auth_locked_out_password)
        SecurityMode.PIN ->
            Pair(kg_too_many_failed_attempts_countdown, kg_primary_auth_locked_out_pin)
        else -> Pair(0, 0)
    }
}
