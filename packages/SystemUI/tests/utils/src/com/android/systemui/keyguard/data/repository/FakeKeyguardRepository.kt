/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import android.graphics.Point
import com.android.systemui.common.shared.model.Position
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [KeyguardRepository] */
class FakeKeyguardRepository : KeyguardRepository {

    private val _animateBottomAreaDozingTransitions = MutableStateFlow(false)
    override val animateBottomAreaDozingTransitions: StateFlow<Boolean> =
        _animateBottomAreaDozingTransitions

    private val _bottomAreaAlpha = MutableStateFlow(1f)
    override val bottomAreaAlpha: StateFlow<Float> = _bottomAreaAlpha

    private val _clockPosition = MutableStateFlow(Position(0, 0))
    override val clockPosition: StateFlow<Position> = _clockPosition

    private val _isKeyguardShowing = MutableStateFlow(false)
    override val isKeyguardShowing: Flow<Boolean> = _isKeyguardShowing

    private val _isKeyguardUnlocked = MutableStateFlow(false)
    override val isKeyguardUnlocked: Flow<Boolean> = _isKeyguardUnlocked

    private val _isKeyguardOccluded = MutableStateFlow(false)
    override val isKeyguardOccluded: Flow<Boolean> = _isKeyguardOccluded

    private val _isDozing = MutableStateFlow(false)
    override val isDozing: Flow<Boolean> = _isDozing

    private val _isAodAvailable = MutableStateFlow(false)
    override val isAodAvailable: Flow<Boolean> = _isAodAvailable

    private val _isDreaming = MutableStateFlow(false)
    override val isDreaming: Flow<Boolean> = _isDreaming

    private val _isDreamingWithOverlay = MutableStateFlow(false)
    override val isDreamingWithOverlay: Flow<Boolean> = _isDreamingWithOverlay

    private val _dozeAmount = MutableStateFlow(0f)
    override val linearDozeAmount: Flow<Float> = _dozeAmount

    private val _statusBarState = MutableStateFlow(StatusBarState.SHADE)
    override val statusBarState: Flow<StatusBarState> = _statusBarState

    private val _dozeTransitionModel = MutableStateFlow(DozeTransitionModel())
    override val dozeTransitionModel: Flow<DozeTransitionModel> = _dozeTransitionModel

    private val _wakefulnessModel =
        MutableStateFlow(
            WakefulnessModel(
                WakefulnessState.ASLEEP,
                false,
                WakeSleepReason.OTHER,
                WakeSleepReason.OTHER
            )
        )
    override val wakefulness: Flow<WakefulnessModel> = _wakefulnessModel

    private val _isUdfpsSupported = MutableStateFlow(false)

    private val _isKeyguardGoingAway = MutableStateFlow(false)
    override val isKeyguardGoingAway: Flow<Boolean> = _isKeyguardGoingAway

    private val _biometricUnlockState = MutableStateFlow(BiometricUnlockModel.NONE)
    override val biometricUnlockState: Flow<BiometricUnlockModel> = _biometricUnlockState

    private val _fingerprintSensorLocation = MutableStateFlow<Point?>(null)
    override val fingerprintSensorLocation: Flow<Point?> = _fingerprintSensorLocation

    private val _faceSensorLocation = MutableStateFlow<Point?>(null)
    override val faceSensorLocation: Flow<Point?> = _faceSensorLocation

    private val _biometricUnlockSource = MutableStateFlow<BiometricUnlockSource?>(null)
    override val biometricUnlockSource: Flow<BiometricUnlockSource?> = _biometricUnlockSource

    private val _isQuickSettingsVisible = MutableStateFlow(false)
    override val isQuickSettingsVisible: Flow<Boolean> = _isQuickSettingsVisible.asStateFlow()

    override fun setQuickSettingsVisible(isVisible: Boolean) {
        _isQuickSettingsVisible.value = isVisible
    }

    override fun isKeyguardShowing(): Boolean {
        return _isKeyguardShowing.value
    }

    override fun setAnimateDozingTransitions(animate: Boolean) {
        _animateBottomAreaDozingTransitions.tryEmit(animate)
    }

    override fun setBottomAreaAlpha(alpha: Float) {
        _bottomAreaAlpha.value = alpha
    }

    override fun setClockPosition(x: Int, y: Int) {
        _clockPosition.value = Position(x, y)
    }

    fun setKeyguardShowing(isShowing: Boolean) {
        _isKeyguardShowing.value = isShowing
    }

    fun setKeyguardOccluded(isOccluded: Boolean) {
        _isKeyguardOccluded.value = isOccluded
    }

    fun setDozing(isDozing: Boolean) {
        _isDozing.value = isDozing
    }

    fun setAodAvailable(isAodAvailable: Boolean) {
        _isAodAvailable.value = isAodAvailable
    }

    fun setDreamingWithOverlay(isDreaming: Boolean) {
        _isDreamingWithOverlay.value = isDreaming
    }

    fun setDozeAmount(dozeAmount: Float) {
        _dozeAmount.value = dozeAmount
    }

    fun setWakefulnessModel(model: WakefulnessModel) {
        _wakefulnessModel.value = model
    }

    fun setBiometricUnlockState(state: BiometricUnlockModel) {
        _biometricUnlockState.tryEmit(state)
    }

    fun setBiometricUnlockSource(source: BiometricUnlockSource?) {
        _biometricUnlockSource.tryEmit(source)
    }

    fun setFaceSensorLocation(location: Point?) {
        _faceSensorLocation.tryEmit(location)
    }

    fun setFingerprintSensorLocation(location: Point?) {
        _fingerprintSensorLocation.tryEmit(location)
    }

    fun setDozeTransitionModel(model: DozeTransitionModel) {
        _dozeTransitionModel.value = model
    }

    fun setStatusBarState(state: StatusBarState) {
        _statusBarState.value = state
    }

    override fun isUdfpsSupported(): Boolean {
        return _isUdfpsSupported.value
    }
}
