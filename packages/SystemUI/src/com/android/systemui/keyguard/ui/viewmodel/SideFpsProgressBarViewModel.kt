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
import android.graphics.Point
import androidx.core.animation.doOnEnd
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.SideFpsSensorInteractor
import com.android.systemui.biometrics.shared.model.isDefaultOrientation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SysUISingleton
class SideFpsProgressBarViewModel
@Inject
constructor(
    private val fpAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val sfpsSensorInteractor: SideFpsSensorInteractor,
    displayStateInteractor: DisplayStateInteractor,
    @Application private val applicationScope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(0.0f)
    private val _visible = MutableStateFlow(false)
    private var _animator: ValueAnimator? = null

    private fun onFingerprintCaptureCompleted() {
        _visible.value = false
        _progress.value = 0.0f
    }

    val isVisible: Flow<Boolean> = _visible.asStateFlow()

    val progress: Flow<Float> = _progress.asStateFlow()

    val sensorWidth: Flow<Int> = sfpsSensorInteractor.sensorLocation.map { it.width }

    val sensorLocation: Flow<Point> =
        sfpsSensorInteractor.sensorLocation.map { Point(it.left, it.top) }

    val isFingerprintAuthRunning: Flow<Boolean> = fpAuthRepository.isRunning

    val shouldRotate90Degrees: Flow<Boolean> =
        combine(displayStateInteractor.currentRotation, sfpsSensorInteractor.sensorLocation, ::Pair)
            .map { (rotation, sensorLocation) ->
                if (rotation.isDefaultOrientation()) {
                    sensorLocation.isSensorVerticalInDefaultOrientation
                } else {
                    !sensorLocation.isSensorVerticalInDefaultOrientation
                }
            }

    val isProlongedTouchRequiredForAuthentication: Flow<Boolean> =
        sfpsSensorInteractor.isProlongedTouchRequiredForAuthentication

    init {
        applicationScope.launch {
            combine(
                    sfpsSensorInteractor.isProlongedTouchRequiredForAuthentication,
                    sfpsSensorInteractor.authenticationDuration,
                    ::Pair
                )
                .collectLatest { (enabled, authDuration) ->
                    if (!enabled) return@collectLatest

                    launch {
                        fpAuthRepository.authenticationStatus.collectLatest { authStatus ->
                            when (authStatus) {
                                is AcquiredFingerprintAuthenticationStatus -> {
                                    if (authStatus.fingerprintCaptureStarted) {

                                        _visible.value = true
                                        _animator?.cancel()
                                        _animator =
                                            ValueAnimator.ofFloat(0.0f, 1.0f)
                                                .setDuration(authDuration)
                                                .apply {
                                                    addUpdateListener {
                                                        _progress.value = it.animatedValue as Float
                                                    }
                                                    addListener(
                                                        doOnEnd {
                                                            if (_progress.value == 0.0f) {
                                                                _visible.value = false
                                                            }
                                                        }
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
                }
        }
    }
}
