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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.domain.interactor

import android.app.StatusBarManager
import android.graphics.Point
import android.util.MathUtils
import com.android.app.animation.Interpolators
import com.android.systemui.R
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.Position
import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeStateModel.Companion.isDozeOff
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardRootViewVisibilityState
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.ScreenModel
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    configurationRepository: ConfigurationRepository,
    shadeRepository: ShadeRepository,
    sceneInteractorProvider: Provider<SceneInteractor>,
) {
    /** Position information for the shared notification container. */
    val sharedNotificationContainerPosition =
        MutableStateFlow(SharedNotificationContainerPosition())

    /**
     * The amount of doze the system is in, where `1.0` is fully dozing and `0.0` is not dozing at
     * all.
     */
    val dozeAmount: Flow<Float> = repository.linearDozeAmount

    /** Whether the system is in doze mode. */
    val isDozing: Flow<Boolean> = repository.isDozing

    /** Receive an event for doze time tick */
    val dozeTimeTick: Flow<Long> = repository.dozeTimeTick

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

    /** Whether the system is dreaming and the active dream is hosted in lockscreen */
    val isActiveDreamLockscreenHosted: StateFlow<Boolean> = repository.isActiveDreamLockscreenHosted

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
    val wakefulnessModel: StateFlow<WakefulnessModel> = repository.wakefulness

    /** The device screen state */
    val screenModel: StateFlow<ScreenModel> = repository.screenModel

    /**
     * Dozing and dreaming have overlapping events. If the doze state remains in FINISH, it means
     * that doze mode is not running and DREAMING is ok to commence.
     *
     * Allow a brief moment to prevent rapidly oscillating between true/false signals.
     */
    val isAbleToDream: Flow<Boolean> =
        merge(isDreaming, isDreamingWithOverlay)
            .combine(dozeTransitionModel) { isDreaming, dozeTransitionModel ->
                isDreaming && isDozeOff(dozeTransitionModel.to)
            }
            .sample(wakefulnessModel) { isAbleToDream, wakefulnessModel ->
                isAbleToDream && wakefulnessModel.isStartingToWakeOrAwake()
            }
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

    /**
     * Observable for [BiometricUnlockModel] when biometrics like face or any fingerprint (rear,
     * side, under display) is used to unlock the device.
     */
    val biometricUnlockState: Flow<BiometricUnlockModel> = repository.biometricUnlockState

    /** Keyguard is present and is not occluded. */
    val isKeyguardVisible: Flow<Boolean> =
        combine(isKeyguardShowing, isKeyguardOccluded) { showing, occluded -> showing && !occluded }

    /** Whether camera is launched over keyguard. */
    val isSecureCameraActive: Flow<Boolean> by lazy {
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
    }

    /** The approximate location on the screen of the fingerprint sensor, if one is available. */
    val fingerprintSensorLocation: Flow<Point?> = repository.fingerprintSensorLocation

    /** The approximate location on the screen of the face unlock sensor, if one is available. */
    val faceSensorLocation: Flow<Point?> = repository.faceSensorLocation

    /** Notifies when a new configuration is set */
    val configurationChange: Flow<Unit> = configurationRepository.onAnyConfigurationChange

    /** Represents the current state of the KeyguardRootView visibility */
    val keyguardRootViewVisibilityState: Flow<KeyguardRootViewVisibilityState> =
        repository.keyguardRootViewVisibility

    /** The position of the keyguard clock. */
    val clockPosition: Flow<Position> = repository.clockPosition

    val keyguardAlpha: Flow<Float> = repository.keyguardAlpha

    val keyguardTranslationY: Flow<Float> =
        configurationChange.flatMapLatest {
            val translationDistance =
                configurationRepository.getDimensionPixelSize(
                    R.dimen.keyguard_translate_distance_on_swipe_up
                )
            shadeRepository.shadeModel.map {
                // On swipe up, translate the keyguard to reveal the bouncer
                MathUtils.lerp(
                    translationDistance,
                    0,
                    Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(it.expansionAmount)
                )
            }
        }

    /** Whether to animate the next doze mode transition. */
    val animateDozingTransitions: Flow<Boolean> by lazy {
        if (featureFlags.isEnabled(Flags.SCENE_CONTAINER)) {
            sceneInteractorProvider
                .get()
                .transitioningTo
                .map { it == SceneKey.Lockscreen }
                .distinctUntilChanged()
                .flatMapLatest { isTransitioningToLockscreenScene ->
                    if (isTransitioningToLockscreenScene) {
                        flowOf(false)
                    } else {
                        repository.animateBottomAreaDozingTransitions
                    }
                }
        } else {
            repository.animateBottomAreaDozingTransitions
        }
    }

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

    fun setIsActiveDreamLockscreenHosted(isLockscreenHosted: Boolean) {
        repository.setIsActiveDreamLockscreenHosted(isLockscreenHosted)
    }

    /** Sets whether quick settings or quick-quick settings is visible. */
    fun setQuickSettingsVisible(isVisible: Boolean) {
        repository.setQuickSettingsVisible(isVisible)
    }

    fun setKeyguardRootVisibility(
        statusBarState: Int,
        goingToFullShade: Boolean,
        isOcclusionTransitionRunning: Boolean
    ) {
        repository.setKeyguardVisibility(
            statusBarState,
            goingToFullShade,
            isOcclusionTransitionRunning
        )
    }

    fun setClockPosition(x: Int, y: Int) {
        repository.setClockPosition(x, y)
    }

    fun setAlpha(alpha: Float) {
        repository.setKeyguardAlpha(alpha)
    }

    fun setAnimateDozingTransitions(animate: Boolean) {
        repository.setAnimateDozingTransitions(animate)
    }

    fun isKeyguardDismissable(): Boolean {
        return repository.isKeyguardUnlocked.value
    }

    companion object {
        private const val TAG = "KeyguardInteractor"

        fun isKeyguardVisibleInState(state: KeyguardState): Boolean {
            return when (state) {
                KeyguardState.OFF -> true
                KeyguardState.DOZING -> true
                KeyguardState.DREAMING -> true
                KeyguardState.AOD -> true
                KeyguardState.ALTERNATE_BOUNCER -> true
                KeyguardState.PRIMARY_BOUNCER -> true
                KeyguardState.LOCKSCREEN -> true
                KeyguardState.GONE -> false
                KeyguardState.OCCLUDED -> true
                KeyguardState.DREAMING_LOCKSCREEN_HOSTED -> false
            }
        }
    }
}
