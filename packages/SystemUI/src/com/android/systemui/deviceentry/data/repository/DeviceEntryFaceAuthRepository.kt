/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.deviceentry.data.repository

import android.app.StatusBarManager
import android.content.Context
import android.hardware.face.FaceManager
import android.os.CancellationSignal
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Dumpable
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.model.AcquiredFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.BiometricType
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FaceAuthTableLog
import com.android.systemui.keyguard.data.repository.FaceDetectTableLog
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SysUiFaceAuthenticateOptions
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.UserRepository
import com.google.errorprone.annotations.CompileTimeConstant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.util.Arrays
import java.util.stream.Collectors
import javax.inject.Inject

/**
 * API to run face authentication and detection for device entry / on keyguard (as opposed to the
 * biometric prompt).
 */
interface DeviceEntryFaceAuthRepository {
    /** Provide the current face authentication state for device entry. */
    val isAuthenticated: StateFlow<Boolean>

    /** Whether face auth can run at this point. */
    val canRunFaceAuth: StateFlow<Boolean>

    /** Provide the current status of face authentication. */
    val authenticationStatus: Flow<FaceAuthenticationStatus>

    /** Provide the current status of face detection. */
    val detectionStatus: Flow<FaceDetectionStatus>

    /** Current state of whether face authentication is locked out or not. */
    val isLockedOut: StateFlow<Boolean>

    /** Current state of whether face authentication is running. */
    val isAuthRunning: StateFlow<Boolean>

    /** Whether bypass is currently enabled */
    val isBypassEnabled: Flow<Boolean>

    /** Set whether face authentication should be locked out or not */
    fun setLockedOut(isLockedOut: Boolean)

    /**
     * Request face authentication or detection to be run.
     *
     * [uiEvent] provided should be logged whenever face authentication runs. Invocation should be
     * ignored if face authentication is already running. Results should be propagated through
     * [authenticationStatus]
     *
     * Run only face detection when [fallbackToDetection] is true and [canRunFaceAuth] is false.
     *
     * Method returns immediately and the face auth request is processed as soon as possible.
     */
    fun requestAuthenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean = false)

    /** Stop currently running face authentication or detection. */
    fun cancel()
}

private data class AuthenticationRequest(
    val uiEvent: FaceAuthUiEvent,
    val fallbackToDetection: Boolean
)

