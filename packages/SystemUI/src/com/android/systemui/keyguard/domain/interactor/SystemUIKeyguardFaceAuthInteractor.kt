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

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.hardware.biometrics.BiometricFaceConstants
import com.android.keyguard.FaceAuthUiEvent
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FaceAuthenticationStatus
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.pairwise
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates business logic related face authentication being triggered for device entry from
 * SystemUI Keyguard.
 */
@SysUISingleton
class SystemUIKeyguardFaceAuthInteractor
@Inject
constructor(
    private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val repository: DeviceEntryFaceAuthRepository,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val featureFlags: FeatureFlags,
    private val faceAuthenticationLogger: FaceAuthenticationLogger,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val deviceEntryFingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val userRepository: UserRepository,
) : CoreStartable, KeyguardFaceAuthInteractor {

    private val listeners: MutableList<FaceAuthenticationListener> = mutableListOf()

    override fun start() {
        if (!isEnabled()) {
            return
        }
        // This is required because fingerprint state required for the face auth repository is
        // backed by KeyguardUpdateMonitor. KeyguardUpdateMonitor constructor accesses the biometric
        // state which makes lazy injection not an option.
        keyguardUpdateMonitor.setFaceAuthInteractor(this)
        observeFaceAuthStateUpdates()
        faceAuthenticationLogger.interactorStarted()
        primaryBouncerInteractor.isShowing
            .whenItFlipsToTrue()
            .onEach {
                faceAuthenticationLogger.bouncerVisibilityChanged()
                runFaceAuth(
                    FaceAuthUiEvent.FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN,
                    fallbackToDetect = true
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
                keyguardTransitionInteractor.aodToLockscreenTransition,
                keyguardTransitionInteractor.offToLockscreenTransition,
                keyguardTransitionInteractor.dozingToLockscreenTransition
            )
            .filter { it.transitionState == TransitionState.STARTED }
            .onEach {
                faceAuthenticationLogger.lockscreenBecameVisible(it)
                runFaceAuth(
                    FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED,
                    fallbackToDetect = true
                )
            }
            .launchIn(applicationScope)

        deviceEntryFingerprintAuthRepository.isLockedOut
            .onEach {
                if (it) {
                    faceAuthenticationLogger.faceLockedOut("Fingerprint locked out")
                    repository.lockoutFaceAuth()
                }
            }
            .launchIn(applicationScope)

        // User switching should stop face auth and then when it is complete we should trigger face
        // auth so that the switched user can unlock the device with face auth.
        userRepository.userSwitchingInProgress
            .pairwise(false)
            .onEach { (wasSwitching, isSwitching) ->
                if (!wasSwitching && isSwitching) {
                    repository.pauseFaceAuth()
                } else if (wasSwitching && !isSwitching) {
                    repository.resumeFaceAuth()
                    runFaceAuth(
                        FaceAuthUiEvent.FACE_AUTH_UPDATED_USER_SWITCHING,
                        fallbackToDetect = true
                    )
                }
            }
            .launchIn(applicationScope)
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

    override fun registerListener(listener: FaceAuthenticationListener) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: FaceAuthenticationListener) {
        listeners.remove(listener)
    }

    override fun isLockedOut(): Boolean = repository.isLockedOut.value

    override fun isRunning(): Boolean = repository.isAuthRunning.value

    override fun canFaceAuthRun(): Boolean = repository.canRunFaceAuth.value

    override fun isEnabled(): Boolean {
        return featureFlags.isEnabled(Flags.FACE_AUTH_REFACTOR)
    }

    override fun onPrimaryBouncerUserInput() {
        repository.cancel()
    }

    private val faceAuthenticationStatusOverride = MutableStateFlow<FaceAuthenticationStatus?>(null)
    /** Provide the status of face authentication */
    override val authenticationStatus =
        merge(faceAuthenticationStatusOverride.filterNotNull(), repository.authenticationStatus)

    /** Provide the status of face detection */
    override val detectionStatus = repository.detectionStatus

    private fun runFaceAuth(uiEvent: FaceAuthUiEvent, fallbackToDetect: Boolean) {
        if (featureFlags.isEnabled(Flags.FACE_AUTH_REFACTOR)) {
            if (repository.isLockedOut.value) {
                faceAuthenticationStatusOverride.value =
                    ErrorFaceAuthenticationStatus(
                        BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT,
                        context.resources.getString(R.string.keyguard_face_unlock_unavailable)
                    )
            } else {
                faceAuthenticationStatusOverride.value = null
                applicationScope.launch {
                    withContext(mainDispatcher) {
                        faceAuthenticationLogger.authRequested(uiEvent)
                        repository.authenticate(uiEvent, fallbackToDetection = fallbackToDetect)
                    }
                }
            }
        } else {
            faceAuthenticationLogger.ignoredFaceAuthTrigger(
                uiEvent,
                ignoredReason = "Skipping face auth request because feature flag is false"
            )
        }
    }

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
    }

    companion object {
        const val TAG = "KeyguardFaceAuthInteractor"
    }
}

// Extension method that filters a generic Boolean flow to one that emits
// whenever there is flip from false -> true
private fun Flow<Boolean>.whenItFlipsToTrue(): Flow<Boolean> {
    return this.pairwise()
        .filter { pair -> !pair.previousValue && pair.newValue }
        .map { it.newValue }
}
