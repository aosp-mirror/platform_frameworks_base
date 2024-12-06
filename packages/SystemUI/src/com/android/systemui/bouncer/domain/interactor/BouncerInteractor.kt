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

import android.app.StatusBarManager.SESSION_KEYGUARD
import com.android.app.tracing.coroutines.asyncTraced as async
import com.android.compose.animation.scene.SceneKey
import com.android.internal.logging.UiEventLogger
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Password
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pattern
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pin
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Sim
import com.android.systemui.authentication.shared.model.BouncerInputSide
import com.android.systemui.bouncer.data.repository.BouncerRepository
import com.android.systemui.bouncer.shared.logging.BouncerUiEvent
import com.android.systemui.classifier.FalsingClassifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.log.SessionTracker
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Encapsulates business logic and application state accessing use-cases. */
@SysUISingleton
class BouncerInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: BouncerRepository,
    private val authenticationInteractor: AuthenticationInteractor,
    private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val falsingInteractor: FalsingInteractor,
    private val powerInteractor: PowerInteractor,
    private val uiEventLogger: UiEventLogger,
    private val sessionTracker: SessionTracker,
    sceneBackInteractor: SceneBackInteractor,
    @ShadeDisplayAware private val configurationInteractor: ConfigurationInteractor,
) {
    private val _onIncorrectBouncerInput = MutableSharedFlow<Unit>()
    val onIncorrectBouncerInput: SharedFlow<Unit> = _onIncorrectBouncerInput

    /** Whether the auto confirm feature is enabled for the currently-selected user. */
    val isAutoConfirmEnabled: StateFlow<Boolean> = authenticationInteractor.isAutoConfirmEnabled

    /** The length of the hinted PIN, or `null`, if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> = authenticationInteractor.hintedPinLength

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean> = authenticationInteractor.isPatternVisible

    /** Whether the "enhanced PIN privacy" setting is enabled for the current user. */
    val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> =
        authenticationInteractor.isPinEnhancedPrivacyEnabled

    /** Whether the user switcher should be displayed within the bouncer UI on large screens. */
    val isUserSwitcherVisible: Flow<Boolean> =
        authenticationInteractor.authenticationMethod.map { authMethod ->
            when (authMethod) {
                Sim -> false
                else -> repository.isUserSwitcherEnabledInConfig
            }
        }

    /**
     * Whether one handed bouncer mode is supported on large screen devices. This allows user to
     * double tap on the half of the screen to bring the bouncer input to that side of the screen.
     */
    val isOneHandedModeSupported: Flow<Boolean> =
        combine(
            isUserSwitcherVisible,
            authenticationInteractor.authenticationMethod,
            configurationInteractor.onAnyConfigurationChange,
        ) { userSwitcherVisible, authMethod, _ ->
            userSwitcherVisible ||
                (repository.isOneHandedBouncerSupportedInConfig && (authMethod !is Password))
        }

    /**
     * Preferred side of the screen where the input area on the bouncer should be. This is
     * applicable for large screen devices (foldables and tablets).
     */
    val preferredBouncerInputSide: Flow<BouncerInputSide?> =
        combine(
            configurationInteractor.onAnyConfigurationChange,
            repository.preferredBouncerInputSide,
        ) { _, _ ->
            // always read the setting as that can change outside of this
            // repository (tests/manual testing)
            val preferredInputSide = repository.getPreferredInputSideSetting()
            when {
                preferredInputSide != null -> preferredInputSide
                repository.isUserSwitcherEnabledInConfig -> BouncerInputSide.RIGHT
                repository.isOneHandedBouncerSupportedInConfig -> BouncerInputSide.LEFT
                else -> null
            }
        }

    private val _onImeHiddenByUser = MutableSharedFlow<Unit>()
    /** Emits a [Unit] each time the IME (keyboard) is hidden by the user. */
    val onImeHiddenByUser: SharedFlow<Unit> = _onImeHiddenByUser

    /** Emits a [Unit] each time a lockout is started for the selected user. */
    val onLockoutStarted: Flow<Unit> =
        authenticationInteractor.onAuthenticationResult
            .filter { successfullyAuthenticated ->
                !successfullyAuthenticated && authenticationInteractor.lockoutEndTimestamp != null
            }
            .map {}

    /** X coordinate of the last recorded touch position on the lockscreen. */
    val lastRecordedLockscreenTouchPosition = repository.lastRecordedLockscreenTouchPosition

    /** Value between 0-1 that specifies by how much the bouncer UI should be scaled down. */
    val scale: StateFlow<Float> = repository.scale.asStateFlow()

    /** The scene to show when bouncer is dismissed. */
    val dismissDestination: Flow<SceneKey> =
        sceneBackInteractor.backScene
            .filter { it != Scenes.Bouncer }
            .map { it ?: Scenes.Lockscreen }

    /** Notifies that the user has places down a pointer, not necessarily dragging just yet. */
    fun onDown() {
        falsingInteractor.avoidGesture()
    }

    /**
     * Notifies of "intentional" (i.e. non-false) user interaction with the UI which is very likely
     * to be real user interaction with the bouncer and not the result of a false touch in the
     * user's pocket or by the user's face while holding their device up to their ear.
     */
    fun onIntentionalUserInput() {
        deviceEntryFaceAuthInteractor.onPrimaryBouncerUserInput()
        powerInteractor.onUserTouch()
        falsingInteractor.updateFalseConfidence(FalsingClassifier.Result.passed(0.6))
    }

    /**
     * Notifies of false input which is very likely to be the result of a false touch in the user's
     * pocket or by the user's face while holding their device up to their ear.
     */
    fun onFalseUserInput() {
        falsingInteractor.updateFalseConfidence(
            FalsingClassifier.Result.falsed(
                /* confidence= */ 0.7,
                /* context= */ javaClass.simpleName,
                /* reason= */ "empty pattern input",
            )
        )
    }

    /** Update the preferred input side for the bouncer. */
    fun setPreferredBouncerInputSide(inputSide: BouncerInputSide) {
        repository.setPreferredBouncerInputSide(inputSide)
    }

    /**
     * Record the x coordinate of the last touch position on the lockscreen. This will be used to
     * determine which side of the bouncer the input area should be shown.
     */
    fun recordKeyguardTouchPosition(x: Float) {
        // todo (b/375245685) investigate why this is not working as expected when it is
        //  wired up with SBKVM
        repository.recordLockscreenTouchPosition(x)
    }

    fun onBackEventProgressed(progress: Float) {
        // this is applicable only for compose bouncer without flexiglass
        SceneContainerFlag.assertInLegacyMode()
        repository.scale.value = (mapBackEventProgressToScale(progress))
    }

    fun onBackEventCancelled() {
        // this is applicable only for compose bouncer without flexiglass
        SceneContainerFlag.assertInLegacyMode()
        repository.scale.value = DEFAULT_SCALE
    }

    fun resetScale() {
        repository.scale.value = DEFAULT_SCALE
    }

    /**
     * Attempts to authenticate based on the given user input.
     *
     * If the input is correct, the device will be unlocked and the lock screen and bouncer will be
     * dismissed and hidden.
     *
     * If [tryAutoConfirm] is `true`, authentication is attempted if and only if the auth method
     * supports auto-confirming, and the input's length is at least the required length. Otherwise,
     * `AuthenticationResult.SKIPPED` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return The result of this authentication attempt.
     */
    suspend fun authenticate(
        input: List<Any>,
        tryAutoConfirm: Boolean = false,
    ): AuthenticationResult {
        if (input.isEmpty()) {
            return AuthenticationResult.SKIPPED
        }

        if (authenticationInteractor.getAuthenticationMethod() == Sim) {
            // SIM is authenticated in SimBouncerInteractor.
            return AuthenticationResult.SKIPPED
        }

        // Switching to the application scope here since this method is often called from
        // view-models, whose lifecycle (and thus scope) is shorter than this interactor.
        // This allows the task to continue running properly even when the calling scope has been
        // cancelled.
        val authResult =
            applicationScope
                .async { authenticationInteractor.authenticate(input, tryAutoConfirm) }
                .await()

        if (
            authResult == AuthenticationResult.FAILED ||
                (authResult == AuthenticationResult.SKIPPED && !tryAutoConfirm)
        ) {
            _onIncorrectBouncerInput.emit(Unit)
        }

        if (authenticationInteractor.getAuthenticationMethod() in setOf(Pin, Password, Pattern)) {
            if (authResult == AuthenticationResult.SUCCEEDED) {
                uiEventLogger.log(BouncerUiEvent.BOUNCER_PASSWORD_SUCCESS)
            } else if (authResult == AuthenticationResult.FAILED) {
                uiEventLogger.log(
                    BouncerUiEvent.BOUNCER_PASSWORD_FAILURE,
                    sessionTracker.getSessionId(SESSION_KEYGUARD),
                )
            }
        }

        return authResult
    }

    /** Notifies that the input method editor (software keyboard) has been hidden by the user. */
    suspend fun onImeHiddenByUser() {
        _onImeHiddenByUser.emit(Unit)
    }

    private fun mapBackEventProgressToScale(progress: Float): Float {
        // TODO(b/263819310): Update the interpolator to match spec.
        return MIN_BACK_SCALE + (1 - MIN_BACK_SCALE) * (1 - progress)
    }

    companion object {
        // How much the view scales down to during back gestures.
        private const val MIN_BACK_SCALE: Float = 0.9f
        private const val DEFAULT_SCALE: Float = 1.0f
    }
}
