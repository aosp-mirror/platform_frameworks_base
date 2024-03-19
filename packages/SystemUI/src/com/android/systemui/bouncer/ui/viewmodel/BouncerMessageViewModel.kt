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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.Context
import android.util.PluralsMessageFormatter
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.bouncer.shared.model.BouncerMessagePair
import com.android.systemui.bouncer.shared.model.BouncerMessageStrings
import com.android.systemui.bouncer.shared.model.primaryMessage
import com.android.systemui.bouncer.shared.model.secondaryMessage
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.BiometricMessageInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason
import com.android.systemui.deviceentry.shared.model.FaceFailureMessage
import com.android.systemui.deviceentry.shared.model.FaceLockoutMessage
import com.android.systemui.deviceentry.shared.model.FaceTimeoutMessage
import com.android.systemui.deviceentry.shared.model.FingerprintFailureMessage
import com.android.systemui.deviceentry.shared.model.FingerprintLockoutMessage
import com.android.systemui.res.R.string.kg_too_many_failed_attempts_countdown
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.user.ui.viewmodel.UserViewModel
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.time.SystemClock
import dagger.Module
import dagger.Provides
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Holds UI state for the 2-line status message shown on the bouncer. */
@OptIn(ExperimentalCoroutinesApi::class)
class BouncerMessageViewModel(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    private val bouncerInteractor: BouncerInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    selectedUser: Flow<UserViewModel>,
    private val clock: SystemClock,
    private val biometricMessageInteractor: BiometricMessageInteractor,
    private val faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val fingerprintInteractor: DeviceEntryFingerprintAuthInteractor,
    flags: ComposeBouncerFlags,
) {
    /**
     * A message shown when the user has attempted the wrong credential too many times and now must
     * wait a while before attempting to authenticate again.
     *
     * This is updated every second (countdown) during the lockout. When lockout is not active, this
     * is `null` and no lockout message should be shown.
     */
    private val lockoutMessage: MutableStateFlow<MessageViewModel?> = MutableStateFlow(null)

    /** Whether there is a lockout message that is available to be shown in the status message. */
    val isLockoutMessagePresent: Flow<Boolean> = lockoutMessage.map { it != null }

    /** The user-facing message to show in the bouncer. */
    val message: MutableStateFlow<MessageViewModel?> = MutableStateFlow(null)

    /** Initializes the bouncer message to default whenever it is shown. */
    fun onShown() {
        showDefaultMessage()
    }

    /** Reset the message shown on the bouncer to the default message. */
    fun showDefaultMessage() {
        resetToDefault.tryEmit(Unit)
    }

    private val resetToDefault = MutableSharedFlow<Unit>(replay = 1)

    private var lockoutCountdownJob: Job? = null

    private fun defaultBouncerMessageInitializer() {
        applicationScope.launch {
            resetToDefault.emit(Unit)
            authenticationInteractor.authenticationMethod
                .flatMapLatest { authMethod ->
                    if (authMethod == AuthenticationMethodModel.Sim) {
                        resetToDefault.map {
                            MessageViewModel(simBouncerInteractor.getDefaultMessage())
                        }
                    } else if (authMethod.isSecure) {
                        combine(
                            deviceEntryInteractor.deviceEntryRestrictionReason,
                            lockoutMessage,
                            fingerprintInteractor.isFingerprintCurrentlyAllowedOnBouncer,
                            resetToDefault,
                        ) { deviceEntryRestrictedReason, lockoutMsg, isFpAllowedInBouncer, _ ->
                            lockoutMsg
                                ?: deviceEntryRestrictedReason.toMessage(
                                    authMethod,
                                    isFpAllowedInBouncer
                                )
                        }
                    } else {
                        emptyFlow()
                    }
                }
                .collectLatest { messageViewModel -> message.value = messageViewModel }
        }
    }

    private fun listenForSimBouncerEvents() {
        // Listen for any events from the SIM bouncer and update the message shown on the bouncer.
        applicationScope.launch {
            authenticationInteractor.authenticationMethod
                .flatMapLatest { authMethod ->
                    if (authMethod == AuthenticationMethodModel.Sim) {
                        simBouncerInteractor.bouncerMessageChanged.map { simMsg ->
                            simMsg?.let { MessageViewModel(it) }
                        }
                    } else {
                        emptyFlow()
                    }
                }
                .collectLatest {
                    if (it != null) {
                        message.value = it
                    } else {
                        resetToDefault.emit(Unit)
                    }
                }
        }
    }

    private fun listenForFaceMessages() {
        // Listen for any events from face authentication and update the message shown on the
        // bouncer.
        applicationScope.launch {
            biometricMessageInteractor.faceMessage
                .sample(
                    authenticationInteractor.authenticationMethod,
                    fingerprintInteractor.isFingerprintCurrentlyAllowedOnBouncer,
                )
                .collectLatest { (faceMessage, authMethod, fingerprintAllowedOnBouncer) ->
                    val isFaceAuthStrong = faceAuthInteractor.isFaceAuthStrong()
                    val defaultPrimaryMessage =
                        BouncerMessageStrings.defaultMessage(
                                authMethod,
                                fingerprintAllowedOnBouncer
                            )
                            .primaryMessage
                            .toResString()
                    message.value =
                        when (faceMessage) {
                            is FaceTimeoutMessage ->
                                MessageViewModel(
                                    text = defaultPrimaryMessage,
                                    secondaryText = faceMessage.message,
                                    isUpdateAnimated = true
                                )
                            is FaceLockoutMessage ->
                                if (isFaceAuthStrong)
                                    BouncerMessageStrings.class3AuthLockedOut(authMethod)
                                        .toMessage()
                                else
                                    BouncerMessageStrings.faceLockedOut(
                                            authMethod,
                                            fingerprintAllowedOnBouncer
                                        )
                                        .toMessage()
                            is FaceFailureMessage ->
                                BouncerMessageStrings.incorrectFaceInput(
                                        authMethod,
                                        fingerprintAllowedOnBouncer
                                    )
                                    .toMessage()
                            else ->
                                MessageViewModel(
                                    text = defaultPrimaryMessage,
                                    secondaryText = faceMessage.message,
                                    isUpdateAnimated = false
                                )
                        }
                    delay(MESSAGE_DURATION)
                    resetToDefault.emit(Unit)
                }
        }
    }

    private fun listenForFingerprintMessages() {
        applicationScope.launch {
            // Listen for any events from fingerprint authentication and update the message shown
            // on the bouncer.
            biometricMessageInteractor.fingerprintMessage
                .sample(
                    authenticationInteractor.authenticationMethod,
                    fingerprintInteractor.isFingerprintCurrentlyAllowedOnBouncer
                )
                .collectLatest { (fingerprintMessage, authMethod, isFingerprintAllowed) ->
                    val defaultPrimaryMessage =
                        BouncerMessageStrings.defaultMessage(authMethod, isFingerprintAllowed)
                            .primaryMessage
                            .toResString()
                    message.value =
                        when (fingerprintMessage) {
                            is FingerprintLockoutMessage ->
                                BouncerMessageStrings.class3AuthLockedOut(authMethod).toMessage()
                            is FingerprintFailureMessage ->
                                BouncerMessageStrings.incorrectFingerprintInput(authMethod)
                                    .toMessage()
                            else ->
                                MessageViewModel(
                                    text = defaultPrimaryMessage,
                                    secondaryText = fingerprintMessage.message,
                                    isUpdateAnimated = false
                                )
                        }
                    delay(MESSAGE_DURATION)
                    resetToDefault.emit(Unit)
                }
        }
    }

    private fun listenForBouncerEvents() {
        // Keeps the lockout message up-to-date.
        applicationScope.launch {
            bouncerInteractor.onLockoutStarted.collect { startLockoutCountdown() }
        }

        // Listens to relevant bouncer events
        applicationScope.launch {
            bouncerInteractor.onIncorrectBouncerInput
                .sample(
                    authenticationInteractor.authenticationMethod,
                    fingerprintInteractor.isFingerprintCurrentlyAllowedOnBouncer
                )
                .collectLatest { (_, authMethod, isFingerprintAllowed) ->
                    message.emit(
                        BouncerMessageStrings.incorrectSecurityInput(
                                authMethod,
                                isFingerprintAllowed
                            )
                            .toMessage()
                    )
                    delay(MESSAGE_DURATION)
                    resetToDefault.emit(Unit)
                }
        }
    }

    private fun DeviceEntryRestrictionReason?.toMessage(
        authMethod: AuthenticationMethodModel,
        isFingerprintAllowedOnBouncer: Boolean,
    ): MessageViewModel {
        return when (this) {
            DeviceEntryRestrictionReason.UserLockdown ->
                BouncerMessageStrings.authRequiredAfterUserLockdown(authMethod)
            DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot ->
                BouncerMessageStrings.authRequiredAfterReboot(authMethod)
            DeviceEntryRestrictionReason.PolicyLockdown ->
                BouncerMessageStrings.authRequiredAfterAdminLockdown(authMethod)
            DeviceEntryRestrictionReason.UnattendedUpdate ->
                BouncerMessageStrings.authRequiredForUnattendedUpdate(authMethod)
            DeviceEntryRestrictionReason.DeviceNotUnlockedSinceMainlineUpdate ->
                BouncerMessageStrings.authRequiredForMainlineUpdate(authMethod)
            DeviceEntryRestrictionReason.SecurityTimeout ->
                BouncerMessageStrings.authRequiredAfterPrimaryAuthTimeout(authMethod)
            DeviceEntryRestrictionReason.StrongBiometricsLockedOut ->
                BouncerMessageStrings.class3AuthLockedOut(authMethod)
            DeviceEntryRestrictionReason.NonStrongFaceLockedOut ->
                BouncerMessageStrings.faceLockedOut(authMethod, isFingerprintAllowedOnBouncer)
            DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout ->
                BouncerMessageStrings.nonStrongAuthTimeout(
                    authMethod,
                    isFingerprintAllowedOnBouncer
                )
            DeviceEntryRestrictionReason.TrustAgentDisabled ->
                BouncerMessageStrings.trustAgentDisabled(authMethod, isFingerprintAllowedOnBouncer)
            DeviceEntryRestrictionReason.AdaptiveAuthRequest ->
                BouncerMessageStrings.authRequiredAfterAdaptiveAuthRequest(
                    authMethod,
                    isFingerprintAllowedOnBouncer
                )
            else -> BouncerMessageStrings.defaultMessage(authMethod, isFingerprintAllowedOnBouncer)
        }.toMessage()
    }

    private fun BouncerMessagePair.toMessage(): MessageViewModel {
        val primaryMsg = this.primaryMessage.toResString()
        val secondaryMsg =
            if (this.secondaryMessage == 0) "" else this.secondaryMessage.toResString()
        return MessageViewModel(primaryMsg, secondaryText = secondaryMsg, isUpdateAnimated = true)
    }

    /** Shows the countdown message and refreshes it every second. */
    private fun startLockoutCountdown() {
        lockoutCountdownJob?.cancel()
        lockoutCountdownJob =
            applicationScope.launch {
                authenticationInteractor.authenticationMethod.collectLatest { authMethod ->
                    do {
                        val remainingSeconds = remainingLockoutSeconds()
                        val authLockedOutMsg =
                            BouncerMessageStrings.primaryAuthLockedOut(authMethod)
                        lockoutMessage.value =
                            if (remainingSeconds > 0) {
                                MessageViewModel(
                                    text =
                                        kg_too_many_failed_attempts_countdown.toPluralString(
                                            mutableMapOf<String, Any>(
                                                Pair("count", remainingSeconds)
                                            )
                                        ),
                                    secondaryText = authLockedOutMsg.secondaryMessage.toResString(),
                                    isUpdateAnimated = false
                                )
                            } else {
                                null
                            }
                        delay(1.seconds)
                    } while (remainingSeconds > 0)
                    lockoutCountdownJob = null
                }
            }
    }

    private fun remainingLockoutSeconds(): Int {
        val endTimestampMs = authenticationInteractor.lockoutEndTimestamp ?: 0
        val remainingMs = max(0, endTimestampMs - clock.elapsedRealtime())
        return ceil(remainingMs / 1000f).toInt()
    }

    private fun Int.toPluralString(formatterArgs: Map<String, Any>): String =
        PluralsMessageFormatter.format(applicationContext.resources, formatterArgs, this)

    private fun Int.toResString(): String = applicationContext.getString(this)

    init {
        if (flags.isComposeBouncerOrSceneContainerEnabled()) {
            applicationScope.launch {
                // Update the lockout countdown whenever the selected user is switched.
                selectedUser.collect { startLockoutCountdown() }
            }

            defaultBouncerMessageInitializer()

            listenForSimBouncerEvents()
            listenForBouncerEvents()
            listenForFaceMessages()
            listenForFingerprintMessages()
        }
    }

    companion object {
        private const val MESSAGE_DURATION = 2000L
    }
}

