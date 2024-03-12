/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bouncer.shared.model

import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Password
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pattern
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pin
import com.android.systemui.res.R

typealias BouncerMessagePair = Pair<Int, Int>

val BouncerMessagePair.primaryMessage: Int
    get() = this.first

val BouncerMessagePair.secondaryMessage: Int
    get() = this.second

object BouncerMessageStrings {
    private val EmptyMessage = Pair(0, 0)

    fun defaultMessage(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(fpAuthIsAllowed), 0)
            Password -> Pair(passwordDefaultMessage(fpAuthIsAllowed), 0)
            Pin -> Pair(pinDefaultMessage(fpAuthIsAllowed), 0)
            else -> EmptyMessage
        }
    }

    fun incorrectSecurityInput(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        val secondaryMessage = incorrectSecurityInputSecondaryMessage(fpAuthIsAllowed)
        return when (securityMode) {
            Pattern -> Pair(R.string.kg_wrong_pattern_try_again, secondaryMessage)
            Password -> Pair(R.string.kg_wrong_password_try_again, secondaryMessage)
            Pin -> Pair(R.string.kg_wrong_pin_try_again, secondaryMessage)
            else -> EmptyMessage
        }
    }

    private fun incorrectSecurityInputSecondaryMessage(fpAuthIsAllowed: Boolean): Int {
        return if (fpAuthIsAllowed) R.string.kg_wrong_input_try_fp_suggestion else 0
    }

    fun incorrectFingerprintInput(securityMode: AuthenticationMethodModel): BouncerMessagePair {
        val primaryMessage = R.string.kg_fp_not_recognized
        return when (securityMode) {
            Pattern -> Pair(primaryMessage, R.string.kg_bio_try_again_or_pattern)
            Password -> Pair(primaryMessage, R.string.kg_bio_try_again_or_password)
            Pin -> Pair(primaryMessage, R.string.kg_bio_try_again_or_pin)
            else -> EmptyMessage
        }
    }

    fun incorrectFaceInput(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        return if (fpAuthIsAllowed) incorrectFaceInputWithFingerprintAllowed(securityMode)
        else {
            val primaryMessage = R.string.bouncer_face_not_recognized
            when (securityMode) {
                Pattern -> Pair(primaryMessage, R.string.kg_bio_try_again_or_pattern)
                Password -> Pair(primaryMessage, R.string.kg_bio_try_again_or_password)
                Pin -> Pair(primaryMessage, R.string.kg_bio_try_again_or_pin)
                else -> EmptyMessage
            }
        }
    }

    private fun incorrectFaceInputWithFingerprintAllowed(
        securityMode: AuthenticationMethodModel
    ): BouncerMessagePair {
        val secondaryMsg = R.string.bouncer_face_not_recognized
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(true), secondaryMsg)
            Password -> Pair(passwordDefaultMessage(true), secondaryMsg)
            Pin -> Pair(pinDefaultMessage(true), secondaryMsg)
            else -> EmptyMessage
        }
    }

    fun authRequiredAfterReboot(securityMode: AuthenticationMethodModel): BouncerMessagePair {
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(false), R.string.kg_prompt_reason_restart_pattern)
            Password ->
                Pair(passwordDefaultMessage(false), R.string.kg_prompt_reason_restart_password)
            Pin -> Pair(pinDefaultMessage(false), R.string.kg_prompt_reason_restart_pin)
            else -> EmptyMessage
        }
    }

    fun authRequiredAfterAdminLockdown(
        securityMode: AuthenticationMethodModel
    ): BouncerMessagePair {
        val secondaryMsg = R.string.kg_prompt_after_dpm_lock
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(false), secondaryMsg)
            Password -> Pair(passwordDefaultMessage(false), secondaryMsg)
            Pin -> Pair(pinDefaultMessage(false), secondaryMsg)
            else -> EmptyMessage
        }
    }

    fun authRequiredAfterAdaptiveAuthRequest(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        val secondaryMsg = R.string.kg_prompt_after_adaptive_auth_lock
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Password -> Pair(passwordDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Pin -> Pair(pinDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            else -> EmptyMessage
        }
    }

    fun authRequiredAfterUserLockdown(securityMode: AuthenticationMethodModel): BouncerMessagePair {
        return when (securityMode) {
            Pattern ->
                Pair(patternDefaultMessage(false), R.string.kg_prompt_after_user_lockdown_pattern)
            Password ->
                Pair(passwordDefaultMessage(false), R.string.kg_prompt_after_user_lockdown_password)
            Pin -> Pair(pinDefaultMessage(false), R.string.kg_prompt_after_user_lockdown_pin)
            else -> EmptyMessage
        }
    }

    fun authRequiredForUnattendedUpdate(
        securityMode: AuthenticationMethodModel
    ): BouncerMessagePair {
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(false), R.string.kg_prompt_added_security_pattern)
            Password ->
                Pair(passwordDefaultMessage(false), R.string.kg_prompt_added_security_password)
            Pin -> Pair(pinDefaultMessage(false), R.string.kg_prompt_added_security_pin)
            else -> EmptyMessage
        }
    }

    fun authRequiredForMainlineUpdate(securityMode: AuthenticationMethodModel): BouncerMessagePair {
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(false), R.string.kg_prompt_after_update_pattern)
            Password ->
                Pair(passwordDefaultMessage(false), R.string.kg_prompt_after_update_password)
            Pin -> Pair(pinDefaultMessage(false), R.string.kg_prompt_after_update_pin)
            else -> EmptyMessage
        }
    }

    fun authRequiredAfterPrimaryAuthTimeout(
        securityMode: AuthenticationMethodModel
    ): BouncerMessagePair {
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(false), R.string.kg_prompt_pattern_auth_timeout)
            Password ->
                Pair(passwordDefaultMessage(false), R.string.kg_prompt_password_auth_timeout)
            Pin -> Pair(pinDefaultMessage(false), R.string.kg_prompt_pin_auth_timeout)
            else -> EmptyMessage
        }
    }

    fun nonStrongAuthTimeout(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        val secondaryMsg = R.string.kg_prompt_auth_timeout
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Password -> Pair(passwordDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Pin -> Pair(pinDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            else -> EmptyMessage
        }
    }

    fun faceLockedOut(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        val secondaryMsg = R.string.kg_face_locked_out
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Password -> Pair(passwordDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Pin -> Pair(pinDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            else -> EmptyMessage
        }
    }

    fun class3AuthLockedOut(securityMode: AuthenticationMethodModel): BouncerMessagePair {
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(false), R.string.kg_bio_too_many_attempts_pattern)
            Password ->
                Pair(passwordDefaultMessage(false), R.string.kg_bio_too_many_attempts_password)
            Pin -> Pair(pinDefaultMessage(false), R.string.kg_bio_too_many_attempts_pin)
            else -> EmptyMessage
        }
    }

    fun trustAgentDisabled(
        securityMode: AuthenticationMethodModel,
        fpAuthIsAllowed: Boolean
    ): BouncerMessagePair {
        val secondaryMsg = R.string.kg_trust_agent_disabled
        return when (securityMode) {
            Pattern -> Pair(patternDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Password -> Pair(passwordDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            Pin -> Pair(pinDefaultMessage(fpAuthIsAllowed), secondaryMsg)
            else -> EmptyMessage
        }
    }

    fun primaryAuthLockedOut(securityMode: AuthenticationMethodModel): BouncerMessagePair {
        return when (securityMode) {
            Pattern ->
                Pair(
                    R.string.kg_too_many_failed_attempts_countdown,
                    R.string.kg_primary_auth_locked_out_pattern
                )
            Password ->
                Pair(
                    R.string.kg_too_many_failed_attempts_countdown,
                    R.string.kg_primary_auth_locked_out_password
                )
            Pin ->
                Pair(
                    R.string.kg_too_many_failed_attempts_countdown,
                    R.string.kg_primary_auth_locked_out_pin
                )
            else -> EmptyMessage
        }
    }

    private fun patternDefaultMessage(fingerprintAllowed: Boolean): Int {
        return if (fingerprintAllowed) R.string.kg_unlock_with_pattern_or_fp
        else R.string.keyguard_enter_pattern
    }

    private fun pinDefaultMessage(fingerprintAllowed: Boolean): Int {
        return if (fingerprintAllowed) R.string.kg_unlock_with_pin_or_fp
        else R.string.keyguard_enter_pin
    }

    private fun passwordDefaultMessage(fingerprintAllowed: Boolean): Int {
        return if (fingerprintAllowed) R.string.kg_unlock_with_password_or_fp
        else R.string.keyguard_enter_password
    }
}
