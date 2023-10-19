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

package com.android.systemui.bouncer.domain.interactor

import android.os.CountDownTimer
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEFAULT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_PRIMARY_AUTH_LOCKED_OUT
import com.android.systemui.bouncer.data.factory.BouncerMessageFactory
import com.android.systemui.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.REVAMPED_BOUNCER_MESSAGES
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@SysUISingleton
class BouncerMessageInteractor
@Inject
constructor(
    private val repository: BouncerMessageRepository,
    private val factory: BouncerMessageFactory,
    private val userRepository: UserRepository,
    private val countDownTimerUtil: CountDownTimerUtil,
    private val featureFlags: FeatureFlags,
) {
    fun onPrimaryAuthLockedOut(secondsBeforeLockoutReset: Long) {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        val callback =
            object : CountDownTimerCallback {
                override fun onFinish() {
                    repository.clearMessage()
                }

                override fun onTick(millisUntilFinished: Long) {
                    val secondsRemaining = (millisUntilFinished / 1000.0).roundToInt()
                    val message =
                        factory.createFromPromptReason(
                            reason = PROMPT_REASON_PRIMARY_AUTH_LOCKED_OUT,
                            userId = userRepository.getSelectedUserInfo().id
                        )
                    message?.message?.animate = false
                    message?.message?.formatterArgs =
                        mutableMapOf<String, Any>(Pair("count", secondsRemaining))
                    repository.setPrimaryAuthMessage(message)
                }
            }
        countDownTimerUtil.startNewTimer(secondsBeforeLockoutReset * 1000, 1000, callback)
    }

    fun onPrimaryAuthIncorrectAttempt() {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        repository.setPrimaryAuthMessage(
            factory.createFromPromptReason(
                PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                userRepository.getSelectedUserInfo().id
            )
        )
    }

    fun setFingerprintAcquisitionMessage(value: String?) {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        repository.setFingerprintAcquisitionMessage(
            if (value != null) {
                factory.createFromPromptReason(
                    PROMPT_REASON_DEFAULT,
                    userRepository.getSelectedUserInfo().id,
                    secondaryMsgOverride = value
                )
            } else {
                null
            }
        )
    }

    fun setFaceAcquisitionMessage(value: String?) {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        repository.setFaceAcquisitionMessage(
            if (value != null) {
                factory.createFromPromptReason(
                    PROMPT_REASON_DEFAULT,
                    userRepository.getSelectedUserInfo().id,
                    secondaryMsgOverride = value
                )
            } else {
                null
            }
        )
    }

    fun setCustomMessage(value: String?) {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        repository.setCustomMessage(
            if (value != null) {
                factory.createFromPromptReason(
                    PROMPT_REASON_DEFAULT,
                    userRepository.getSelectedUserInfo().id,
                    secondaryMsgOverride = value
                )
            } else {
                null
            }
        )
    }

    fun onPrimaryBouncerUserInput() {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        repository.clearMessage()
    }

    fun onBouncerBeingHidden() {
        if (!featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) return

        repository.clearMessage()
    }

    private fun firstNonNullMessage(
        oneMessageModel: Flow<BouncerMessageModel?>,
        anotherMessageModel: Flow<BouncerMessageModel?>
    ): Flow<BouncerMessageModel?> {
        return oneMessageModel.combine(anotherMessageModel) { a, b -> a ?: b }
    }

    // Null if feature flag is enabled which gets ignored always or empty bouncer message model that
    // always maps to an empty string.
    private fun nullOrEmptyMessage() =
        flowOf(
            if (featureFlags.isEnabled(REVAMPED_BOUNCER_MESSAGES)) null else factory.emptyMessage()
        )

    val bouncerMessage =
        listOf(
                nullOrEmptyMessage(),
                repository.primaryAuthMessage,
                repository.biometricAuthMessage,
                repository.fingerprintAcquisitionMessage,
                repository.faceAcquisitionMessage,
                repository.customMessage,
                repository.authFlagsMessage,
                repository.biometricLockedOutMessage,
                userRepository.selectedUserInfo.map {
                    factory.createFromPromptReason(PROMPT_REASON_DEFAULT, it.id)
                },
            )
            .reduce(::firstNonNullMessage)
            .distinctUntilChanged()
}

interface CountDownTimerCallback {
    fun onFinish()
    fun onTick(millisUntilFinished: Long)
}

@SysUISingleton
open class CountDownTimerUtil @Inject constructor() {

    /**
     * Start a new count down timer that runs for [millisInFuture] with a tick every
     * [millisInterval]
     */
    fun startNewTimer(
        millisInFuture: Long,
        millisInterval: Long,
        callback: CountDownTimerCallback,
    ): CountDownTimer {
        return object : CountDownTimer(millisInFuture, millisInterval) {
                override fun onFinish() = callback.onFinish()

                override fun onTick(millisUntilFinished: Long) =
                    callback.onTick(millisUntilFinished)
            }
            .start()
    }
}
