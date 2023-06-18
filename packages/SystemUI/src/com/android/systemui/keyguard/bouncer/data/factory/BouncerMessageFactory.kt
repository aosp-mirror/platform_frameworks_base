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

package com.android.systemui.keyguard.bouncer.data.factory

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
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TIMEOUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TRUSTAGENT_EXPIRED
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_USER_REQUEST
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R.string.keyguard_enter_password
import com.android.systemui.R.string.keyguard_enter_pattern
import com.android.systemui.R.string.keyguard_enter_pin
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.keyguard.bouncer.shared.model.Message
import javax.inject.Inject

@SysUISingleton
class BouncerMessageFactory
@Inject
constructor(
    private val updateMonitor: KeyguardUpdateMonitor,
    private val securityModel: KeyguardSecurityModel,
) {

    fun createFromPromptReason(
        @BouncerPromptReason reason: Int,
        userId: Int,
    ): BouncerMessageModel? {
        val pair =
            getBouncerMessage(
                reason,
                securityModel.getSecurityMode(userId),
                updateMonitor.isFingerprintAllowedInBouncer
            )
        return pair?.let {
            BouncerMessageModel(
                message = Message(messageResId = pair.first),
                secondaryMessage = Message(messageResId = pair.second)
            )
        }
    }

    fun createFromString(
        primaryMsg: String? = null,
        secondaryMsg: String? = null
    ): BouncerMessageModel =
        BouncerMessageModel(
            message = primaryMsg?.let { Message(message = it) },
            secondaryMessage = secondaryMsg?.let { Message(message = it) },
        )

    /**
     * Helper method that provides the relevant bouncer message that should be shown for different
     * scenarios indicated by [reason]. [securityMode] & [fpAllowedInBouncer] parameters are used to
     * provide a more specific message.
     */
    private fun getBouncerMessage(
        @BouncerPromptReason reason: Int,
        securityMode: SecurityMode,
        fpAllowedInBouncer: Boolean = false
    ): Pair<Int, Int>? {
        return when (reason) {
            PROMPT_REASON_RESTART -> authRequiredAfterReboot(securityMode)
            PROMPT_REASON_TIMEOUT -> authRequiredAfterPrimaryAuthTimeout(securityMode)
            PROMPT_REASON_DEVICE_ADMIN -> authRequiredAfterAdminLockdown(securityMode)
            PROMPT_REASON_USER_REQUEST -> authRequiredAfterUserLockdown(securityMode)
            PROMPT_REASON_AFTER_LOCKOUT -> biometricLockout(securityMode)
            PROMPT_REASON_PREPARE_FOR_UPDATE -> authRequiredForUnattendedUpdate(securityMode)
            PROMPT_REASON_FINGERPRINT_LOCKED_OUT -> fingerprintUnlockUnavailable(securityMode)
            PROMPT_REASON_FACE_LOCKED_OUT -> faceUnlockUnavailable(securityMode)
            PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT ->
                if (fpAllowedInBouncer) incorrectSecurityInputWithFingerprint(securityMode)
                else incorrectSecurityInput(securityMode)
            PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT ->
                if (fpAllowedInBouncer) nonStrongAuthTimeoutWithFingerprintAllowed(securityMode)
                else nonStrongAuthTimeout(securityMode)
            PROMPT_REASON_TRUSTAGENT_EXPIRED ->
                if (fpAllowedInBouncer) trustAgentDisabledWithFingerprintAllowed(securityMode)
                else trustAgentDisabled(securityMode)
            PROMPT_REASON_INCORRECT_FACE_INPUT ->
                if (fpAllowedInBouncer) incorrectFaceInputWithFingerprintAllowed(securityMode)
                else incorrectFaceInput(securityMode)
            PROMPT_REASON_INCORRECT_FINGERPRINT_INPUT -> incorrectFingerprintInput(securityMode)
            PROMPT_REASON_DEFAULT ->
                if (fpAllowedInBouncer) defaultMessageWithFingerprint(securityMode)
                else defaultMessage(securityMode)
            PROMPT_REASON_PRIMARY_AUTH_LOCKED_OUT -> primaryAuthLockedOut(securityMode)
            else -> null
        }
    }
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
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectSecurityInput(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectSecurityInputWithFingerprint(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectFingerprintInput(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectFaceInput(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun incorrectFaceInputWithFingerprintAllowed(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun biometricLockout(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterReboot(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterAdminLockdown(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterUserLockdown(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun authRequiredForUnattendedUpdate(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun authRequiredAfterPrimaryAuthTimeout(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun nonStrongAuthTimeout(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun nonStrongAuthTimeoutWithFingerprintAllowed(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun faceUnlockUnavailable(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun fingerprintUnlockUnavailable(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun trustAgentDisabled(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun trustAgentDisabledWithFingerprintAllowed(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}

private fun primaryAuthLockedOut(securityMode: SecurityMode): Pair<Int, Int> {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
        SecurityMode.Password -> Pair(keyguard_enter_password, 0)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
        else -> Pair(0, 0)
    }
}
