/*
 *   Copyright (C) 2023 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.android.systemui.deviceentry.domain.interactor

import android.app.trust.TrustManager
import android.content.Context
import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.biometrics.BiometricSourceType
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.shared.model.LockoutMode
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.data.repository.FaceWakeUpTriggersConfig
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.yield

/**
 * Encapsulates business logic related face authentication being triggered for device entry from
 * SystemUI Keyguard.
 */
@SysUISingleton
class SystemUIDeviceEntryFaceAuthInteractor
@Inject
constructor(
    private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val repository: DeviceEntryFaceAuthRepository,
    private val primaryBouncerInteractor: Lazy<PrimaryBouncerInteractor>,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val faceAuthenticationLogger: FaceAuthenticationLogger,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val deviceEntryFingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    private val userRepository: UserRepository,
    private val facePropertyRepository: FacePropertyRepository,
    private val faceWakeUpTriggersConfig: FaceWakeUpTriggersConfig,
    private val powerInteractor: PowerInteractor,
    private val biometricSettingsRepository: BiometricSettingsRepository,
    private val trustManager: TrustManager,
) : CoreStartable, DeviceEntryFaceAuthInteractor {

    private val listeners: MutableList<FaceAuthenticationListener> = mutableListOf()

    override fun start() {
        // Todo(b/310594096): there is a dependency cycle introduced by the repository depending on
        //  KeyguardBypassController, which in turn depends on KeyguardUpdateMonitor through
        //  its other dependencies. Once bypassEnabled state is available through a repository, we
        //  can break that cycle and inject this interactor directly into KeyguardUpdateMonitor
        keyguardUpdateMonitor.setFaceAuthInteractor(this)
        observeFaceAuthStateUpdates()
        faceAuthenticationLogger.interactorStarted()
        primaryBouncerInteractor
            .get()
            .isShowing
            .whenItFlipsToTrue()
            .onEach {
                faceAuthenticationLogger.bouncerVisibilityChanged()
                runFaceAuth(
                    FaceAuthUiEvent.FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN,
                    fallbackToDetect = false
                )
            }
            .launchIn(applicationScope)

        alternateBouncerInteractor.isVisible
            .whenItFlipsToTrue()
            .onEach {
                faceAuthenticationLogger.alternateBouncerVisibilityChanged()
                runFaceAuth(
                    FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN,
                    fallbackToDetect = false
                )
            }
            .launchIn(applicationScope)

        merge(
                keyguardTransitionInteractor.transition(AOD, LOCKSCREEN),
                keyguardTransitionInteractor.transition(OFF, LOCKSCREEN),
                keyguardTransitionInteractor.transition(DOZING, LOCKSCREEN),
            )
            .filter { it.transitionState == TransitionState.STARTED }
            .sample(powerInteractor.detailedWakefulness)
            .filter { wakefulnessModel ->
                val validWakeupReason =
                    faceWakeUpTriggersConfig.shouldTriggerFaceAuthOnWakeUpFrom(
                        wakefulnessModel.lastWakeReason
                    )
                if (!validWakeupReason) {
                    faceAuthenticationLogger.ignoredWakeupReason(wakefulnessModel.lastWakeReason)
                }
                validWakeupReason
            }
            .onEach {
                faceAuthenticationLogger.lockscreenBecameVisible(it)
                FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED.extraInfo =
                    it.lastWakeReason.powerManagerWakeReason
                runFaceAuth(
                    FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED,
                    fallbackToDetect = true
                )
            }
            .launchIn(applicationScope)

        deviceEntryFingerprintAuthInteractor.isLockedOut
            .sample(biometricSettingsRepository.isFaceAuthEnrolledAndEnabled, ::Pair)
            .filter { (_, faceEnabledAndEnrolled) ->
                // We don't care about this if face auth is not enabled.
                faceEnabledAndEnrolled
            }
            .map { (fpLockedOut, _) -> fpLockedOut }
            .sample(userRepository.selectedUser, ::Pair)
            .onEach { (fpLockedOut, currentUser) ->
                if (fpLockedOut) {
                    faceAuthenticationLogger.faceLockedOut("Fingerprint locked out")
                    if (isFaceAuthEnabledAndEnrolled()) {
                        repository.setLockedOut(true)
                    }
                } else {
                    // Fingerprint is not locked out anymore, revert face lockout state back to
                    // previous value.
                    resetLockedOutState(currentUser.userInfo.id)
                }
            }
            .launchIn(applicationScope)

        // User switching should stop face auth and then when it is complete we should trigger face
        // auth so that the switched user can unlock the device with face auth.
        userRepository.selectedUser
            .pairwise()
            .onEach { (previous, curr) ->
                val wasSwitching = previous.selectionStatus == SelectionStatus.SELECTION_IN_PROGRESS
                val isSwitching = curr.selectionStatus == SelectionStatus.SELECTION_IN_PROGRESS
                if (wasSwitching && !isSwitching) {
                    resetLockedOutState(curr.userInfo.id)
                    yield()
                    runFaceAuth(
                        FaceAuthUiEvent.FACE_AUTH_UPDATED_USER_SWITCHING,
                        // Fallback to detection if bouncer is not showing so that we can detect a
                        // face and then show the bouncer to the user if face auth can't run
                        fallbackToDetect = !primaryBouncerInteractor.get().isBouncerShowing()
                    )
                }
            }
            .launchIn(applicationScope)

        facePropertyRepository.cameraInfo
            .onEach {
                if (it != null && isRunning()) {
                    repository.cancel()
                    runFaceAuth(
                        FaceAuthUiEvent.FACE_AUTH_CAMERA_AVAILABLE_CHANGED,
                        fallbackToDetect = true
                    )
                }
            }
            .launchIn(applicationScope)
    }

    private suspend fun resetLockedOutState(currentUserId: Int) {
        val lockoutMode = facePropertyRepository.getLockoutMode(currentUserId)
        repository.setLockedOut(
            lockoutMode == LockoutMode.PERMANENT || lockoutMode == LockoutMode.TIMED
        )
    }

    override fun onSwipeUpOnBouncer() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, false)
    }

    override fun onNotificationPanelClicked() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED, true)
    }

    override fun onQsExpansionStared() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_QS_EXPANDED, true)
    }

    override fun onDeviceLifted() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED, true)
    }

    override fun onAssistantTriggeredOnLockScreen() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_UPDATED_ASSISTANT_VISIBILITY_CHANGED, true)
    }

    override fun onUdfpsSensorTouched() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN, false)
    }

    override fun onAccessibilityAction() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_ACCESSIBILITY_ACTION, false)
    }

    override fun onWalletLaunched() {
        if (facePropertyRepository.sensorInfo.value?.strength == SensorStrength.STRONG) {
            runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_OCCLUDING_APP_REQUESTED, true)
        }
    }

    override fun onDeviceUnfolded() {
        if (facePropertyRepository.supportedPostures.contains(DevicePosture.OPENED)) {
            runFaceAuth(FaceAuthUiEvent.FACE_AUTH_UPDATED_POSTURE_CHANGED, true)
        }
    }

    override fun registerListener(listener: FaceAuthenticationListener) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: FaceAuthenticationListener) {
        listeners.remove(listener)
    }

    override fun isLockedOut(): Boolean = repository.isLockedOut.value

    override fun isRunning(): Boolean = repository.isAuthRunning.value

    override fun canFaceAuthRun(): Boolean = repository.canRunFaceAuth.value

    override fun isFaceAuthStrong(): Boolean =
        facePropertyRepository.sensorInfo.value?.strength == SensorStrength.STRONG

    override fun onPrimaryBouncerUserInput() {
        repository.cancel()
    }

    private val faceAuthenticationStatusOverride = MutableStateFlow<FaceAuthenticationStatus?>(null)
    /** Provide the status of face authentication */
    override val authenticationStatus =
        merge(faceAuthenticationStatusOverride.filterNotNull(), repository.authenticationStatus)

    /** Provide the status of face detection */
    override val detectionStatus = repository.detectionStatus
    override val lockedOut: Flow<Boolean> = repository.isLockedOut
    override val authenticated: Flow<Boolean> = repository.isAuthenticated
    override val isBypassEnabled: Flow<Boolean> = repository.isBypassEnabled

    private fun runFaceAuth(uiEvent: FaceAuthUiEvent, fallbackToDetect: Boolean) {
        if (repository.isLockedOut.value) {
            faceAuthenticationStatusOverride.value =
                ErrorFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT,
                    context.resources.getString(R.string.keyguard_face_unlock_unavailable)
                )
        } else {
            faceAuthenticationStatusOverride.value = null
            faceAuthenticationLogger.authRequested(uiEvent)
            repository.requestAuthenticate(uiEvent, fallbackToDetection = fallbackToDetect)
        }
    }

    override fun isFaceAuthEnabledAndEnrolled(): Boolean =
        biometricSettingsRepository.isFaceAuthEnrolledAndEnabled.value

    override fun isAuthenticated(): Boolean = repository.isAuthenticated.value

    private fun observeFaceAuthStateUpdates() {
        authenticationStatus
            .onEach { authStatusUpdate ->
                listeners.forEach { it.onAuthenticationStatusChanged(authStatusUpdate) }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
        detectionStatus
            .onEach { detectionStatusUpdate ->
                listeners.forEach { it.onDetectionStatusChanged(detectionStatusUpdate) }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
        repository.isLockedOut
            .onEach { lockedOut -> listeners.forEach { it.onLockoutStateChanged(lockedOut) } }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
        repository.isAuthRunning
            .onEach { running -> listeners.forEach { it.onRunningStateChanged(running) } }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
        repository.isAuthenticated
            .sample(userRepository.selectedUserInfo, ::Pair)
            .onEach { (isAuthenticated, userInfo) ->
                if (!isAuthenticated) {
                    faceAuthenticationLogger.clearFaceRecognized()
                    trustManager.clearAllBiometricRecognized(BiometricSourceType.FACE, userInfo.id)
                }
            }
            .onEach { (isAuthenticated, _) ->
                listeners.forEach { it.onAuthenticatedChanged(isAuthenticated) }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)

        biometricSettingsRepository.isFaceAuthEnrolledAndEnabled
            .onEach { enrolledAndEnabled ->
                listeners.forEach { it.onAuthEnrollmentStateChanged(enrolledAndEnabled) }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
    }

    companion object {
        const val TAG = "DeviceEntryFaceAuthInteractor"
    }
}

// Extension method that filters a generic Boolean flow to one that emits
// whenever there is flip from false -> true
private fun Flow<Boolean>.whenItFlipsToTrue(): Flow<Boolean> {
    return this.pairwise()
        .filter { pair -> !pair.previousValue && pair.newValue }
        .map { it.newValue }
}
