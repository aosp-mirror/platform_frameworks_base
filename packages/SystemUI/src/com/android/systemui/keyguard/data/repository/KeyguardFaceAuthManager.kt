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
 */

package com.android.systemui.keyguard.data.repository

import android.app.StatusBarManager
import android.content.Context
import android.hardware.face.FaceManager
import android.os.CancellationSignal
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.FaceAuthUiEvent
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.shared.model.AcquiredAuthenticationStatus
import com.android.systemui.keyguard.shared.model.AuthenticationStatus
import com.android.systemui.keyguard.shared.model.DetectionStatus
import com.android.systemui.keyguard.shared.model.ErrorAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailedAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessAuthenticationStatus
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.repository.UserRepository
import java.io.PrintWriter
import java.util.Arrays
import java.util.stream.Collectors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * API to run face authentication and detection for device entry / on keyguard (as opposed to the
 * biometric prompt).
 */
interface KeyguardFaceAuthManager {
    /**
     * Trigger face authentication.
     *
     * [uiEvent] provided should be logged whenever face authentication runs. Invocation should be
     * ignored if face authentication is already running. Results should be propagated through
     * [authenticationStatus]
     */
    suspend fun authenticate(uiEvent: FaceAuthUiEvent)

    /**
     * Trigger face detection.
     *
     * Invocation should be ignored if face authentication is currently running.
     */
    suspend fun detect()

    /** Stop currently running face authentication or detection. */
    fun cancel()

    /** Provide the current status of face authentication. */
    val authenticationStatus: Flow<AuthenticationStatus>

    /** Provide the current status of face detection. */
    val detectionStatus: Flow<DetectionStatus>

    /** Current state of whether face authentication is locked out or not. */
    val isLockedOut: Flow<Boolean>

    /** Current state of whether face authentication is running. */
    val isAuthRunning: Flow<Boolean>

    /** Is face detection supported. */
    val isDetectionSupported: Boolean
}

