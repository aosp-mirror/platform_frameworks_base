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

import android.annotation.RawRes
import android.content.res.Configuration
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Models UI of [BiometricPromptLayout.iconView] */
class PromptIconViewModel(
    promptViewModel: PromptViewModel,
    private val displayStateInteractor: DisplayStateInteractor,
    promptSelectorInteractor: PromptSelectorInteractor,
) {

    /** Auth types for the UI to display. */
    enum class AuthType {
        Fingerprint,
        Face,
        Coex
    }

    /**
     * Indicates what auth type the UI currently displays. Fingerprint-only auth -> Fingerprint
     * Face-only auth -> Face Co-ex auth, implicit flow -> Face Co-ex auth, explicit flow -> Coex
     */
    val activeAuthType: Flow<AuthType> =
        combine(
            promptViewModel.modalities.distinctUntilChanged(),
            promptViewModel.faceMode.distinctUntilChanged()
        ) { modalities, faceMode ->
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

    /** Whether an error message is currently being shown. */
    val showingError = promptViewModel.showingError

    /** Whether the previous icon shown displayed an error. */
    private val _previousIconWasError: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun setPreviousIconWasError(previousIconWasError: Boolean) {
        _previousIconWasError.value = previousIconWasError
    }

    val iconSize: Flow<Pair<Int, Int>> =
        combine(
            promptViewModel.position,
            activeAuthType,
            promptViewModel.legacyFingerprintSensorWidth,
            promptViewModel.legacyFingerprintSensorHeight,
        ) { _, activeAuthType, fingerprintSensorWidth, fingerprintSensorHeight ->
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
                        displayStateInteractor.isInRearDisplayMode,
                        promptSelectorInteractor.fingerprintSensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) {
                        rotation: DisplayRotation,
                        isInRearDisplayMode: Boolean,
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                getSfpsIconViewAsset(
                                    rotation,
                                    isInRearDisplayMode,
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                            else ->
                                getFingerprintIconViewAsset(
                                    authState.isAuthenticated,
                                    isAuthenticating,
                                    showingError
                                )
                        }
                    }
                AuthType.Face ->
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
                AuthType.Coex ->
                    combine(
                        displayStateInteractor.currentRotation,
                        displayStateInteractor.isInRearDisplayMode,
                        promptSelectorInteractor.fingerprintSensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.isPendingConfirmation,
                        promptViewModel.showingError,
                    ) {
                        rotation: DisplayRotation,
                        isInRearDisplayMode: Boolean,
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        isPendingConfirmation: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON ->
                                getCoexSfpsIconViewAsset(
                                    rotation,
                                    isInRearDisplayMode,
                                    authState,
                                    isAuthenticating,
                                    isPendingConfirmation,
                                    showingError
                                )
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
    ): Int {
        return if (isAuthenticated) {
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
    }

    @RawRes
    private fun getSfpsIconViewAsset(
        rotation: DisplayRotation,
        isInRearDisplayMode: Boolean,
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ): Int {
        return if (isAuthenticated) {
            if (_previousIconWasError.value) {
                R.raw.biometricprompt_sfps_error_to_success
            } else {
                getSfpsAsset_fingerprintToSuccess(rotation, isInRearDisplayMode)
            }
        } else if (isAuthenticating) {
            if (_previousIconWasError.value) {
                getSfpsAsset_errorToFingerprint(rotation, isInRearDisplayMode)
            } else {
                getSfpsAsset_fingerprintAuthenticating(isInRearDisplayMode)
            }
        } else if (showingError) {
            getSfpsAsset_fingerprintToError(rotation, isInRearDisplayMode)
        } else {
            -1
        }
    }

    @RawRes
    private fun getFaceIconViewAsset(
        authState: PromptAuthState,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ): Int {
        return if (authState.isAuthenticated && isPendingConfirmation) {
            R.raw.face_dialog_wink_from_dark
        } else if (authState.isAuthenticated) {
            R.raw.face_dialog_dark_to_checkmark
        } else if (isAuthenticating) {
            R.raw.face_dialog_authenticating
        } else if (showingError) {
            R.raw.face_dialog_dark_to_error
        } else if (_previousIconWasError.value) {
            R.raw.face_dialog_error_to_idle
        } else {
            R.raw.face_dialog_idle_static
        }
    }

    @RawRes
    private fun getCoexIconViewAsset(
        authState: PromptAuthState,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ): Int {
        return if (authState.isAuthenticatedAndExplicitlyConfirmed) {
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
    }

    @RawRes
    private fun getCoexSfpsIconViewAsset(
        rotation: DisplayRotation,
        isInRearDisplayMode: Boolean,
        authState: PromptAuthState,
        isAuthenticating: Boolean,
        isPendingConfirmation: Boolean,
        showingError: Boolean
    ): Int {
        return if (authState.isAuthenticatedAndExplicitlyConfirmed) {
            R.raw.biometricprompt_sfps_unlock_to_success
        } else if (isPendingConfirmation) {
            if (_previousIconWasError.value) {
                R.raw.biometricprompt_sfps_error_to_unlock
            } else {
                getSfpsAsset_fingerprintToUnlock(rotation, isInRearDisplayMode)
            }
        } else if (authState.isAuthenticated) {
            if (_previousIconWasError.value) {
                R.raw.biometricprompt_sfps_error_to_success
            } else {
                getSfpsAsset_fingerprintToSuccess(rotation, isInRearDisplayMode)
            }
        } else if (isAuthenticating) {
            if (_previousIconWasError.value) {
                getSfpsAsset_errorToFingerprint(rotation, isInRearDisplayMode)
            } else {
                getSfpsAsset_fingerprintAuthenticating(isInRearDisplayMode)
            }
        } else if (showingError) {
            getSfpsAsset_fingerprintToError(rotation, isInRearDisplayMode)
        } else {
            -1
        }
    }

    /** Content description for iconView */
    val contentDescriptionId: Flow<Int> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex ->
                    combine(
                        promptSelectorInteractor.fingerprintSensorType,
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
                else -> R.string.biometric_dialog_confirm
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
                        promptSelectorInteractor.fingerprintSensorType,
                        promptViewModel.isAuthenticated,
                        promptViewModel.isAuthenticating,
                        promptViewModel.showingError
                    ) {
                        sensorType: FingerprintSensorType,
                        authState: PromptAuthState,
                        isAuthenticating: Boolean,
                        showingError: Boolean ->
                        when (sensorType) {
                            FingerprintSensorType.POWER_BUTTON -> true
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
                        promptSelectorInteractor.fingerprintSensorType,
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
                            FingerprintSensorType.POWER_BUTTON -> true
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

    /** Whether the current BiometricPromptLayout.iconView asset animation should be looping. */
    val shouldLoopIconView: Flow<Boolean> =
        activeAuthType.flatMapLatest { activeAuthType: AuthType ->
            when (activeAuthType) {
                AuthType.Fingerprint,
                AuthType.Coex -> flowOf(false)
                AuthType.Face -> promptViewModel.isAuthenticating
            }
        }

    private fun shouldAnimateFingerprintIconView(
        isAuthenticated: Boolean,
        isAuthenticating: Boolean,
        showingError: Boolean
    ) = (isAuthenticating && _previousIconWasError.value) || isAuthenticated || showingError

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

    /* Used to rotate the iconView for assets reused across rotations. */
    val iconViewRotation: Flow<Float> =
        combine(iconAsset, displayStateInteractor.currentRotation) {
            icon: Int,
            rotation: DisplayRotation ->
            if (assetReusedAcrossRotations(icon)) {
                when (rotation) {
                    DisplayRotation.ROTATION_0 -> 0f
                    DisplayRotation.ROTATION_90 -> 270f
                    DisplayRotation.ROTATION_180 -> 180f
                    DisplayRotation.ROTATION_270 -> 90f
                }
            } else {
                0f
            }
        }

    private fun assetReusedAcrossRotations(asset: Int): Boolean {
        return asset in assetsReusedAcrossRotations
    }

    private val assetsReusedAcrossRotations: List<Int> =
        listOf(
            R.raw.biometricprompt_sfps_fingerprint_authenticating,
            R.raw.biometricprompt_sfps_rear_display_fingerprint_authenticating,
            R.raw.biometricprompt_sfps_rear_display_fingerprint_authenticating
        )

    /** Called on configuration changes */
    fun onConfigurationChanged(newConfig: Configuration) {
        displayStateInteractor.onConfigurationChanged(newConfig)
    }

    /** Coex iconView assets for caching */
    fun getCoexAssetsList(hasSfps: Boolean): List<Int> =
        if (hasSfps) {
            listOf(
                R.raw.biometricprompt_sfps_fingerprint_authenticating,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_authenticating,
                R.raw.biometricprompt_sfps_error_to_unlock,
                R.raw.biometricprompt_sfps_error_to_success,
                R.raw.biometricprompt_sfps_fingerprint_to_error,
                R.raw.biometricprompt_sfps_fingerprint_to_error_90,
                R.raw.biometricprompt_sfps_fingerprint_to_error_180,
                R.raw.biometricprompt_sfps_fingerprint_to_error_270,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_90,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_180,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_270,
                R.raw.biometricprompt_sfps_error_to_fingerprint,
                R.raw.biometricprompt_sfps_error_to_fingerprint_90,
                R.raw.biometricprompt_sfps_error_to_fingerprint_180,
                R.raw.biometricprompt_sfps_error_to_fingerprint_270,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_90,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_180,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_270,
                R.raw.biometricprompt_sfps_fingerprint_to_unlock,
                R.raw.biometricprompt_sfps_fingerprint_to_unlock_90,
                R.raw.biometricprompt_sfps_fingerprint_to_unlock_180,
                R.raw.biometricprompt_sfps_fingerprint_to_unlock_270,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_90,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_180,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_270,
                R.raw.biometricprompt_sfps_fingerprint_to_success,
                R.raw.biometricprompt_sfps_fingerprint_to_success_90,
                R.raw.biometricprompt_sfps_fingerprint_to_success_180,
                R.raw.biometricprompt_sfps_fingerprint_to_success_270,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_90,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_180,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_270,
            )
        } else {
            listOf(
                R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie,
                R.raw.fingerprint_dialogue_error_to_unlock_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_unlock_lottie,
                R.raw.fingerprint_dialogue_error_to_success_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_success_lottie,
                R.raw.fingerprint_dialogue_error_to_fingerprint_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_error_lottie
            )
        }

    /** Fingerprint iconView assets for caching */
    fun getFingerprintAssetsList(hasSfps: Boolean): List<Int> =
        if (hasSfps) {
            listOf(
                R.raw.biometricprompt_sfps_fingerprint_authenticating,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_authenticating,
                R.raw.biometricprompt_sfps_error_to_success,
                R.raw.biometricprompt_sfps_fingerprint_to_error,
                R.raw.biometricprompt_sfps_fingerprint_to_error_90,
                R.raw.biometricprompt_sfps_fingerprint_to_error_180,
                R.raw.biometricprompt_sfps_fingerprint_to_error_270,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_90,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_180,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_270,
                R.raw.biometricprompt_sfps_error_to_fingerprint,
                R.raw.biometricprompt_sfps_error_to_fingerprint_90,
                R.raw.biometricprompt_sfps_error_to_fingerprint_180,
                R.raw.biometricprompt_sfps_error_to_fingerprint_270,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_90,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_180,
                R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_270,
                R.raw.biometricprompt_sfps_fingerprint_to_success,
                R.raw.biometricprompt_sfps_fingerprint_to_success_90,
                R.raw.biometricprompt_sfps_fingerprint_to_success_180,
                R.raw.biometricprompt_sfps_fingerprint_to_success_270,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_90,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_180,
                R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_270,
            )
        } else {
            listOf(
                R.raw.fingerprint_dialogue_error_to_fingerprint_lottie,
                R.raw.fingerprint_dialogue_error_to_success_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_error_lottie,
                R.raw.fingerprint_dialogue_fingerprint_to_success_lottie
            )
        }

    /** Face iconView assets for caching */
    fun getFaceAssetsList(): List<Int> =
        listOf(
            R.raw.face_dialog_wink_from_dark,
            R.raw.face_dialog_dark_to_checkmark,
            R.raw.face_dialog_dark_to_error,
            R.raw.face_dialog_error_to_idle,
            R.raw.face_dialog_idle_static,
            R.raw.face_dialog_authenticating
        )

    private fun getSfpsAsset_fingerprintAuthenticating(isInRearDisplayMode: Boolean): Int =
        if (isInRearDisplayMode) {
            R.raw.biometricprompt_sfps_rear_display_fingerprint_authenticating
        } else {
            R.raw.biometricprompt_sfps_fingerprint_authenticating
        }

    private fun getSfpsAsset_fingerprintToError(
        rotation: DisplayRotation,
        isInRearDisplayMode: Boolean
    ): Int =
        if (isInRearDisplayMode) {
            when (rotation) {
                DisplayRotation.ROTATION_0 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error
                DisplayRotation.ROTATION_90 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_90
                DisplayRotation.ROTATION_180 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_180
                DisplayRotation.ROTATION_270 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error_270
            }
        } else {
            when (rotation) {
                DisplayRotation.ROTATION_0 -> R.raw.biometricprompt_sfps_fingerprint_to_error
                DisplayRotation.ROTATION_90 -> R.raw.biometricprompt_sfps_fingerprint_to_error_90
                DisplayRotation.ROTATION_180 -> R.raw.biometricprompt_sfps_fingerprint_to_error_180
                DisplayRotation.ROTATION_270 -> R.raw.biometricprompt_sfps_fingerprint_to_error_270
            }
        }

    private fun getSfpsAsset_errorToFingerprint(
        rotation: DisplayRotation,
        isInRearDisplayMode: Boolean
    ): Int =
        if (isInRearDisplayMode) {
            when (rotation) {
                DisplayRotation.ROTATION_0 ->
                    R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint
                DisplayRotation.ROTATION_90 ->
                    R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_90
                DisplayRotation.ROTATION_180 ->
                    R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_180
                DisplayRotation.ROTATION_270 ->
                    R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint_270
            }
        } else {
            when (rotation) {
                DisplayRotation.ROTATION_0 -> R.raw.biometricprompt_sfps_error_to_fingerprint
                DisplayRotation.ROTATION_90 -> R.raw.biometricprompt_sfps_error_to_fingerprint_90
                DisplayRotation.ROTATION_180 -> R.raw.biometricprompt_sfps_error_to_fingerprint_180
                DisplayRotation.ROTATION_270 -> R.raw.biometricprompt_sfps_error_to_fingerprint_270
            }
        }

    private fun getSfpsAsset_fingerprintToUnlock(
        rotation: DisplayRotation,
        isInRearDisplayMode: Boolean
    ): Int =
        if (isInRearDisplayMode) {
            when (rotation) {
                DisplayRotation.ROTATION_0 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock
                DisplayRotation.ROTATION_90 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_90
                DisplayRotation.ROTATION_180 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_180
                DisplayRotation.ROTATION_270 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock_270
            }
        } else {
            when (rotation) {
                DisplayRotation.ROTATION_0 -> R.raw.biometricprompt_sfps_fingerprint_to_unlock
                DisplayRotation.ROTATION_90 -> R.raw.biometricprompt_sfps_fingerprint_to_unlock_90
                DisplayRotation.ROTATION_180 -> R.raw.biometricprompt_sfps_fingerprint_to_unlock_180
                DisplayRotation.ROTATION_270 -> R.raw.biometricprompt_sfps_fingerprint_to_unlock_270
            }
        }

    private fun getSfpsAsset_fingerprintToSuccess(
        rotation: DisplayRotation,
        isInRearDisplayMode: Boolean
    ): Int =
        if (isInRearDisplayMode) {
            when (rotation) {
                DisplayRotation.ROTATION_0 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success
                DisplayRotation.ROTATION_90 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_90
                DisplayRotation.ROTATION_180 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_180
                DisplayRotation.ROTATION_270 ->
                    R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success_270
            }
        } else {
            when (rotation) {
                DisplayRotation.ROTATION_0 -> R.raw.biometricprompt_sfps_fingerprint_to_success
                DisplayRotation.ROTATION_90 -> R.raw.biometricprompt_sfps_fingerprint_to_success_90
                DisplayRotation.ROTATION_180 ->
                    R.raw.biometricprompt_sfps_fingerprint_to_success_180
                DisplayRotation.ROTATION_270 ->
                    R.raw.biometricprompt_sfps_fingerprint_to_success_270
            }
        }
}
