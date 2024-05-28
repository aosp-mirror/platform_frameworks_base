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
 */

package com.android.systemui.keyguard.data.repository

import android.graphics.Point
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.doze.DozeMachine
import com.android.systemui.doze.DozeTransitionCallback
import com.android.systemui.doze.DozeTransitionListener
import com.android.systemui.dreams.DreamOverlayCallbackController
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Defines interface for classes that encapsulate application state for the keyguard. */
interface KeyguardRepository {
    /**
     * Observable for whether the bottom area UI should animate the transition out of doze state.
     *
     * To learn more about doze state, please see [isDozing].
     */
    val animateBottomAreaDozingTransitions: StateFlow<Boolean>

    /**
     * Observable for the current amount of alpha that should be used for rendering the bottom area.
     * UI.
     */
    val bottomAreaAlpha: StateFlow<Float>

    val keyguardAlpha: StateFlow<Float>

    /**
     * Observable for whether the keyguard is showing.
     *
     * Note: this is also `true` when the lock-screen is occluded with an `Activity` "above" it in
     * the z-order (which is not really above the system UI window, but rather - the lock-screen
     * becomes invisible to reveal the "occluding activity").
     */
    val isKeyguardShowing: Flow<Boolean>

    /** Is an activity showing over the keyguard? */
    @Deprecated("Use KeyguardTransitionInteractor + KeyguardState.OCCLUDED")
    val isKeyguardOccluded: Flow<Boolean>

    /**
     * Whether the device is locked or unlocked right now. This is true when keyguard has been
     * dismissed or can be dismissed by a swipe
     */
    val isKeyguardDismissible: StateFlow<Boolean>

    /**
     * Observable for the signal that keyguard is about to go away.
     *
     * TODO(b/278086361): Remove once KEYGUARD_WM_STATE_REFACTOR flag is removed.
     */
    @Deprecated(
        "Use KeyguardTransitionInteractor flows instead. The closest match for 'going " +
            "away' is isInTransitionToState(GONE), but consider using more specific flows " +
            "whenever possible."
    )
    val isKeyguardGoingAway: Flow<Boolean>

    /**
     * Whether the keyguard is enabled, per [KeyguardService]. If the keyguard is not enabled, the
     * lockscreen cannot be shown and the device will go from AOD/DOZING directly to GONE.
     *
     * Keyguard can be disabled by selecting Security: "None" in settings, or by apps that hold
     * permission to do so (such as Phone).
     *
     * If the keyguard is disabled while we're locked, we will transition to GONE unless we're in
     * lockdown mode. If the keyguard is re-enabled, we'll transition back to LOCKSCREEN if we were
     * locked when it was disabled.
     */
    val isKeyguardEnabled: StateFlow<Boolean>

    /** Is the always-on display available to be used? */
    val isAodAvailable: StateFlow<Boolean>

    fun setAodAvailable(value: Boolean)

    /**
     * Observable for whether we are in doze state.
     *
     * Doze state is the same as "Always on Display" or "AOD". It is the state that the device can
     * enter to conserve battery when the device is locked and inactive.
     *
     * Note that it is possible for the system to be transitioning into doze while this flow still
     * returns `false`. In order to account for that, observers should also use the
     * [linearDozeAmount] flow to check if it's greater than `0`
     */
    val isDozing: StateFlow<Boolean>

    /** Keyguard can be clipped at the top as the shade is dragged */
    val topClippingBounds: MutableStateFlow<Int?>

    /**
     * Observable for whether the device is dreaming.
     *
     * Dozing/AOD is a specific type of dream, but it is also possible for other non-systemui dreams
     * to be active, such as screensavers.
     */
    val isDreaming: Flow<Boolean>

    /** Observable for whether the device is dreaming with an overlay, see [DreamOverlayService] */
    val isDreamingWithOverlay: Flow<Boolean>

    /** Observable for device dreaming state and the active dream is hosted in lockscreen */
    val isActiveDreamLockscreenHosted: StateFlow<Boolean>