@SysUISingleton
class KeyguardFaceAuthManagerImpl
@Inject
constructor(
    context: Context,
    private val faceManager: FaceManager? = null,
    private val userRepository: UserRepository,
    private val keyguardBypassController: KeyguardBypassController? = null,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val sessionTracker: SessionTracker,
    private val uiEventsLogger: UiEventLogger,
    private val faceAuthLogger: FaceAuthenticationLogger,
    dumpManager: DumpManager,
) : KeyguardFaceAuthManager, Dumpable {
    private var cancellationSignal: CancellationSignal? = null
    private val lockscreenBypassEnabled: Boolean
        get() = keyguardBypassController?.bypassEnabled ?: false
    private var faceAcquiredInfoIgnoreList: Set<Int>

    private val faceLockoutResetCallback =
        object : FaceManager.LockoutResetCallback() {
            override fun onLockoutReset(sensorId: Int) {
                _isLockedOut.value = false
            }
        }

    init {
        faceManager?.addLockoutResetCallback(faceLockoutResetCallback)
        faceAcquiredInfoIgnoreList =
            Arrays.stream(
                    context.resources.getIntArray(
                        R.array.config_face_acquire_device_entry_ignorelist
                    )
                )
                .boxed()
                .collect(Collectors.toSet())
        dumpManager.registerCriticalDumpable("KeyguardFaceAuthManagerImpl", this)
    }

    private val faceAuthCallback =
        object : FaceManager.AuthenticationCallback() {
            override fun onAuthenticationFailed() {
                _authenticationStatus.value = FailedAuthenticationStatus
                faceAuthLogger.authenticationFailed()
                onFaceAuthRequestCompleted()
            }

            override fun onAuthenticationAcquired(acquireInfo: Int) {
                _authenticationStatus.value = AcquiredAuthenticationStatus(acquireInfo)
                faceAuthLogger.authenticationAcquired(acquireInfo)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                val errorStatus = ErrorAuthenticationStatus(errorCode, errString.toString())
                if (errorStatus.isLockoutError()) {
                    _isLockedOut.value = true
                }
                _authenticationStatus.value = errorStatus
                if (errorStatus.isCancellationError()) {
                    cancelNotReceivedHandlerJob?.cancel()
                    applicationScope.launch {
                        faceAuthLogger.launchingQueuedFaceAuthRequest(
                            faceAuthRequestedWhileCancellation
                        )
                        faceAuthRequestedWhileCancellation?.let { authenticate(it) }
                        faceAuthRequestedWhileCancellation = null
                    }
                }
                faceAuthLogger.authenticationError(
                    errorCode,
                    errString,
                    errorStatus.isLockoutError(),
                    errorStatus.isCancellationError()
                )
                onFaceAuthRequestCompleted()
            }

            override fun onAuthenticationHelp(code: Int, helpStr: CharSequence?) {
                if (faceAcquiredInfoIgnoreList.contains(code)) {
                    return
                }
                _authenticationStatus.value = HelpAuthenticationStatus(code, helpStr.toString())
            }

            override fun onAuthenticationSucceeded(result: FaceManager.AuthenticationResult) {
                _authenticationStatus.value = SuccessAuthenticationStatus(result)
                faceAuthLogger.faceAuthSuccess(result)
                onFaceAuthRequestCompleted()
            }
        }

    private fun onFaceAuthRequestCompleted() {
        cancellationInProgress = false
        _isAuthRunning.value = false
        cancellationSignal = null
    }

    private val detectionCallback =
        FaceManager.FaceDetectionCallback { sensorId, userId, isStrong ->
            faceAuthLogger.faceDetected()
            _detectionStatus.value = DetectionStatus(sensorId, userId, isStrong)
        }

    private var cancellationInProgress = false
    private var faceAuthRequestedWhileCancellation: FaceAuthUiEvent? = null

    override suspend fun authenticate(uiEvent: FaceAuthUiEvent) {
        if (_isAuthRunning.value) {
            faceAuthLogger.ignoredFaceAuthTrigger(uiEvent)
            return
        }

        if (cancellationInProgress) {
            faceAuthLogger.queuingRequestWhileCancelling(
                faceAuthRequestedWhileCancellation,
                uiEvent
            )
            faceAuthRequestedWhileCancellation = uiEvent
            return
        } else {
            faceAuthRequestedWhileCancellation = null
        }

        withContext(mainDispatcher) {
            // We always want to invoke face auth in the main thread.
            cancellationSignal = CancellationSignal()
            _isAuthRunning.value = true
            uiEventsLogger.logWithInstanceIdAndPosition(
                uiEvent,
                0,
                null,
                keyguardSessionId,
                uiEvent.extraInfo
            )
            faceAuthLogger.authenticating(uiEvent)
            faceManager?.authenticate(
                null,
                cancellationSignal,
                faceAuthCallback,
                null,
                currentUserId,
                lockscreenBypassEnabled
            )
        }
    }

    override suspend fun detect() {
        if (!isDetectionSupported) {
            faceAuthLogger.detectionNotSupported(faceManager, faceManager?.sensorPropertiesInternal)
            return
        }
        if (_isAuthRunning.value) {
            faceAuthLogger.skippingBecauseAlreadyRunning("detection")
            return
        }

        cancellationSignal = CancellationSignal()
        withContext(mainDispatcher) {
            // We always want to invoke face detect in the main thread.
            faceAuthLogger.faceDetectionStarted()
            faceManager?.detectFace(cancellationSignal, detectionCallback, currentUserId)
        }
    }

    private val currentUserId: Int
        get() = userRepository.getSelectedUserInfo().id

    override fun cancel() {
        if (cancellationSignal == null) return

        cancellationSignal?.cancel()
        cancelNotReceivedHandlerJob =
            applicationScope.launch {
                delay(DEFAULT_CANCEL_SIGNAL_TIMEOUT)
                faceAuthLogger.cancelSignalNotReceived(
                    _isAuthRunning.value,
                    _isLockedOut.value,
                    cancellationInProgress,
                    faceAuthRequestedWhileCancellation
                )
                onFaceAuthRequestCompleted()
            }
        cancellationInProgress = true
        _isAuthRunning.value = false
    }

    private var cancelNotReceivedHandlerJob: Job? = null

    private val _authenticationStatus: MutableStateFlow<AuthenticationStatus?> =
        MutableStateFlow(null)
    override val authenticationStatus: Flow<AuthenticationStatus>
        get() = _authenticationStatus.filterNotNull()

    private val _detectionStatus = MutableStateFlow<DetectionStatus?>(null)
    override val detectionStatus: Flow<DetectionStatus>
        get() = _detectionStatus.filterNotNull()

    private val _isLockedOut = MutableStateFlow(false)
    override val isLockedOut: Flow<Boolean> = _isLockedOut

    override val isDetectionSupported =
        faceManager?.sensorPropertiesInternal?.firstOrNull()?.supportsFaceDetection ?: false

    private val _isAuthRunning = MutableStateFlow(false)
    override val isAuthRunning: Flow<Boolean>
        get() = _isAuthRunning

    private val keyguardSessionId: InstanceId?
        get() = sessionTracker.getSessionId(StatusBarManager.SESSION_KEYGUARD)

    companion object {
        const val TAG = "KeyguardFaceAuthManager"

        /**
         * If no cancel signal has been received after this amount of time, assume that it is
         * cancelled.
         */
        const val DEFAULT_CANCEL_SIGNAL_TIMEOUT = 3000L
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("KeyguardFaceAuthManagerImpl state:")
        pw.println("  cancellationInProgress: $cancellationInProgress")
        pw.println("  _isLockedOut.value: ${_isLockedOut.value}")
        pw.println("  _isAuthRunning.value: ${_isAuthRunning.value}")
        pw.println("  isDetectionSupported: $isDetectionSupported")
        pw.println("  FaceManager state:")
        pw.println("    faceManager: $faceManager")
        pw.println("    sensorPropertiesInternal: ${faceManager?.sensorPropertiesInternal}")
        pw.println(
            "    supportsFaceDetection: " +
                "${faceManager?.sensorPropertiesInternal?.firstOrNull()?.supportsFaceDetection}"
        )
        pw.println(
            "  faceAuthRequestedWhileCancellation: ${faceAuthRequestedWhileCancellation?.reason}"
        )
        pw.println("  cancellationSignal: $cancellationSignal")
        pw.println("  faceAcquiredInfoIgnoreList: $faceAcquiredInfoIgnoreList")
        pw.println("  _authenticationStatus: ${_authenticationStatus.value}")
        pw.println("  _detectionStatus: ${_detectionStatus.value}")
        pw.println("  currentUserId: $currentUserId")
        pw.println("  keyguardSessionId: $keyguardSessionId")
        pw.println("  lockscreenBypassEnabled: $lockscreenBypassEnabled")
    }
}
