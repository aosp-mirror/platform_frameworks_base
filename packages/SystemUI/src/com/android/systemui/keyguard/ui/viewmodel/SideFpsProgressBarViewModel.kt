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
package com.android.systemui.keyguard.ui.viewmodel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import androidx.annotation.VisibleForTesting
import androidx.core.animation.addListener
import com.android.systemui.Flags
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.SideFpsSensorInteractor
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.isDefaultOrientation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.DozeServiceHost
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
@SysUISingleton
class SideFpsProgressBarViewModel
@Inject
constructor(
    private val context: Context,
    biometricStatusInteractor: BiometricStatusInteractor,
    deviceEntryFingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    private val sfpsSensorInteractor: SideFpsSensorInteractor,
    // todo (b/317432075) Injecting DozeServiceHost directly instead of using it through
    //  DozeInteractor as DozeServiceHost already depends on DozeInteractor.
    private val dozeServiceHost: DozeServiceHost,
    private val keyguardInteractor: KeyguardInteractor,
    displayStateInteractor: DisplayStateInteractor,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application private val applicationScope: CoroutineScope,
    private val powerInteractor: PowerInteractor,
) {
    private val _progress = MutableStateFlow(0.0f)
    private val _visible = MutableStateFlow(false)
    private var _animator: ValueAnimator? = null
    private var animatorJob: Job? = null

    private fun onFingerprintCaptureCompleted() {
        _visible.value = false
        _progress.value = 0.0f
    }

    // Merged [FingerprintAuthenticationStatus] from BiometricPrompt acquired messages and
    // device entry authentication messages
    private val mergedFingerprintAuthenticationStatus =
        merge(
                biometricStatusInteractor.fingerprintAcquiredStatus,
                deviceEntryFingerprintAuthInteractor.authenticationStatus
            )
            .filter {
                if (it is AcquiredFingerprintAuthenticationStatus) {
                    it.authenticationReason == AuthenticationReason.DeviceEntryAuthentication ||
                        it.authenticationReason ==
                            AuthenticationReason.BiometricPromptAuthentication
                } else {
                    true
                }
            }

    val isVisible: Flow<Boolean> = _visible.asStateFlow()

    val progress: Flow<Float> = _progress.asStateFlow()

    val progressBarLength: Flow<Int> =
        sfpsSensorInteractor.sensorLocation.map { it.length }.distinctUntilChanged()

    val progressBarThickness =
        context.resources.getDimension(R.dimen.sfps_progress_bar_thickness).toInt()

    val progressBarLocation =
        combine(displayStateInteractor.currentRotation, sfpsSensorInteractor.sensorLocation, ::Pair)
            .map { (rotation, sensorLocation) ->
                val paddingFromEdge =
                    context.resources
                        .getDimension(R.dimen.sfps_progress_bar_padding_from_edge)
                        .toInt()
                val viewLeftTop = Point(sensorLocation.left, sensorLocation.top)
                val totalDistanceFromTheEdge = paddingFromEdge + progressBarThickness

                val isSensorVerticalNow =
                    sensorLocation.isSensorVerticalInDefaultOrientation ==
                        rotation.isDefaultOrientation()
                if (isSensorVerticalNow) {
                    // Sensor is vertical to the current orientation, we rotate it 270 deg
                    // around the (left,top) point as the pivot. We need to push it down the
                    // length of the progress bar so that it is still aligned to the sensor
                    viewLeftTop.y += sensorLocation.length
                    val isSensorOnTheNearEdge =
                        rotation == DisplayRotation.ROTATION_180 ||
                            rotation == DisplayRotation.ROTATION_90
                    if (isSensorOnTheNearEdge) {
                        // Add just the padding from the edge to push the progress bar right
                        viewLeftTop.x += paddingFromEdge
                    } else {
                        // View left top is pushed left from the edge by the progress bar thickness
                        // and the padding.
                        viewLeftTop.x -= totalDistanceFromTheEdge
                    }
                } else {
                    // Sensor is horizontal to the current orientation.
                    val isSensorOnTheNearEdge =
                        rotation == DisplayRotation.ROTATION_0 ||
                            rotation == DisplayRotation.ROTATION_90
                    if (isSensorOnTheNearEdge) {
                        // Add just the padding from the edge to push the progress bar down
                        viewLeftTop.y += paddingFromEdge
                    } else {
                        // Sensor is now at the bottom edge of the device in the current rotation.
                        // We want to push it up from the bottom edge by the padding and
                        // the thickness of the progressbar.
                        viewLeftTop.y -= totalDistanceFromTheEdge
                    }
                }
                viewLeftTop
            }

    val isFingerprintAuthRunning: Flow<Boolean> =
        combine(
            deviceEntryFingerprintAuthInteractor.isRunning,
            biometricStatusInteractor.sfpsAuthenticationReason
        ) { deviceEntryAuthIsRunning, sfpsAuthReason ->
            deviceEntryAuthIsRunning ||
                sfpsAuthReason == AuthenticationReason.BiometricPromptAuthentication
        }

    val rotation: Flow<Float> =
        combine(displayStateInteractor.currentRotation, sfpsSensorInteractor.sensorLocation, ::Pair)
            .map { (rotation, sensorLocation) ->
                if (
                    rotation.isDefaultOrientation() ==
                        sensorLocation.isSensorVerticalInDefaultOrientation
                ) {
                    // We should rotate the progress bar 270 degrees in the clockwise direction with
                    // the left top point as the pivot so that it fills up from bottom to top
                    270.0f
                } else {
                    0.0f
                }
            }

    val isProlongedTouchRequiredForAuthentication: Flow<Boolean> =
        sfpsSensorInteractor.isProlongedTouchRequiredForAuthentication

    init {
        if (Flags.restToUnlock()) {
            launchAnimator()
        }
    }

    private fun launchAnimator() {
        applicationScope.launch {
            sfpsSensorInteractor.isProlongedTouchRequiredForAuthentication.collectLatest { enabled
                ->
                if (!enabled) {
                    animatorJob?.cancel()
                    return@collectLatest
                }
                animatorJob =
                    sfpsSensorInteractor.authenticationDuration
                        .flatMapLatest { authDuration ->
                            _animator?.cancel()
                            mergedFingerprintAuthenticationStatus.map {
                                authStatus: FingerprintAuthenticationStatus ->
                                when (authStatus) {
                                    is AcquiredFingerprintAuthenticationStatus -> {
                                        if (authStatus.fingerprintCaptureStarted) {
                                            if (keyguardInteractor.isDozing.value) {
                                                dozeServiceHost.fireSideFpsAcquisitionStarted()
                                            } else {
                                                powerInteractor
                                                    .wakeUpForSideFingerprintAcquisition()
                                            }
                                            _animator?.cancel()
                                            _animator =
                                                ValueAnimator.ofFloat(0.0f, 1.0f)
                                                    .setDuration(authDuration)
                                                    .apply {
                                                        addUpdateListener {
                                                            _progress.value =
                                                                it.animatedValue as Float
                                                        }
                                                        addListener(
                                                            onEnd = {
                                                                if (_progress.value == 0.0f) {
                                                                    _visible.value = false
                                                                }
                                                            },
                                                            onStart = { _visible.value = true },
                                                            onCancel = { _visible.value = false }
                                                        )
                                                    }
                                            _animator?.start()
                                        } else if (authStatus.fingerprintCaptureCompleted) {
                                            onFingerprintCaptureCompleted()
                                        } else {
                                            // Abandoned FP Auth attempt
                                            _animator?.reverse()
                                        }
                                    }
                                    is ErrorFingerprintAuthenticationStatus ->
                                        onFingerprintCaptureCompleted()
                                    is FailFingerprintAuthenticationStatus ->
                                        onFingerprintCaptureCompleted()
                                    is SuccessFingerprintAuthenticationStatus ->
                                        onFingerprintCaptureCompleted()
                                    else -> Unit
                                }
                            }
                        }
                        .flowOn(mainDispatcher)
                        .onCompletion { _animator?.cancel() }
                        .launchIn(applicationScope)
            }
        }
    }

    @VisibleForTesting
    fun setVisible(isVisible: Boolean) {
        _visible.value = isVisible
    }
}
