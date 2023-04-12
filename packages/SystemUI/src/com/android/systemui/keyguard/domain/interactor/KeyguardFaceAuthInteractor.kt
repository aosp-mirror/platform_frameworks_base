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

import com.android.keyguard.FaceAuthUiEvent
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.shared.model.AuthenticationStatus
import com.android.systemui.keyguard.shared.model.DetectionStatus
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Encapsulates business logic related face authentication being triggered for device entry from
 * keyguard
 */
@SysUISingleton
class KeyguardFaceAuthInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val repository: DeviceEntryFaceAuthRepository,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val featureFlags: FeatureFlags,
    private val faceAuthenticationLogger: FaceAuthenticationLogger,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) : CoreStartable {

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
    }

    private fun runFaceAuth(uiEvent: FaceAuthUiEvent, fallbackToDetect: Boolean) {
        if (featureFlags.isEnabled(Flags.FACE_AUTH_REFACTOR)) {
            applicationScope.launch {
                faceAuthenticationLogger.authRequested(uiEvent)
                repository.authenticate(uiEvent, fallbackToDetection = fallbackToDetect)
            }
        } else {
            faceAuthenticationLogger.ignoredFaceAuthTrigger(
                uiEvent,
                ignoredReason = "Skipping face auth request because feature flag is false"
            )
        }
    }

    fun onSwipeUpOnBouncer() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, false)
    }

    fun onNotificationPanelClicked() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED, true)
    }

    fun onQsExpansionStared() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_QS_EXPANDED, true)
    }

    fun onDeviceLifted() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED, true)
    }

    fun onAssistantTriggeredOnLockScreen() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_UPDATED_ASSISTANT_VISIBILITY_CHANGED, true)
    }

    fun onUdfpsSensorTouched() {
        runFaceAuth(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN, false)
    }

    fun registerListener(listener: FaceAuthenticationListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: FaceAuthenticationListener) {
        listeners.remove(listener)
    }

    fun isLockedOut(): Boolean = repository.isLockedOut.value

    fun isRunning(): Boolean = repository.isAuthRunning.value

    fun canFaceAuthRun(): Boolean = repository.canRunFaceAuth.value

    fun isEnabled(): Boolean {
        return featureFlags.isEnabled(Flags.FACE_AUTH_REFACTOR)
    }

    /** Provide the status of face authentication */
    val authenticationStatus = repository.authenticationStatus

    /** Provide the status of face detection */
    val detectionStatus = repository.detectionStatus

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

/**
 * Listener that can be registered with the [KeyguardFaceAuthInteractor] to receive updates about
 * face authentication & detection updates.
 *
 * This is present to make it easier for use the new face auth API for code that cannot use
 * [KeyguardFaceAuthInteractor.authenticationStatus] or [KeyguardFaceAuthInteractor.detectionStatus]
 * flows.
 */
interface FaceAuthenticationListener {
    /** Receive face authentication status updates */
    fun onAuthenticationStatusChanged(status: AuthenticationStatus)

    /** Receive status updates whenever face detection runs */
    fun onDetectionStatusChanged(status: DetectionStatus)
}

// Extension method that filters a generic Boolean flow to one that emits
// whenever there is flip from false -> true
private fun Flow<Boolean>.whenItFlipsToTrue(): Flow<Boolean> {
    return this.pairwise()
        .filter { pair -> !pair.previousValue && pair.newValue }
        .map { it.newValue }
}