@SysUISingleton
class DeviceEntryFaceAuthRepositoryImpl
@Inject
constructor(
    context: Context,
    private val faceManager: FaceManager? = null,
    private val userRepository: UserRepository,
    private val keyguardBypassController: KeyguardBypassController? = null,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val sessionTracker: SessionTracker,
    private val uiEventsLogger: UiEventLogger,
    private val faceAuthLogger: FaceAuthenticationLogger,
    private val biometricSettingsRepository: BiometricSettingsRepository,
    private val deviceEntryFingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    trustRepository: TrustRepository,
    private val keyguardRepository: KeyguardRepository,
    private val powerInteractor: PowerInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    @FaceDetectTableLog private val faceDetectLog: TableLogBuffer,
    @FaceAuthTableLog private val faceAuthLog: TableLogBuffer,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val displayStateInteractor: DisplayStateInteractor,
    dumpManager: DumpManager,
) : DeviceEntryFaceAuthRepository, Dumpable {
    private var authCancellationSignal: CancellationSignal? = null
    private var detectCancellationSignal: CancellationSignal? = null
    private var faceAcquiredInfoIgnoreList: Set<Int>
    private var retryCount = 0

    private var pendingAuthenticateRequest = MutableStateFlow<AuthenticationRequest?>(null)

    private var cancelNotReceivedHandlerJob: Job? = null
    private var halErrorRetryJob: Job? = null

    private val _authenticationStatus: MutableStateFlow<FaceAuthenticationStatus?> =
        MutableStateFlow(null)
    override val authenticationStatus: Flow<FaceAuthenticationStatus>
        get() = _authenticationStatus.filterNotNull()

    private val _detectionStatus = MutableStateFlow<FaceDetectionStatus?>(null)
    override val detectionStatus: Flow<FaceDetectionStatus>
        get() = _detectionStatus.filterNotNull()

    private val _isLockedOut = MutableStateFlow(false)
    override val isLockedOut: StateFlow<Boolean> = _isLockedOut

    val isDetectionSupported =
        faceManager?.sensorPropertiesInternal?.firstOrNull()?.supportsFaceDetection ?: false

    private val _isAuthRunning = MutableStateFlow(false)
    override val isAuthRunning: StateFlow<Boolean>
        get() = _isAuthRunning

    private val keyguardSessionId: InstanceId?
        get() = sessionTracker.getSessionId(StatusBarManager.SESSION_KEYGUARD)

    override val canRunFaceAuth: StateFlow<Boolean>

    private val canRunDetection: StateFlow<Boolean>

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private var cancellationInProgress = MutableStateFlow(false)

    override val isBypassEnabled: Flow<Boolean> =
        keyguardBypassController?.let {
            conflatedCallbackFlow {
                val callback =
                    object : KeyguardBypassController.OnBypassStateChangedListener {
                        override fun onBypassStateChanged(isEnabled: Boolean) {
                            trySendWithFailureLogging(isEnabled, TAG, "BypassStateChanged")
                        }
                    }
                it.registerOnBypassStateChangedListener(callback)
                trySendWithFailureLogging(it.bypassEnabled, TAG, "BypassStateChanged")
                awaitClose { it.unregisterOnBypassStateChangedListener(callback) }
            }
        }
            ?: flowOf(false)

    override fun setLockedOut(isLockedOut: Boolean) {
        _isLockedOut.value = isLockedOut
    }

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
        dumpManager.registerCriticalDumpable("DeviceEntryFaceAuthRepositoryImpl", this)

        canRunFaceAuth =
            listOf(
                    *gatingConditionsForAuthAndDetect(),
                    Pair(isLockedOut.isFalse(), "isNotInLockOutState"),
                    Pair(trustRepository.isCurrentUserTrusted.isFalse(), "currentUserIsNotTrusted"),
                    Pair(
                        biometricSettingsRepository.isFaceAuthCurrentlyAllowed,
                        "isFaceAuthCurrentlyAllowed"
                    ),
                    Pair(isAuthenticated.isFalse(), "faceNotAuthenticated"),
                )
                .andAllFlows("canFaceAuthRun", faceAuthLog)
                .flowOn(backgroundDispatcher)
                .stateIn(applicationScope, SharingStarted.Eagerly, false)

        // Face detection can run only when lockscreen bypass is enabled
        // & detection is supported
        //   & biometric unlock is not allowed
        //     or user is trusted by trust manager & we want to run face detect to dismiss
        // keyguard
        canRunDetection =
            listOf(
                    *gatingConditionsForAuthAndDetect(),
                    Pair(isBypassEnabled, "isBypassEnabled"),
                    Pair(
                        biometricSettingsRepository.isFaceAuthCurrentlyAllowed
                            .isFalse()
                            .or(trustRepository.isCurrentUserTrusted),
                        "faceAuthIsNotCurrentlyAllowedOrCurrentUserIsTrusted"
                    ),
                    // We don't want to run face detect if fingerprint can be used to unlock the
                    // device
                    // but it's not possible to authenticate with FP from the bouncer (UDFPS)
                    Pair(
                        and(isUdfps(), deviceEntryFingerprintAuthRepository.isRunning).isFalse(),
                        "udfpsAuthIsNotPossibleAnymore"
                    )
                )
                .andAllFlows("canFaceDetectRun", faceDetectLog)
                .flowOn(backgroundDispatcher)
                .stateIn(applicationScope, SharingStarted.Eagerly, false)
        observeFaceAuthGatingChecks()
        observeFaceDetectGatingChecks()
        observeFaceAuthResettingConditions()
        listenForSchedulingWatchdog()
        processPendingAuthRequests()
    }

    private fun listenForSchedulingWatchdog() {
        keyguardTransitionInteractor.anyStateToGoneTransition
            .filter { it.transitionState == TransitionState.FINISHED }
            .onEach {
                // We deliberately want to run this in background because scheduleWatchdog does
                // a Binder IPC.
                withContext(backgroundDispatcher) {
                    faceAuthLogger.watchdogScheduled()
                    faceManager?.scheduleWatchdog()
                }
            }
            .launchIn(applicationScope)
    }

    private fun observeFaceAuthResettingConditions() {
        // Clear auth status when keyguard done animations finished or when the user is switching
        // or device starts going to sleep.
        merge(
                powerInteractor.isAsleep,
                if (KeyguardWmStateRefactor.isEnabled) {
                    keyguardTransitionInteractor.isInTransitionToState(KeyguardState.GONE)
                } else {
                    keyguardRepository.keyguardDoneAnimationsFinished.map { true }
                },
                userRepository.selectedUser.map {
                    it.selectionStatus == SelectionStatus.SELECTION_IN_PROGRESS
                },
            )
            .flowOn(mainDispatcher) // should revoke auth ASAP in the main thread
            .onEach { anyOfThemIsTrue ->
                if (anyOfThemIsTrue) {
                    clearPendingAuthRequest("Resetting auth status")
                    _isAuthenticated.value = false
                    retryCount = 0
                    halErrorRetryJob?.cancel()
                }
            }
            .launchIn(applicationScope)
    }

    private fun clearPendingAuthRequest(@CompileTimeConstant loggingContext: String) {
        faceAuthLogger.clearingPendingAuthRequest(
            loggingContext,
            pendingAuthenticateRequest.value?.uiEvent,
            pendingAuthenticateRequest.value?.fallbackToDetection
        )
        pendingAuthenticateRequest.value = null
    }

    private fun observeFaceDetectGatingChecks() {
        canRunDetection
            .onEach {
                if (!it) {
                    cancelDetection()
                }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
    }

    private fun isUdfps() =
        deviceEntryFingerprintAuthRepository.availableFpSensorType.map {
            it == BiometricType.UNDER_DISPLAY_FINGERPRINT
        }

    private fun gatingConditionsForAuthAndDetect(): Array<Pair<Flow<Boolean>, String>> {
        return arrayOf(
            Pair(
                and(
                        displayStateInteractor.isDefaultDisplayOff,
                        keyguardTransitionInteractor.isFinishedInStateWhere(
                            KeyguardState::deviceIsAwakeInState
                        ),
                    )
                    .isFalse(),
                // this can happen if an app is requesting for screen off, the display can
                // turn off without wakefulness.isStartingToSleepOrAsleep calls
                "displayIsNotOffWhileFullyTransitionedToAwake",
            ),
            Pair(
                biometricSettingsRepository.isFaceAuthEnrolledAndEnabled,
                "isFaceAuthEnrolledAndEnabled"
            ),
            Pair(keyguardRepository.isKeyguardGoingAway.isFalse(), "keyguardNotGoingAway"),
            Pair(
                keyguardTransitionInteractor
                    .isInTransitionToStateWhere(KeyguardState::deviceIsAsleepInState)
                    .isFalse(),
                "deviceNotTransitioningToAsleepState"
            ),
            Pair(
                keyguardInteractor.isSecureCameraActive
                    .isFalse()
                    .or(
                        alternateBouncerInteractor.isVisible.or(
                            keyguardInteractor.primaryBouncerShowing
                        )
                    ),
                "secureCameraNotActiveOrAnyBouncerIsShowing"
            ),
            Pair(
                biometricSettingsRepository.isFaceAuthSupportedInCurrentPosture,
                "isFaceAuthSupportedInCurrentPosture"
            ),
            Pair(
                biometricSettingsRepository.isCurrentUserInLockdown.isFalse(),
                "userHasNotLockedDownDevice"
            ),
            Pair(keyguardRepository.isKeyguardShowing, "isKeyguardShowing"),
            Pair(
                userRepository.selectedUser
                    .map { it.selectionStatus == SelectionStatus.SELECTION_IN_PROGRESS }
                    .isFalse(),
                "userSwitchingInProgress"
            )
        )
    }

    private fun observeFaceAuthGatingChecks() {
        canRunFaceAuth
            .onEach {
                faceAuthLogger.canFaceAuthRunChanged(it)
                if (!it) {
                    // Cancel currently running auth if any of the gating checks are false.
                    faceAuthLogger.cancellingFaceAuth()
                    cancel()
                }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
    }

    private val faceAuthCallback =
        object : FaceManager.AuthenticationCallback() {
            override fun onAuthenticationFailed() {
                _isAuthenticated.value = false
                faceAuthLogger.authenticationFailed()
                if (!_isLockedOut.value) {
                    // onAuthenticationError gets invoked before onAuthenticationFailed when the
                    // last auth attempt locks out face authentication.
                    // Skip updating the authentication status in such a scenario.
                    _authenticationStatus.value = FailedFaceAuthenticationStatus()
                    onFaceAuthRequestCompleted()
                }
            }

            override fun onAuthenticationAcquired(acquireInfo: Int) {
                _authenticationStatus.value = AcquiredFaceAuthenticationStatus(acquireInfo)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                val errorStatus = ErrorFaceAuthenticationStatus(errorCode, errString.toString())
                if (errorStatus.isLockoutError()) {
                    _isLockedOut.value = true
                }
                _isAuthenticated.value = false
                _authenticationStatus.value = errorStatus
                if (errorStatus.isHardwareError()) {
                    faceAuthLogger.hardwareError(errorStatus)
                    handleFaceHardwareError()
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
                _authenticationStatus.value = HelpFaceAuthenticationStatus(code, helpStr.toString())
            }

            override fun onAuthenticationSucceeded(result: FaceManager.AuthenticationResult) {
                // Update _isAuthenticated before _authenticationStatus is updated. There are
                // consumers that receive the face authentication updates through a long chain of
                // callbacks
                // _authenticationStatus -> KeyguardUpdateMonitor -> KeyguardStateController ->
                // onUnlockChanged
                // These consumers then query the isAuthenticated boolean. This makes sure that the
                // boolean is updated to new value before the event is propagated.
                // TODO (b/310592822): once all consumers can use the new system directly, we don't
                //  have to worry about this ordering.
                _isAuthenticated.value = true
                _authenticationStatus.value = SuccessFaceAuthenticationStatus(result)
                faceAuthLogger.faceAuthSuccess(result)
                onFaceAuthRequestCompleted()
            }
        }

    private fun handleFaceHardwareError() {
        if (retryCount < HAL_ERROR_RETRY_MAX) {
            retryCount++
            halErrorRetryJob?.cancel()
            halErrorRetryJob =
                applicationScope.launch {
                    delay(HAL_ERROR_RETRY_TIMEOUT)
                    if (retryCount < HAL_ERROR_RETRY_MAX) {
                        faceAuthLogger.attemptingRetryAfterHardwareError(retryCount)
                        requestAuthenticate(
                            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_RETRY_AFTER_HW_UNAVAILABLE,
                            fallbackToDetection = false
                        )
                    }
                }
        }
    }

    private fun onFaceAuthRequestCompleted() {
        cancelNotReceivedHandlerJob?.cancel()
        cancellationInProgress.value = false
        _isAuthRunning.value = false
        authCancellationSignal = null
    }

    private val detectionCallback =
        FaceManager.FaceDetectionCallback { sensorId, userId, isStrong ->
            faceAuthLogger.faceDetected()
            _detectionStatus.value = FaceDetectionStatus(sensorId, userId, isStrong)
        }

    override fun requestAuthenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) {
        if (pendingAuthenticateRequest.value != null) {
            faceAuthLogger.ignoredFaceAuthTrigger(
                pendingAuthenticateRequest.value?.uiEvent,
                "Previously queued trigger skipped due to new request"
            )
        }
        faceAuthLogger.queueingRequest(uiEvent, fallbackToDetection)
        pendingAuthenticateRequest.value = AuthenticationRequest(uiEvent, fallbackToDetection)
    }

    private fun processPendingAuthRequests() {
        combine(
                pendingAuthenticateRequest,
                canRunFaceAuth,
                canRunDetection,
                cancellationInProgress,
            ) { pending, canRunAuth, canRunDetect, cancelInProgress ->
                if (
                    pending != null &&
                        !(canRunAuth || (canRunDetect && pending.fallbackToDetection)) ||
                        cancelInProgress
                ) {
                    faceAuthLogger.notProcessingRequestYet(
                        pending?.uiEvent,
                        canRunAuth,
                        canRunDetect,
                        cancelInProgress
                    )
                    return@combine null
                } else {
                    return@combine pending
                }
            }
            .onEach {
                it?.let {
                    faceAuthLogger.processingRequest(it.uiEvent, it.fallbackToDetection)
                    clearPendingAuthRequest("Authenticate was invoked")
                    authenticate(it.uiEvent, it.fallbackToDetection)
                }
            }
            .flowOn(mainDispatcher)
            .launchIn(applicationScope)
    }

    private suspend fun authenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) {
        if (_isAuthRunning.value) {
            faceAuthLogger.ignoredFaceAuthTrigger(uiEvent, "face auth is currently running")
            return
        }

        if (cancellationInProgress.value) {
            faceAuthLogger.ignoredFaceAuthTrigger(uiEvent, "cancellation in progress")
            return
        }

        if (canRunFaceAuth.value) {
            withContext(mainDispatcher) {
                // We always want to invoke face auth in the main thread.
                authCancellationSignal = CancellationSignal()
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
                    authCancellationSignal,
                    faceAuthCallback,
                    null,
                    SysUiFaceAuthenticateOptions(
                            currentUserId,
                            uiEvent,
                            wakeReason = uiEvent.extraInfo
                        )
                        .toFaceAuthenticateOptions()
                )
            }
        } else if (canRunDetection.value) {
            if (fallbackToDetection) {
                faceAuthLogger.ignoredFaceAuthTrigger(
                    uiEvent,
                    "face auth gating check is false, falling back to detection."
                )
                detect(uiEvent)
            } else {
                faceAuthLogger.ignoredFaceAuthTrigger(
                    uiEvent = uiEvent,
                    "face auth gating check is false and fallback to detection is not requested"
                )
            }
        } else {
            faceAuthLogger.ignoredFaceAuthTrigger(
                uiEvent,
                "face auth & detect gating check is false"
            )
        }
    }

    suspend fun detect(uiEvent: FaceAuthUiEvent) {
        if (!isDetectionSupported) {
            faceAuthLogger.detectionNotSupported(faceManager, faceManager?.sensorPropertiesInternal)
            return
        }
        if (_isAuthRunning.value) {
            faceAuthLogger.skippingDetection(_isAuthRunning.value, detectCancellationSignal != null)
            return
        }
        withContext(mainDispatcher) {
            // We always want to invoke face detect in the main thread.
            faceAuthLogger.faceDetectionStarted()
            detectCancellationSignal?.cancel()
            detectCancellationSignal = CancellationSignal()
            detectCancellationSignal?.let {
                faceManager?.detectFace(
                    it,
                    detectionCallback,
                    SysUiFaceAuthenticateOptions(currentUserId, uiEvent, uiEvent.extraInfo)
                        .toFaceAuthenticateOptions()
                )
            }
        }
    }

    private val currentUserId: Int
        get() = userRepository.getSelectedUserInfo().id

    private fun cancelDetection() {
        detectCancellationSignal?.cancel()
        detectCancellationSignal = null
    }

    override fun cancel() {
        if (authCancellationSignal == null) return

        authCancellationSignal?.cancel()
        cancelNotReceivedHandlerJob?.cancel()
        cancelNotReceivedHandlerJob =
            applicationScope.launch {
                delay(DEFAULT_CANCEL_SIGNAL_TIMEOUT)
                faceAuthLogger.cancelSignalNotReceived(
                    _isAuthRunning.value,
                    _isLockedOut.value,
                    cancellationInProgress.value,
                    pendingAuthenticateRequest.value?.uiEvent
                )
                _authenticationStatus.value = ErrorFaceAuthenticationStatus.cancelNotReceivedError()
                onFaceAuthRequestCompleted()
            }
        cancellationInProgress.value = true
        _isAuthRunning.value = false
    }

    companion object {
        const val TAG = "DeviceEntryFaceAuthRepository"

        /**
         * If no cancel signal has been received after this amount of time, assume that it is
         * cancelled.
         */
        const val DEFAULT_CANCEL_SIGNAL_TIMEOUT = 3000L

        /** Number of allowed retries whenever there is a face hardware error */
        const val HAL_ERROR_RETRY_MAX = 5

        /** Timeout before retries whenever there is a HAL error. */
        const val HAL_ERROR_RETRY_TIMEOUT = 500L // ms
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("DeviceEntryFaceAuthRepositoryImpl state:")
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
        pw.println("  _pendingAuthenticateRequest: ${pendingAuthenticateRequest.value}")
        pw.println("  authCancellationSignal: $authCancellationSignal")
        pw.println("  detectCancellationSignal: $detectCancellationSignal")
        pw.println("  faceAcquiredInfoIgnoreList: $faceAcquiredInfoIgnoreList")
        pw.println("  _authenticationStatus: ${_authenticationStatus.value}")
        pw.println("  _detectionStatus: ${_detectionStatus.value}")
        pw.println("  currentUserId: $currentUserId")
        pw.println("  keyguardSessionId: $keyguardSessionId")
        pw.println("  lockscreenBypassEnabled: ${keyguardBypassController?.bypassEnabled ?: false}")
    }
}
/** Combine two boolean flows by and-ing both of them */
private fun and(flow: Flow<Boolean>, anotherFlow: Flow<Boolean>) =
    flow.combine(anotherFlow) { a, b -> a && b }

/** Combine two boolean flows by or-ing both of them */
private fun Flow<Boolean>.or(anotherFlow: Flow<Boolean>) =
    this.combine(anotherFlow) { a, b -> a || b }

/** "Not" the given flow. The return [Flow] will be true when [this] flow is false. */
private fun Flow<Boolean>.isFalse(): Flow<Boolean> {
    return this.map { !it }
}

private fun List<Pair<Flow<Boolean>, String>>.andAllFlows(
    combinedLoggingInfo: String,
    tableLogBuffer: TableLogBuffer
): Flow<Boolean> {
    return combine(this.map { it.first }) {
        val combinedValue =
            it.reduceIndexed { index, accumulator, current ->
                tableLogBuffer.logChange(prefix = "", columnName = this[index].second, current)
                return@reduceIndexed accumulator && current
            }
        tableLogBuffer.logChange(prefix = "", combinedLoggingInfo, combinedValue)
        return@combine combinedValue
    }
}