    /**
     * Observable for the amount of doze we are currently in.
     *
     * While in doze state, this amount can change - driving a cycle of animations designed to avoid
     * pixel burn-in, etc.
     *
     * Also note that the value here may be greater than `0` while [isDozing] is still `false`, this
     * happens during an animation/transition into doze mode. An observer would be wise to account
     * for both flows if needed.
     */
    val linearDozeAmount: Flow<Float>

    /** Doze state information, as it transitions */
    val dozeTransitionModel: Flow<DozeTransitionModel>

    val lastDozeTapToWakePosition: StateFlow<Point?>

    /** Last point that [KeyguardRootView] was tapped */
    val lastRootViewTapPosition: MutableStateFlow<Point?>

    /** Is the ambient indication area visible? */
    val ambientIndicationVisible: MutableStateFlow<Boolean>

    /** Observable for the [StatusBarState] */
    val statusBarState: StateFlow<StatusBarState>

    /** Observable for biometric unlock state which includes the mode and unlock source */
    val biometricUnlockState: Flow<BiometricUnlockModel>

    fun setBiometricUnlockState(
        unlockMode: BiometricUnlockMode,
        unlockSource: BiometricUnlockSource?,
    )

    /** Approximate location on the screen of the fingerprint sensor. */
    val fingerprintSensorLocation: Flow<Point?>

    /** Approximate location on the screen of the face unlock sensor/front facing camera. */
    val faceSensorLocation: Flow<Point?>

    /** Whether quick settings or quick-quick settings is visible. */
    val isQuickSettingsVisible: Flow<Boolean>

    /** Receive an event for doze time tick */
    val dozeTimeTick: Flow<Long>

    /** Observable for DismissAction */
    val dismissAction: StateFlow<DismissAction>

    /** Observable updated when keyguardDone should be called either now or soon. */
    val keyguardDone: Flow<KeyguardDone>

    /**
     * Emits after the keyguard is done animating away.
     *
     * TODO(b/278086361): Remove once KEYGUARD_WM_STATE_REFACTOR flag is removed.
     */
    @Deprecated(
        "Use KeyguardTransitionInteractor flows instead. The closest match for " +
            "'keyguardDoneAnimationsFinished' is when the GONE transition is finished."
    )
    val keyguardDoneAnimationsFinished: Flow<Unit>

    /**
     * Receive whether clock should be centered on lockscreen.
     *
     * @deprecated When scene container flag is on use clockShouldBeCentered from domain level.
     */
    val clockShouldBeCentered: Flow<Boolean>

    /**
     * Whether the primary authentication is required for the given user due to lockdown or
     * encryption after reboot.
     */
    val isEncryptedOrLockdown: Flow<Boolean>

    /**
     * Returns `true` if the keyguard is showing; `false` otherwise.
     *
     * Note: this is also `true` when the lock-screen is occluded with an `Activity` "above" it in
     * the z-order (which is not really above the system UI window, but rather - the lock-screen
     * becomes invisible to reveal the "occluding activity").
     */
    fun isKeyguardShowing(): Boolean

    /** Sets whether the bottom area UI should animate the transition out of doze state. */
    fun setAnimateDozingTransitions(animate: Boolean)

    /** Sets the current amount of alpha that should be used for rendering the bottom area. */
    @Deprecated("Deprecated as part of b/278057014") fun setBottomAreaAlpha(alpha: Float)

    /** Sets the current amount of alpha that should be used for rendering the keyguard. */
    fun setKeyguardAlpha(alpha: Float)

    /**
     * Returns whether the keyguard bottom area should be constrained to the top of the lock icon
     */
    fun isUdfpsSupported(): Boolean

    /** Sets whether quick settings or quick-quick settings is visible. */
    fun setQuickSettingsVisible(isVisible: Boolean)

    fun setLastDozeTapToWakePosition(position: Point)