/** Data class that represents the status message show on the bouncer. */
data class MessageViewModel(
    val text: String,
    val secondaryText: String? = null,
    /**
     * Whether updates to the message should be cross-animated from one message to another.
     *
     * If `false`, no animation should be applied, the message text should just be replaced
     * instantly.
     */
    val isUpdateAnimated: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Module
object BouncerMessageViewModelModule {

    @Provides
    @SysUISingleton
    fun viewModel(
        @Application applicationContext: Context,
        @Application applicationScope: CoroutineScope,
        bouncerInteractor: BouncerInteractor,
        simBouncerInteractor: SimBouncerInteractor,
        authenticationInteractor: AuthenticationInteractor,
        clock: SystemClock,
        biometricMessageInteractor: BiometricMessageInteractor,
        faceAuthInteractor: DeviceEntryFaceAuthInteractor,
        deviceEntryInteractor: DeviceEntryInteractor,
        fingerprintInteractor: DeviceEntryFingerprintAuthInteractor,
        flags: ComposeBouncerFlags,
        userSwitcherViewModel: UserSwitcherViewModel,
    ): BouncerMessageViewModel {
        return BouncerMessageViewModel(
            applicationContext = applicationContext,
            applicationScope = applicationScope,
            bouncerInteractor = bouncerInteractor,
            simBouncerInteractor = simBouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            clock = clock,
            biometricMessageInteractor = biometricMessageInteractor,
            faceAuthInteractor = faceAuthInteractor,
            deviceEntryInteractor = deviceEntryInteractor,
            fingerprintInteractor = fingerprintInteractor,
            flags = flags,
            selectedUser = userSwitcherViewModel.selectedUser,
        )
    }
}
