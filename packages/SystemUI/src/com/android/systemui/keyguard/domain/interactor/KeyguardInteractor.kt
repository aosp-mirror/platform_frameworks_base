/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.app.StatusBarManager
import android.graphics.Point
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeStateModel.Companion.isDozeOff
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessModel.Companion.isWakingOrStartingToWake
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Encapsulates business-logic related to the keyguard but not to a more specific part within it.
 */
@SysUISingleton
class KeyguardInteractor
@Inject
constructor(
    private val repository: KeyguardRepository,
    private val commandQueue: CommandQueue,
    featureFlags: FeatureFlags,
    bouncerRepository: KeyguardBouncerRepository,
) {
    /**
     * The amount of doze the system is in, where `1.0` is fully dozing and `0.0` is not dozing at
     * all.
     */
    val dozeAmount: Flow<Float> = repository.linearDozeAmount
    /** Whether the system is in doze mode. */
    val isDozing: Flow<Boolean> = repository.isDozing
    /** Whether Always-on Display mode is available. */
    val isAodAvailable: Flow<Boolean> = repository.isAodAvailable
    /** Doze transition information. */
    val dozeTransitionModel: Flow<DozeTransitionModel> = repository.dozeTransitionModel
    /**
     * Whether the system is dreaming. [isDreaming] will be always be true when [isDozing] is true,
     * but not vice-versa.
     */
    val isDreaming: Flow<Boolean> = repository.isDreaming
    /** Whether the system is dreaming with an overlay active */
    val isDreamingWithOverlay: Flow<Boolean> = repository.isDreamingWithOverlay
    /** Event for when the camera gesture is detected */
    val onCameraLaunchDetected: Flow<CameraLaunchSourceModel> = conflatedCallbackFlow {
        val callback =
            object : CommandQueue.Callbacks {
                override fun onCameraLaunchGestureDetected(source: Int) {
                    trySendWithFailureLogging(
                        cameraLaunchSourceIntToModel(source),
                        TAG,
                        "updated onCameraLaunchGestureDetected"
                    )
                }
            }

        commandQueue.addCallback(callback)

        awaitClose { commandQueue.removeCallback(callback) }
    }

    /** The device wake/sleep state */
    val wakefulnessModel: Flow<WakefulnessModel> = repository.wakefulness

    /**
     * Dozing and dreaming have overlapping events. If the doze state remains in FINISH, it means
     * that doze mode is not running and DREAMING is ok to commence.
     *
     * Allow a brief moment to prevent rapidly oscillating between true/false signals.
     */
    val isAbleToDream: Flow<Boolean> =
        merge(isDreaming, isDreamingWithOverlay)
            .combine(
                dozeTransitionModel,
                { isDreaming, dozeTransitionModel ->
                    isDreaming && isDozeOff(dozeTransitionModel.to)
                }
            )
            .sample(
                wakefulnessModel,
                { isAbleToDream, wakefulnessModel ->
                    isAbleToDream && isWakingOrStartingToWake(wakefulnessModel)
                }
            )
            .flatMapLatest { isAbleToDream ->
                flow {
                    delay(50)
                    emit(isAbleToDream)
                }
            }
            .distinctUntilChanged()

    /** Whether the keyguard is showing or not. */
    val isKeyguardShowing: Flow<Boolean> = repository.isKeyguardShowing
    /** Whether the keyguard is unlocked or not. */
    val isKeyguardUnlocked: Flow<Boolean> = repository.isKeyguardUnlocked
    /** Whether the keyguard is occluded (covered by an activity). */
    val isKeyguardOccluded: Flow<Boolean> = repository.isKeyguardOccluded
    /** Whether the keyguard is going away. */
    val isKeyguardGoingAway: Flow<Boolean> = repository.isKeyguardGoingAway
    /** Whether the primary bouncer is showing or not. */
    val primaryBouncerShowing: Flow<Boolean> = bouncerRepository.primaryBouncerShow
    /** Whether the alternate bouncer is showing or not. */
    val alternateBouncerShowing: Flow<Boolean> = bouncerRepository.alternateBouncerVisible
    /** Observable for the [StatusBarState] */
    val statusBarState: Flow<StatusBarState> = repository.statusBarState
    /** Whether or not quick settings or quick quick settings are showing. */
    val isQuickSettingsVisible: Flow<Boolean> = repository.isQuickSettingsVisible
    /**
     * Observable for [BiometricUnlockModel] when biometrics like face or any fingerprint (rear,
     * side, under display) is used to unlock the device.
     */
    val biometricUnlockState: Flow<BiometricUnlockModel> = repository.biometricUnlockState

    /** Keyguard is present and is not occluded. */
    val isKeyguardVisible: Flow<Boolean> =
        combine(isKeyguardShowing, isKeyguardOccluded) { showing, occluded -> showing && !occluded }

    /** Whether camera is launched over keyguard. */
    var isSecureCameraActive =
        if (featureFlags.isEnabled(Flags.FACE_AUTH_REFACTOR)) {
            combine(
                    isKeyguardVisible,
                    primaryBouncerShowing,
                    onCameraLaunchDetected,
                ) { isKeyguardVisible, isPrimaryBouncerShowing, cameraLaunchEvent ->
                    when {
                        isKeyguardVisible -> false
                        isPrimaryBouncerShowing -> false
                        else -> cameraLaunchEvent == CameraLaunchSourceModel.POWER_DOUBLE_TAP
                    }
                }
                .onStart { emit(false) }
        } else {
            flowOf(false)
        }

    /** The approximate location on the screen of the fingerprint sensor, if one is available. */
    val fingerprintSensorLocation: Flow<Point?> = repository.fingerprintSensorLocation

    /** The approximate location on the screen of the face unlock sensor, if one is available. */
    val faceSensorLocation: Flow<Point?> = repository.faceSensorLocation

    fun dozeTransitionTo(vararg states: DozeStateModel): Flow<DozeTransitionModel> {
        return dozeTransitionModel.filter { states.contains(it.to) }
    }
    fun isKeyguardShowing(): Boolean {
        return repository.isKeyguardShowing()
    }

    private fun cameraLaunchSourceIntToModel(value: Int): CameraLaunchSourceModel {
        return when (value) {
            StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE -> CameraLaunchSourceModel.WIGGLE
            StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP ->
                CameraLaunchSourceModel.POWER_DOUBLE_TAP
            StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER ->
                CameraLaunchSourceModel.LIFT_TRIGGER
            StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE ->
                CameraLaunchSourceModel.QUICK_AFFORDANCE
            else -> throw IllegalArgumentException("Invalid CameraLaunchSourceModel value: $value")
        }
    }

    /** Sets whether quick settings or quick-quick settings is visible. */
    fun setQuickSettingsVisible(isVisible: Boolean) {
        repository.setQuickSettingsVisible(isVisible)
    }

    companion object {
        private const val TAG = "KeyguardInteractor"
    }
}