    fun setIsDozing(isDozing: Boolean)

    fun setIsActiveDreamLockscreenHosted(isLockscreenHosted: Boolean)

    fun dozeTimeTick()

    fun setDismissAction(dismissAction: DismissAction)

    suspend fun setKeyguardDone(keyguardDoneType: KeyguardDone)

    fun setClockShouldBeCentered(shouldBeCentered: Boolean)

    /**
     * Updates signal that the keyguard done animations are finished
     *
     * TODO(b/278086361): Remove once KEYGUARD_WM_STATE_REFACTOR flag is removed.
     */
    @Deprecated(
        "Use KeyguardTransitionInteractor flows instead. The closest match for " +
            "'keyguardDoneAnimationsFinished' is when the GONE transition is finished."
    )
    fun keyguardDoneAnimationsFinished()

    /** Sets whether the keyguard is enabled (see [isKeyguardEnabled]). */
    fun setKeyguardEnabled(enabled: Boolean)
}

/** Encapsulates application state for the keyguard. */
@SysUISingleton
class KeyguardRepositoryImpl
@Inject
constructor(
    statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val dozeTransitionListener: DozeTransitionListener,
    private val authController: AuthController,
    private val dreamOverlayCallbackController: DreamOverlayCallbackController,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val systemClock: SystemClock,
    facePropertyRepository: FacePropertyRepository,
    private val userTracker: UserTracker,
) : KeyguardRepository {
    private val _dismissAction: MutableStateFlow<DismissAction> =
        MutableStateFlow(DismissAction.None)
    override val dismissAction = _dismissAction.asStateFlow()
    override fun setDismissAction(dismissAction: DismissAction) {
        _dismissAction.value = dismissAction
    }

    private val _keyguardDone: MutableSharedFlow<KeyguardDone> = MutableSharedFlow()
    override val keyguardDone = _keyguardDone.asSharedFlow()
    override suspend fun setKeyguardDone(keyguardDoneType: KeyguardDone) {
        _keyguardDone.emit(keyguardDoneType)
    }

    override val keyguardDoneAnimationsFinished: MutableSharedFlow<Unit> = MutableSharedFlow()
    override fun keyguardDoneAnimationsFinished() {
        keyguardDoneAnimationsFinished.tryEmit(Unit)
    }

    private val _animateBottomAreaDozingTransitions = MutableStateFlow(false)
    override val animateBottomAreaDozingTransitions =
        _animateBottomAreaDozingTransitions.asStateFlow()

    private val _bottomAreaAlpha = MutableStateFlow(1f)
    override val bottomAreaAlpha = _bottomAreaAlpha.asStateFlow()

    private val _keyguardAlpha = MutableStateFlow(1f)
    override val keyguardAlpha = _keyguardAlpha.asStateFlow()

    private val _clockShouldBeCentered = MutableStateFlow(true)
    override val clockShouldBeCentered: Flow<Boolean> = _clockShouldBeCentered.asStateFlow()

    override val topClippingBounds = MutableStateFlow<Int?>(null)

    override val isKeyguardShowing: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardStateController.Callback {
                        override fun onKeyguardShowingChanged() {
                            trySendWithFailureLogging(
                                keyguardStateController.isShowing,
                                TAG,
                                "updated isKeyguardShowing"
                            )
                        }
                    }

                keyguardStateController.addCallback(callback)
                // Adding the callback does not send an initial update.
                trySendWithFailureLogging(
                    keyguardStateController.isShowing,
                    TAG,
                    "initial isKeyguardShowing"
                )

                awaitClose { keyguardStateController.removeCallback(callback) }
            }
            .distinctUntilChanged()

    private val _isAodAvailable = MutableStateFlow(false)
    override val isAodAvailable: StateFlow<Boolean> = _isAodAvailable.asStateFlow()

    override fun setAodAvailable(value: Boolean) {
        _isAodAvailable.value = value
    }

    override val isKeyguardOccluded: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardStateController.Callback {
                        override fun onKeyguardShowingChanged() {
                            trySendWithFailureLogging(
                                keyguardStateController.isOccluded,
                                TAG,
                                "updated isKeyguardOccluded"
                            )
                        }
                    }

                keyguardStateController.addCallback(callback)
                // Adding the callback does not send an initial update.
                trySendWithFailureLogging(
                    keyguardStateController.isOccluded,
                    TAG,
                    "initial isKeyguardOccluded"
                )

                awaitClose { keyguardStateController.removeCallback(callback) }
            }
            .distinctUntilChanged()

    override val isKeyguardDismissible: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardStateController.Callback {
                        override fun onUnlockedChanged() {
                            trySendWithFailureLogging(
                                keyguardStateController.isUnlocked,
                                TAG,
                                "updated isKeyguardDismissible due to onUnlockedChanged"
                            )
                        }

                        override fun onKeyguardShowingChanged() {
                            trySendWithFailureLogging(
                                keyguardStateController.isUnlocked,
                                TAG,
                                "updated isKeyguardDismissible due to onKeyguardShowingChanged"
                            )
                        }
                    }

                keyguardStateController.addCallback(callback)
                // Adding the callback does not send an initial update.
                trySendWithFailureLogging(
                    keyguardStateController.isUnlocked,
                    TAG,
                    "initial isKeyguardUnlocked"
                )

                awaitClose { keyguardStateController.removeCallback(callback) }
            }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                initialValue = false,
            )

    override val isKeyguardGoingAway: Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : KeyguardStateController.Callback {
                override fun onKeyguardGoingAwayChanged() {
                    trySendWithFailureLogging(
                        keyguardStateController.isKeyguardGoingAway,
                        TAG,
                        "updated isKeyguardGoingAway"
                    )
                }
            }

        keyguardStateController.addCallback(callback)
        // Adding the callback does not send an initial update.
        trySendWithFailureLogging(
            keyguardStateController.isKeyguardGoingAway,
            TAG,
            "initial isKeyguardGoingAway"
        )

        awaitClose { keyguardStateController.removeCallback(callback) }
    }

    private val _isKeyguardEnabled = MutableStateFlow(true)
    override val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _isDozing = MutableStateFlow(statusBarStateController.isDozing)
    override val isDozing: StateFlow<Boolean> = _isDozing.asStateFlow()

    override fun setIsDozing(isDozing: Boolean) {
        _isDozing.value = isDozing
    }

    private val _dozeTimeTick = MutableStateFlow<Long>(0)
    override val dozeTimeTick = _dozeTimeTick.asStateFlow()

    override fun dozeTimeTick() {
        _dozeTimeTick.value = systemClock.uptimeMillis()
    }

    private val _lastDozeTapToWakePosition = MutableStateFlow<Point?>(null)
    override val lastDozeTapToWakePosition = _lastDozeTapToWakePosition.asStateFlow()

    override fun setLastDozeTapToWakePosition(position: Point) {
        _lastDozeTapToWakePosition.value = position
    }

    override val lastRootViewTapPosition: MutableStateFlow<Point?> = MutableStateFlow(null)

    override val ambientIndicationVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val isDreamingWithOverlay: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : DreamOverlayCallbackController.Callback {
                        override fun onStartDream() {
                            trySendWithFailureLogging(true, TAG, "updated isDreamingWithOverlay")
                        }
                        override fun onWakeUp() {
                            trySendWithFailureLogging(false, TAG, "updated isDreamingWithOverlay")
                        }
                    }
                dreamOverlayCallbackController.addCallback(callback)
                trySendWithFailureLogging(
                    dreamOverlayCallbackController.isDreaming,
                    TAG,
                    "initial isDreamingWithOverlay",
                )

                awaitClose { dreamOverlayCallbackController.removeCallback(callback) }
            }
            .distinctUntilChanged()

    override val isDreaming: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onDreamingStateChanged(isDreaming: Boolean) {
                            trySendWithFailureLogging(isDreaming, TAG, "updated isDreaming")
                        }
                    }
                keyguardUpdateMonitor.registerCallback(callback)
                trySendWithFailureLogging(
                    keyguardUpdateMonitor.isDreaming,
                    TAG,
                    "initial isDreaming",
                )

                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .flowOn(mainDispatcher)
            .distinctUntilChanged()

    override val linearDozeAmount: Flow<Float> = conflatedCallbackFlow {
        val callback =
            object : StatusBarStateController.StateListener {
                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    trySendWithFailureLogging(linear, TAG, "updated dozeAmount")
                }
            }

        statusBarStateController.addCallback(callback)
        trySendWithFailureLogging(statusBarStateController.dozeAmount, TAG, "initial dozeAmount")

        awaitClose { statusBarStateController.removeCallback(callback) }
    }

    override val dozeTransitionModel: Flow<DozeTransitionModel> = conflatedCallbackFlow {
        val callback =
            object : DozeTransitionCallback {
                override fun onDozeTransition(
                    oldState: DozeMachine.State,
                    newState: DozeMachine.State
                ) {
                    trySendWithFailureLogging(
                        DozeTransitionModel(
                            from = dozeMachineStateToModel(oldState),
                            to = dozeMachineStateToModel(newState),
                        ),
                        TAG,
                        "doze transition model"
                    )
                }
            }

        dozeTransitionListener.addCallback(callback)
        trySendWithFailureLogging(
            DozeTransitionModel(
                from = dozeMachineStateToModel(dozeTransitionListener.oldState),
                to = dozeMachineStateToModel(dozeTransitionListener.newState),
            ),
            TAG,
            "initial doze transition model"
        )

        awaitClose { dozeTransitionListener.removeCallback(callback) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isEncryptedOrLockdown: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onStrongAuthStateChanged(userId: Int) {
                            trySendWithFailureLogging(userId, TAG, "strong auth state change")
                        }
                    }
                keyguardUpdateMonitor.registerCallback(callback)
                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .filter { userId -> userId == userTracker.userId }
            .onStart { emit(userTracker.userId) }
            .mapLatest { userId -> keyguardUpdateMonitor.isEncryptedOrLockdown(userId) }
            // KeyguardUpdateMonitor#registerCallback needs to be called on the main thread.
            .flowOn(mainDispatcher)

    override fun isKeyguardShowing(): Boolean {
        return keyguardStateController.isShowing
    }

    // TODO(b/297345631): Expose this at the interactor level instead so that it can be powered by
    // [SceneInteractor] when scenes are ready.
    override val statusBarState: StateFlow<StatusBarState> =
        conflatedCallbackFlow {
                val callback =
                    object : StatusBarStateController.StateListener {
                        override fun onStateChanged(state: Int) {
                            trySendWithFailureLogging(
                                statusBarStateIntToObject(state),
                                TAG,
                                "state"
                            )
                        }
                    }

                statusBarStateController.addCallback(callback)
                awaitClose { statusBarStateController.removeCallback(callback) }
            }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                statusBarStateIntToObject(statusBarStateController.state)
            )

    private val _biometricUnlockState: MutableStateFlow<BiometricUnlockModel> =
        MutableStateFlow(BiometricUnlockModel(BiometricUnlockMode.NONE, null))
    override val biometricUnlockState = _biometricUnlockState.asStateFlow()

    override fun setBiometricUnlockState(
        unlockMode: BiometricUnlockMode,
        unlockSource: BiometricUnlockSource?,
    ) {
        _biometricUnlockState.value = BiometricUnlockModel(unlockMode, unlockSource)
    }

    override val fingerprintSensorLocation: Flow<Point?> = conflatedCallbackFlow {
        fun sendFpLocation() {
            trySendWithFailureLogging(
                authController.fingerprintSensorLocation,
                TAG,
                "AuthController.Callback#onFingerprintLocationChanged"
            )
        }

        val callback =
            object : AuthController.Callback {
                override fun onFingerprintLocationChanged() {
                    sendFpLocation()
                }
            }

        authController.addCallback(callback)
        sendFpLocation()

        awaitClose { authController.removeCallback(callback) }
    }

    override val faceSensorLocation: Flow<Point?> = facePropertyRepository.sensorLocation

    private val _isQuickSettingsVisible = MutableStateFlow(false)
    override val isQuickSettingsVisible: Flow<Boolean> = _isQuickSettingsVisible.asStateFlow()

    private val _isActiveDreamLockscreenHosted = MutableStateFlow(false)
    override val isActiveDreamLockscreenHosted = _isActiveDreamLockscreenHosted.asStateFlow()

    override fun setAnimateDozingTransitions(animate: Boolean) {
        _animateBottomAreaDozingTransitions.value = animate
    }

    override fun setBottomAreaAlpha(alpha: Float) {
        _bottomAreaAlpha.value = alpha
    }

    override fun setKeyguardAlpha(alpha: Float) {
        _keyguardAlpha.value = alpha
    }

    override fun isUdfpsSupported(): Boolean = keyguardUpdateMonitor.isUdfpsSupported

    override fun setQuickSettingsVisible(isVisible: Boolean) {
        _isQuickSettingsVisible.value = isVisible
    }

    override fun setIsActiveDreamLockscreenHosted(isLockscreenHosted: Boolean) {
        _isActiveDreamLockscreenHosted.value = isLockscreenHosted
    }

    override fun setClockShouldBeCentered(shouldBeCentered: Boolean) {
        _clockShouldBeCentered.value = shouldBeCentered
    }

    override fun setKeyguardEnabled(enabled: Boolean) {
        _isKeyguardEnabled.value = enabled
    }

    private fun statusBarStateIntToObject(value: Int): StatusBarState {
        return when (value) {
            0 -> StatusBarState.SHADE
            1 -> StatusBarState.KEYGUARD
            2 -> StatusBarState.SHADE_LOCKED
            else -> throw IllegalArgumentException("Invalid StatusBarState value: $value")
        }
    }

    private fun dozeMachineStateToModel(state: DozeMachine.State): DozeStateModel {
        return when (state) {
            DozeMachine.State.UNINITIALIZED -> DozeStateModel.UNINITIALIZED
            DozeMachine.State.INITIALIZED -> DozeStateModel.INITIALIZED
            DozeMachine.State.DOZE -> DozeStateModel.DOZE
            DozeMachine.State.DOZE_SUSPEND_TRIGGERS -> DozeStateModel.DOZE_SUSPEND_TRIGGERS
            DozeMachine.State.DOZE_AOD -> DozeStateModel.DOZE_AOD
            DozeMachine.State.DOZE_REQUEST_PULSE -> DozeStateModel.DOZE_REQUEST_PULSE
            DozeMachine.State.DOZE_PULSING -> DozeStateModel.DOZE_PULSING
            DozeMachine.State.DOZE_PULSING_BRIGHT -> DozeStateModel.DOZE_PULSING_BRIGHT
            DozeMachine.State.DOZE_PULSE_DONE -> DozeStateModel.DOZE_PULSE_DONE
            DozeMachine.State.FINISH -> DozeStateModel.FINISH
            DozeMachine.State.DOZE_AOD_PAUSED -> DozeStateModel.DOZE_AOD_PAUSED
            DozeMachine.State.DOZE_AOD_PAUSING -> DozeStateModel.DOZE_AOD_PAUSING
            DozeMachine.State.DOZE_AOD_DOCKED -> DozeStateModel.DOZE_AOD_DOCKED
            else -> throw IllegalArgumentException("Invalid DozeMachine.State: state")
        }
    }

    companion object {
        private const val TAG = "KeyguardRepositoryImpl"
    }
}
