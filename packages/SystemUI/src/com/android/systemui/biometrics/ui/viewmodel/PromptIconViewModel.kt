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
 *
 */

package com.android.systemui.biometrics.ui.viewmodel

import android.annotation.DrawableRes
import android.annotation.RawRes
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.face.Face
import android.util.RotationUtils
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Models UI of [BiometricPromptLayout.iconView] and [BiometricPromptLayout.biometric_icon_overlay]
 */
class PromptIconViewModel
constructor(
    promptViewModel: PromptViewModel,
    private val displayStateInteractor: DisplayStateInteractor,
    promptSelectorInteractor: PromptSelectorInteractor,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {

    /** Auth types for the UI to display. */
    enum class AuthType {
        Fingerprint,
        Face,
        Coex
    }

    /**
     * Indicates what auth type the UI currently displays.
     * Fingerprint-only auth -> Fingerprint
     * Face-only auth -> Face
     * Co-ex auth, implicit flow -> Face
     * Co-ex auth, explicit flow -> Coex
     */
    val activeAuthType: Flow<AuthType> =
        combine(
            promptViewModel.size,
            promptViewModel.modalities.distinctUntilChanged(),
            promptViewModel.faceMode.distinctUntilChanged()
        ) { _, modalities, faceMode ->
            if (modalities.hasFaceAndFingerprint && !faceMode) {
                AuthType.Coex
            } else if (modalities.hasFaceOnly || faceMode) {
                AuthType.Face
            } else if (modalities.hasFingerprintOnly) {
                AuthType.Fingerprint
            } else {
                // TODO(b/288175072): Remove, currently needed for transition to credential view
                AuthType.Fingerprint
            }
        }

    val udfpsSensorBounds: Flow<Rect> =
        combine(
                udfpsOverlayInteractor.udfpsOverlayParams,
                displayStateInteractor.currentRotation
            ) { params, rotation ->
                val rotatedBounds = Rect(params.sensorBounds)
                RotationUtils.rotateBounds(
                    rotatedBounds,
                    params.naturalDisplayWidth,
                    params.naturalDisplayHeight,
                    rotation.ordinal
                )
                rotatedBounds
            }
            .distinctUntilChanged()

    val iconPosition: Flow<Rect> =
        combine(udfpsSensorBounds, promptViewModel.size, promptViewModel.modalities) {
            sensorBounds,
            size,
            modalities ->
            // If not Udfps, icon does not change from default layout position
            if (!modalities.hasUdfps) {
                Rect() // Empty rect, don't offset from default position
            } else if (size.isSmall) {
                // When small with Udfps, only set horizontal position
                Rect(sensorBounds.left, -1, sensorBounds.right, -1)
            } else {
                sensorBounds
            }
        }

    /** Whether an error message is currently being shown. */
    val showingError = promptViewModel.showingError

    /** Whether the previous icon shown displayed an error. */
    private val _previousIconWasError: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Whether the previous icon overlay shown displayed an error. */
    private val _previousIconOverlayWasError: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun setPreviousIconWasError(previousIconWasError: Boolean) {
        _previousIconWasError.value = previousIconWasError
    }

    fun setPreviousIconOverlayWasError(previousIconOverlayWasError: Boolean) {
        _previousIconOverlayWasError.value = previousIconOverlayWasError
    }

    /** Called when iconView begins animating. */
    fun onAnimationStart() {
        _animationEnded.value = false
    }

    /** Called when iconView ends animating. */
    fun onAnimationEnd() {
        _animationEnded.value = true
    }

    private val _animationEnded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Whether a face iconView should pulse (i.e. while isAuthenticating and previous animation
     * ended).
     */
    val shouldPulseAnimation: Flow<Boolean> =
        combine(_animationEnded, promptViewModel.isAuthenticating) {
                animationEnded,
                isAuthenticating ->
                animationEnded && isAuthenticating
            }
            .distinctUntilChanged()

    private val _lastPulseLightToDark: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Tracks whether a face iconView last pulsed light to dark (vs. dark to light) */
    val lastPulseLightToDark: Flow<Boolean> = _lastPulseLightToDark.asStateFlow()

    val iconSize: Flow<Pair<Int, Int>> =
        combine(
            activeAuthType,
            promptViewModel.fingerprintSensorWidth,
            promptViewModel.fingerprintSensorHeight,
        ) { activeAuthType, fingerprintSensorWidth, fingerprintSensorHeight ->
            if (activeAuthType == AuthType.Face) {
                Pair(promptViewModel.faceIconWidth, promptViewModel.faceIconHeight)
            } else {
                Pair(fingerprintSensorWidth, fingerprintSensorHeight)
            }
        }

    /** Current BiometricPromptLayout.iconView asset. */
    val iconAsset: Flow<Int> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint ->
                    combine(
                        displayStateInteractor.currentRotation,
                        displayStateInteractor.isFolded,
                        displayStateInteractor.isInRearDisplayMode,
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) {
                        rotation: DisplayRotation,
                        isFolded: Boolean,
                        isInRearDisplayMode: Boolean,
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                getSfpsIconViewAsset(rotation, isFolded, isInRearDisplayMode)
                            else ->
                                getFingerprintIconViewAsset(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                        }
                    }
                AuthType.Face ->
                    shouldPulseAnimation.flatMapLatest { shouldPulseAnimation: Boolean ->
                        if (shouldPulseAnimation) {
                            val iconAsset =
                                if (_lastPulseLightToDark.value) {
                                    R.drawable.face_dialog_pulse_dark_to_light
                                } else {
                                    R.drawable.face_dialog_pulse_light_to_dark
                                }
                            _lastPulseLightToDark.value = !_lastPulseLightToDark.value
                            flowOf(iconAsset)
                        } else {
                            combine(
                                promptViewModel.isAuthenticated.distinctUntilChanged(),
                                promptViewModel.isAuthenticating.distinctUntilChanged(),
                                promptViewModel.isPendingConfirmation.distinctUntilChanged(),
                                promptViewModel.showingError.distinctUntilChanged()
                            ) {
                                authState: PromptAuthState,
                                isAuthenticating: Boolean,
                                isPendingConfirmation: Boolean,
                                showingError: Boolean ->
                                getFaceIconViewAsset(
                                    authState,
                                    isAuthenticating,
                                    isPendingConfirmation,
                                    showingError
                                )
                            }
                        }
                    }
                AuthType.Coex ->
                    combine(
                        displayStateInteractor.currentRotation,
                        displayStateInteractor.isFolded,
                        displayStateInteractor.isInRearDisplayMode,
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.isPendingConfirmation,
                        promptViewModel.showingError,
                    ) {
                        rotation: DisplayRotation,
                        isFolded: Boolean,
                        isInRearDisplayMode: Boolean,
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        isPendingConfirmation: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                getSfpsIconViewAsset(rotation, isFolded, isInRearDisplayMode)
                            else ->
                                getCoexIconViewAsset(
                                    authState,
                                    isAuthenticating,
                                    isPendingConfirmation,
                                    showingError
                                )
                        }
                    }
            }
        }

    private fun getFingerprintIconViewAsset(
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ): Int =
        if (isAuthenticated) {
            if (_previousIconWasError.value) {
                R.raw.fingerprint_dialogue_error_to_success_lottie
            } else {
                R.raw.fingerprint_dialogue_fingerprint_to_success_lottie
            }
        } else if (isAuthenticating) {
            if (_previousIconWasError.value) {
                R.raw.fingerprint_dialogue_error_to_fingerprint_lottie
            } else {
                R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
            }
        } else if (showingError) {
            R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
        } else {
            -1
        }

    @RawRes
    private fun getSfpsIconViewAsset(
        rotation: DisplayRotation,
        isDeviceFolded: Boolean,
        isInRearDisplayMode: Boolean,
    ): Int =
        when (rotation) {
            DisplayRotation.ROTATION_90 ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_portrait_reverse_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_topleft
                } else {
                    R.raw.biometricprompt_portrait_base_topleft
                }
            DisplayRotation.ROTATION_270 ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_portrait_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_bottomright
                } else {
                    R.raw.biometricprompt_portrait_base_bottomright
                }
            else ->
                if (isInRearDisplayMode) {
                    R.raw.biometricprompt_rear_landscape_base
                } else if (isDeviceFolded) {
                    R.raw.biometricprompt_folded_base_default
                } else {
                    R.raw.biometricprompt_landscape_base
                }
        }

    @DrawableRes
    private fun getFaceIconViewAsset(
        authState: PromptAuthState,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ): Int =
        if (authState.isAuthenticated && isPendingConfirmation) {
            R.drawable.face_dialog_wink_from_dark
        } else if (authState.isAuthenticated) {
            R.drawable.face_dialog_dark_to_checkmark
        } else if (isAuthenticating) {
            _lastPulseLightToDark.value = false
            R.drawable.face_dialog_pulse_dark_to_light
        } else if (showingError) {
            R.drawable.face_dialog_dark_to_error
        } else if (_previousIconWasError.value) {
            R.drawable.face_dialog_error_to_idle
        } else {
            R.drawable.face_dialog_idle_static
        }

    @RawRes
    private fun getCoexIconViewAsset(
        authState: PromptAuthState,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ): Int =
        if (authState.isAuthenticatedAndExplicitlyConfirmed) {
            R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie
        } else if (isPendingConfirmation) {
            if (_previousIconWasError.value) {
                R.raw.fingerprint_dialogue_error_to_unlock_lottie
            } else {
                R.raw.fingerprint_dialogue_fingerprint_to_unlock_lottie
            }
        } else if (authState.isAuthenticated) {
            if (_previousIconWasError.value) {
                R.raw.fingerprint_dialogue_error_to_success_lottie
            } else {
                R.raw.fingerprint_dialogue_fingerprint_to_success_lottie
            }
        } else if (isAuthenticating) {
            if (_previousIconWasError.value) {
                R.raw.fingerprint_dialogue_error_to_fingerprint_lottie
            } else {
                R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
            }
        } else if (showingError) {
            R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
        } else {
            -1
        }

    /** Current BiometricPromptLayout.biometric_icon_overlay asset. */
    var iconOverlayAsset: Flow<Int> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex ->
                    combine(
                        displayStateInteractor.currentRotation,
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) {
                        rotation: DisplayRotation,
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                getSfpsIconOverlayAsset(
                                    rotation,
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                            else -> -1
                        }
                    }
                AuthType.Face -> flowOf(-1)
            }
        }

    @RawRes
    private fun getSfpsIconOverlayAsset(
        rotation: DisplayRotation,
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ): Int =
        if (isAuthenticated) {
            if (_previousIconOverlayWasError.value) {
                when (rotation) {
                    DisplayRotation.ROTATION_0 ->
                        R.raw.biometricprompt_symbol_error_to_success_landscape
                    DisplayRotation.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_error_to_success_portrait_topleft
                    DisplayRotation.ROTATION_180 ->
                        R.raw.biometricprompt_symbol_error_to_success_landscape
                    DisplayRotation.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_error_to_success_portrait_bottomright
                }
            } else {
                when (rotation) {
                    DisplayRotation.ROTATION_0 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                    DisplayRotation.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_topleft
                    DisplayRotation.ROTATION_180 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                    DisplayRotation.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_bottomright
                }
            }
        } else if (isAuthenticating) {
            if (_previousIconOverlayWasError.value) {
                when (rotation) {
                    DisplayRotation.ROTATION_0 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                    DisplayRotation.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_topleft
                    DisplayRotation.ROTATION_180 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                    DisplayRotation.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_bottomright
                }
            } else {
                when (rotation) {
                    DisplayRotation.ROTATION_0 ->
                        R.raw.biometricprompt_fingerprint_to_error_landscape
                    DisplayRotation.ROTATION_90 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                    DisplayRotation.ROTATION_180 ->
                        R.raw.biometricprompt_fingerprint_to_error_landscape
                    DisplayRotation.ROTATION_270 ->
                        R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                }
            }
        } else if (showingError) {
            when (rotation) {
                DisplayRotation.ROTATION_0 -> R.raw.biometricprompt_fingerprint_to_error_landscape
                DisplayRotation.ROTATION_90 ->
                    R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                DisplayRotation.ROTATION_180 -> R.raw.biometricprompt_fingerprint_to_error_landscape
                DisplayRotation.ROTATION_270 ->
                    R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
            }
        } else {
            -1
        }

    /** Content description for iconView */
    val contentDescriptionId: Flow<Int> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex ->
                    combine(
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.isPendingConfirmation,
                        promptViewModel.showingError
                    ) {
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        isPendingConfirmation: Boolean,
                        showingError: Boolean ->
                        getFingerprintIconContentDescriptionId(
                            sensorType,
                            authState.isAuthenticated,
                            isAuthenticating,
                            isPendingConfirmation,
                            showingError
                        )
                    }
                AuthType.Face ->
                    combine(
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError,
                    ) { authState: PromptAuthState, isAuthenticating: Boolean, showingError: Boolean
                        ->
                        getFaceIconContentDescriptionId(authState, isAuthenticating, showingError)
                    }
            }
        }

    private fun getFingerprintIconContentDescriptionId(
        sensorType: FingerprintSensorType,
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ): Int =
        if (isPendingConfirmation) {
            when (sensorType) {
                FingerprintSensorType.POWER_BUTTON -> -1
                else -> R.string.fingerprint_dialog_authenticated_confirmation
            }
        } else if (isAuthenticating || isAuthenticated) {
            when (sensorType) {
                FingerprintSensorType.POWER_BUTTON ->
                    R.string.security_settings_sfps_enroll_find_sensor_message
                else -> R.string.fingerprint_dialog_touch_sensor
            }
        } else if (showingError) {
            R.string.biometric_dialog_try_again
        } else {
            -1
        }

    private fun getFaceIconContentDescriptionId(
        authState: PromptAuthState,
        isAuthenticating: Boolean,
        showingError: Boolean
    ): Int =
        if (authState.isAuthenticatedAndExplicitlyConfirmed) {
            R.string.biometric_dialog_face_icon_description_confirmed
        } else if (authState.isAuthenticated) {
            R.string.biometric_dialog_face_icon_description_authenticated
        } else if (isAuthenticating) {
            R.string.biometric_dialog_face_icon_description_authenticating
        } else if (showingError) {
            R.string.keyguard_face_failed
        } else {
            R.string.biometric_dialog_face_icon_description_idle
        }

    /** Whether the current BiometricPromptLayout.iconView asset animation should be playing. */
    val shouldAnimateIconView: Flow<Boolean> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint ->
                    combine(
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) {
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                shouldAnimateSfpsIconView(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                            else ->
                                shouldAnimateFingerprintIconView(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                        }
                    }
                AuthType.Face ->
                    combine(
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) { authState: PromptAuthState, isAuthenticating: Boolean, showingError: Boolean
                        ->
                        isAuthenticating ||
                            authState.isAuthenticated ||
                            showingError ||
                            _previousIconWasError.value
                    }
                AuthType.Coex ->
                    combine(
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.isPendingConfirmation,
                        promptViewModel.showingError,
                    ) {
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        isPendingConfirmation: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                shouldAnimateSfpsIconView(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                            else ->
                                shouldAnimateCoexIconView(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    isPendingConfirmation,
                                    showingError
                                )
                        }
                    }
            }
        }

    private fun shouldAnimateFingerprintIconView(
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ) = (isAuthenticating && _previousIconWasError.value) || isAuthenticated || showingError

    private fun shouldAnimateSfpsIconView(
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ) = isAuthenticated || isAuthenticating || showingError

    private fun shouldAnimateCoexIconView(
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ) =
        (isAuthenticating && _previousIconWasError.value) ||
            isPendingConfirmation ||
            isAuthenticated ||
            showingError

    /** Whether the current iconOverlayAsset animation should be playing. */
    val shouldAnimateIconOverlay: Flow<Boolean> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex ->
                    combine(
                        promptSelectorInteractor.sensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) {
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                shouldAnimateSfpsIconOverlay(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                            else -> false
                        }
                    }
                AuthType.Face -> flowOf(false)
            }
        }

    private fun shouldAnimateSfpsIconOverlay(
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ) = (isAuthenticating && _previousIconOverlayWasError.value) || isAuthenticated || showingError

    /** Whether the iconView should be flipped due to a device using reverse default rotation . */
    val shouldFlipIconView: Flow<Boolean> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex ->
                    combine(
                        promptSelectorInteractor.sensorType,
                        displayStateInteractor.currentRotation
                    ) { sensorType: FingerprintSensorType, rotation: DisplayRotation ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                (rotation == DisplayRotation.ROTATION_180)
                            else -> false
                        }
                    }
                AuthType.Face -> flowOf(false)
            }
        }

    /** Whether the current BiometricPromptLayout.iconView asset animation should be repeated. */
    val shouldRepeatAnimation: Flow<Boolean> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex -> flowOf(false)
                AuthType.Face -> promptViewModel.isAuthenticating.map { it }
            }
        }

    /** Called on configuration changes */
    fun onConfigurationChanged(newConfig: Configuration) {
        displayStateInteractor.onConfigurationChanged(newConfig)
    }

    /** iconView assets for caching */
    fun getRawAssets(hasSfps: Boolean): List<Int> {
        return if (hasSfps) {
            listOf(
                R.raw.biometricprompt_fingerprint_to_error_landscape,
                R.raw.biometricprompt_folded_base_bottomright,
                R.raw.biometricprompt_folded_base_default,
                R.raw.biometricprompt_folded_base_topleft,
                R.raw.biometricprompt_landscape_base,
                R.raw.biometricprompt_portrait_base_bottomright,
                R.raw.biometricprompt_portrait_base_topleft,
                R.raw.biometricprompt_symbol_error_to_fingerprint_landscape,
                R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_bottomright,
                R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_topleft,
                R.raw.biometricprompt_symbol_error_to_success_landscape,
                R.raw.biometricprompt_symbol_error_to_success_portrait_bottomright,
                R.raw.biometricprompt_symbol_error_to_success_portrait_topleft,
                R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright,
                R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft,
                R.raw.biometricprompt_symbol_fingerprint_to_success_landscape,
                R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_bottomright,
                R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_topleft
            )
        } else {
            listOf(
                R.raw.fingerprint_dialogue_error_to_fingerprint_lottie,
                R.raw.fingerprint_dialogue_error_to_success_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_error_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_success_lottie
            )
        }
    }
}
