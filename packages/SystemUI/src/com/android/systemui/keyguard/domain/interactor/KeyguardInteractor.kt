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
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.CameraLaunchSourceModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeStateModel.Companion.isDozeOff
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.notification.NotificationUtils.interpolate
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/**
 * Encapsulates business-logic related to the keyguard but not to a more specific part within it.
 */
@SysUISingleton
class KeyguardInteractor
@Inject
constructor(
    private val repository: KeyguardRepository,
    private val commandQueue: CommandQueue,
    powerInteractor: PowerInteractor,
    bouncerRepository: KeyguardBouncerRepository,
    configurationInteractor: ConfigurationInteractor,
    shadeRepository: ShadeRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    sceneInteractorProvider: Provider<SceneInteractor>,
    private val fromGoneTransitionInteractor: Provider<FromGoneTransitionInteractor>,
    private val fromLockscreenTransitionInteractor: Provider<FromLockscreenTransitionInteractor>,
    sharedNotificationContainerInteractor: Provider<SharedNotificationContainerInteractor>,
    @Application applicationScope: CoroutineScope,
) {
    // TODO(b/296118689): move to a repository
    private val _notificationPlaceholderBounds = MutableStateFlow(NotificationContainerBounds())

    // When going to AOD, we interpolate bounds when receiving the new bounds
    // When going back to LS, we'll apply new bounds directly
    private val _nonSplitShadeNotifciationPlaceholderBounds =
        _notificationPlaceholderBounds.pairwise().flatMapLatest { (oldBounds, newBounds) ->
            val lastChangeStep = keyguardTransitionInteractor.transitionState.first()
            if (lastChangeStep.to == AOD) {
                keyguardTransitionInteractor.transitionState.map { step ->
                    val startingProgress = lastChangeStep.value
                    val progress = step.value
                    if (step.to == AOD && progress >= startingProgress && startingProgress < 1f) {
                        val adjustedProgress =
                            ((progress - startingProgress) / (1F - startingProgress)).coerceIn(
                                0F,
                                1F
                            )
                        val top = interpolate(oldBounds.top, newBounds.top, adjustedProgress)
                        val bottom =
                            interpolate(
                                oldBounds.bottom,
                                newBounds.bottom,
                                adjustedProgress.coerceIn(0F, 1F)
                            )
                        NotificationContainerBounds(top = top, bottom = bottom)
                    } else {
                        newBounds
                    }
                }
            } else {
                flow { emit(newBounds) }
            }
        }

    /** Bounds of the notification container. */
    val notificationContainerBounds: StateFlow<NotificationContainerBounds> by lazy {
        SceneContainerFlag.assertInLegacyMode()
        combine(
                _notificationPlaceholderBounds,
                _nonSplitShadeNotifciationPlaceholderBounds,
                sharedNotificationContainerInteractor.get().configurationBasedDimensions,
            ) { bounds, nonSplitShadeBounds: NotificationContainerBounds, cfg ->
                // We offset the placeholder bounds by the configured top margin to account for
                // legacy placement behavior within notifications for splitshade.
                if (MigrateClocksToBlueprint.isEnabled) {
                    if (cfg.useSplitShade) {
                        bounds.copy(bottom = bounds.bottom - cfg.keyguardSplitShadeTopMargin)
                    } else {
                        nonSplitShadeBounds
                    }
                } else bounds
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = NotificationContainerBounds(),
            )
    }

    fun setNotificationContainerBounds(position: NotificationContainerBounds) {
        SceneContainerFlag.assertInLegacyMode()
        _notificationPlaceholderBounds.value = position
    }

    /**
     * The amount of doze the system is in, where `1.0` is fully dozing and `0.0` is not dozing at
     * all.
     */
    val dozeAmount: Flow<Float> = repository.linearDozeAmount

    /** Whether the system is in doze mode. */
    val isDozing: StateFlow<Boolean> = repository.isDozing

    /** Receive an event for doze time tick */
    val dozeTimeTick: Flow<Long> = repository.dozeTimeTick

    /** Whether Always-on Display mode is available. */
    val isAodAvailable: StateFlow<Boolean> = repository.isAodAvailable

    /** Doze transition information. */
    val dozeTransitionModel: Flow<DozeTransitionModel> = repository.dozeTransitionModel

    val isPulsing: Flow<Boolean> = dozeTransitionModel.map { it.to == DozeStateModel.DOZE_PULSING }

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
            .sample(powerInteractor.isAwake) { isAbleToDream, isAwake -> isAbleToDream && isAwake }
            .debounce(50L)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether the keyguard is showing or not. */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState")
    val isKeyguardShowing: Flow<Boolean> = repository.isKeyguardShowing

    /** Whether the keyguard is dismissible or not. */
    val isKeyguardDismissible: StateFlow<Boolean> = repository.isKeyguardDismissible

    /** Whether the keyguard is occluded (covered by an activity). */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState.OCCLUDED")
    val isKeyguardOccluded: Flow<Boolean> = repository.isKeyguardOccluded

    /** Whether the keyguard is going away. */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState.GONE")
    val isKeyguardGoingAway: Flow<Boolean> = repository.isKeyguardGoingAway

    /** Keyguard can be clipped at the top as the shade is dragged */
    val topClippingBounds: Flow<Int?> =
        combineTransform(
                keyguardTransitionInteractor
                    .transitionValue(scene = Scenes.Gone, stateWithoutSceneContainer = GONE)
                    .map { it == 1f }
                    .onStart { emit(false) }
                    .distinctUntilChanged(),
                repository.topClippingBounds
            ) { isGone, topClippingBounds ->
                if (!isGone) {
                    emit(topClippingBounds)
                }
            }
            .distinctUntilChanged()

    /** Last point that [KeyguardRootView] view was tapped */
    val lastRootViewTapPosition: Flow<Point?> = repository.lastRootViewTapPosition.asStateFlow()

    /** Is the ambient indication area visible? */
    val ambientIndicationVisible: Flow<Boolean> = repository.ambientIndicationVisible.asStateFlow()

    /** Whether the primary bouncer is showing or not. */
    @JvmField val primaryBouncerShowing: Flow<Boolean> = bouncerRepository.primaryBouncerShow

    /** Whether the alternate bouncer is showing or not. */
    val alternateBouncerShowing: Flow<Boolean> =
        bouncerRepository.alternateBouncerVisible.sample(isAbleToDream) {
            alternateBouncerVisible,
            isAbleToDream ->
            if (isAbleToDream) {
                // If the alternate bouncer will show over a dream, it is likely that the dream has
                // requested a dismissal, which will stop the dream. By delaying this slightly, the
                // DREAMING->LOCKSCREEN transition will now happen first, followed by
                // LOCKSCREEN->ALTERNATE_BOUNCER.
                delay(600L)
            }
            alternateBouncerVisible
        }

    /** Observable for the [StatusBarState] */
    val statusBarState: Flow<StatusBarState> = repository.statusBarState

    /** Observable for [BiometricUnlockModel] when biometrics are used to unlock the device. */
    val biometricUnlockState: Flow<BiometricUnlockModel> = repository.biometricUnlockState

    /** Keyguard is present and is not occluded. */
    val isKeyguardVisible: Flow<Boolean> =
        combine(isKeyguardShowing, isKeyguardOccluded) { showing, occluded -> showing && !occluded }

    /** Whether camera is launched over keyguard. */
    val isSecureCameraActive: Flow<Boolean> by lazy {
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
    }

    /** The approximate location on the screen of the fingerprint sensor, if one is available. */
    val fingerprintSensorLocation: Flow<Point?> = repository.fingerprintSensorLocation

    /** The approximate location on the screen of the face unlock sensor, if one is available. */
    val faceSensorLocation: Flow<Point?> = repository.faceSensorLocation

    @Deprecated("Use the relevant TransitionViewModel")
    val keyguardAlpha: Flow<Float> = repository.keyguardAlpha

    /** Temporary shim for fading out content when the brightness slider is used */
    @Deprecated("SceneContainer uses NotificationStackAppearanceInteractor")
    val panelAlpha: StateFlow<Float> = repository.panelAlpha.asStateFlow()

    /**
     * When the lockscreen can be dismissed, emit an alpha value as the user swipes up. This is
     * useful just before the code commits to moving to GONE.
     *
     * This uses legacyShadeExpansion to process swipe up events. In the future, the touch input
     * signal should be sent directly to transitions.
     */
    val dismissAlpha: Flow<Float> =
        shadeRepository.legacyShadeExpansion
            .sampleCombine(
                statusBarState,
                keyguardTransitionInteractor.currentKeyguardState,
                keyguardTransitionInteractor.transitionState,
                isKeyguardDismissible,
            )
            .filter { (_, _, _, step, _) -> !step.transitionState.isTransitioning() }
            .transform {
                (
                    legacyShadeExpansion,
                    statusBarState,
                    currentKeyguardState,
                    step,
                    isKeyguardDismissible) ->
                if (
                    statusBarState == StatusBarState.KEYGUARD &&
                        isKeyguardDismissible &&
                        currentKeyguardState == LOCKSCREEN &&
                        legacyShadeExpansion != 1f
                ) {
                    emit(MathUtils.constrainedMap(0f, 1f, 0.95f, 1f, legacyShadeExpansion))
                } else if (legacyShadeExpansion == 0f || legacyShadeExpansion == 1f) {
                    // Resets alpha state
                    emit(1f)
                }
            }
            .onStart { emit(1f) }
            .distinctUntilChanged()

    val keyguardTranslationY: Flow<Float> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.keyguard_translate_distance_on_swipe_up)
            .flatMapLatest { translationDistance ->
                combineTransform(
                    shadeRepository.legacyShadeExpansion.onStart { emit(0f) },
                    keyguardTransitionInteractor.transitionValue(GONE).onStart { emit(0f) },
                ) { legacyShadeExpansion, goneValue ->
                    val isLegacyShadeInResetPosition =
                        legacyShadeExpansion == 0f || legacyShadeExpansion == 1f
                    if (goneValue == 1f || (goneValue == 0f && isLegacyShadeInResetPosition)) {
                        // Reset the translation value
                        emit(0f)
                    } else if (!isLegacyShadeInResetPosition) {
                        // On swipe up, translate the keyguard to reveal the bouncer, OR a GONE
                        // transition is running, which means this is a swipe to dismiss. Values of
                        // 0f and 1f need to be ignored in the legacy shade expansion. These can
                        // flip arbitrarily as the legacy shade is reset, and would cause the
                        // translation value to jump around unexpectedly.
                        emit(
                            MathUtils.lerp(
                                translationDistance,
                                0,
                                Interpolators.FAST_OUT_LINEAR_IN.getInterpolation(
                                    legacyShadeExpansion
                                ),
                            )
                        )
                    }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = 0f,
            )

    val clockShouldBeCentered: Flow<Boolean> = repository.clockShouldBeCentered

    /** Whether to animate the next doze mode transition. */
    val animateDozingTransitions: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isEnabled) {
            sceneInteractorProvider
                .get()
                .transitioningTo
                .map { it == Scenes.Lockscreen }
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

    /**
     * Whether the primary authentication is required for the given user due to lockdown or
     * encryption after reboot.
     */
    val isEncryptedOrLockdown: Flow<Boolean> = repository.isEncryptedOrLockdown

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

    fun setAlpha(alpha: Float) {
        repository.setKeyguardAlpha(alpha)
    }

    fun setPanelAlpha(alpha: Float) {
        repository.setPanelAlpha(alpha)
    }

    fun setAnimateDozingTransitions(animate: Boolean) {
        repository.setAnimateDozingTransitions(animate)
    }

    fun setClockShouldBeCentered(shouldBeCentered: Boolean) {
        repository.setClockShouldBeCentered(shouldBeCentered)
    }

    fun setLastRootViewTapPosition(point: Point?) {
        repository.lastRootViewTapPosition.value = point
    }

    fun setAmbientIndicationVisible(isVisible: Boolean) {
        repository.ambientIndicationVisible.value = isVisible
    }

    fun keyguardDoneAnimationsFinished() {
        repository.keyguardDoneAnimationsFinished()
    }

    fun setTopClippingBounds(top: Int?) {
        repository.topClippingBounds.value = top
    }

    fun setDreaming(isDreaming: Boolean) {
        repository.setDreaming(isDreaming)
    }

    /** Temporary shim, until [KeyguardWmStateRefactor] is enabled */
    fun showKeyguard() {
        fromGoneTransitionInteractor.get().showKeyguard()
    }

    /** Temporary shim, until [KeyguardWmStateRefactor] is enabled */
    fun dismissKeyguard() {
        fromLockscreenTransitionInteractor.get().dismissKeyguard()
    }

    companion object {
        private const val TAG = "KeyguardInteractor"
    }
}
