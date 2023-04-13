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
import android.hardware.face.FaceAuthenticateOptions
import android.hardware.face.FaceManager
import android.os.CancellationSignal
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.FaceAuthUiEvent
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AcquiredAuthenticationStatus
import com.android.systemui.keyguard.shared.model.AuthenticationStatus
import com.android.systemui.keyguard.shared.model.DetectionStatus
import com.android.systemui.keyguard.shared.model.ErrorAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailedAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessAuthenticationStatus
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.repository.UserRepository
import java.io.PrintWriter
import java.util.Arrays
import java.util.stream.Collectors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * API to run face authentication and detection for device entry / on keyguard (as opposed to the
 * biometric prompt).
 */
interface DeviceEntryFaceAuthRepository {
    /** Provide the current face authentication state for device entry. */
    val isAuthenticated: Flow<Boolean>

    /** Whether face auth can run at this point. */
    val canRunFaceAuth: StateFlow<Boolean>

    /** Provide the current status of face authentication. */
    val authenticationStatus: Flow<AuthenticationStatus>

    /** Provide the current status of face detection. */
    val detectionStatus: Flow<DetectionStatus>

    /** Current state of whether face authentication is locked out or not. */
    val isLockedOut: StateFlow<Boolean>

    /** Current state of whether face authentication is running. */
    val isAuthRunning: StateFlow<Boolean>

    /** Whether bypass is currently enabled */
    val isBypassEnabled: Flow<Boolean>

    /**
     * Trigger face authentication.
     *
     * [uiEvent] provided should be logged whenever face authentication runs. Invocation should be
     * ignored if face authentication is already running. Results should be propagated through
     * [authenticationStatus]
     *
     * Run only face detection when [fallbackToDetection] is true and [canRunFaceAuth] is false.
     */
    suspend fun authenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean = false)

    /** Stop currently running face authentication or detection. */
    fun cancel()
}

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
    private val sessionTracker: SessionTracker,
    private val uiEventsLogger: UiEventLogger,
    private val faceAuthLogger: FaceAuthenticationLogger,
    private val biometricSettingsRepository: BiometricSettingsRepository,
    private val deviceEntryFingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val trustRepository: TrustRepository,
    private val keyguardRepository: KeyguardRepository,
    private val keyguardInteractor: KeyguardInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    @FaceDetectTableLog private val faceDetectLog: TableLogBuffer,
    @FaceAuthTableLog private val faceAuthLog: TableLogBuffer,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    dumpManager: DumpManager,
) : DeviceEntryFaceAuthRepository, Dumpable {
    private var authCancellationSignal: CancellationSignal? = null
    private var detectCancellationSignal: CancellationSignal? = null
    private var faceAcquiredInfoIgnoreList: Set<Int>

    private var cancelNotReceivedHandlerJob: Job? = null

    private val _authenticationStatus: MutableStateFlow<AuthenticationStatus?> =
        MutableStateFlow(null)
    override val authenticationStatus: Flow<AuthenticationStatus>
        get() = _authenticationStatus.filterNotNull()

    private val _detectionStatus = MutableStateFlow<DetectionStatus?>(null)
    override val detectionStatus: Flow<DetectionStatus>
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

    private val _canRunFaceAuth = MutableStateFlow(true)
    override val canRunFaceAuth: StateFlow<Boolean>
        get() = _canRunFaceAuth

    private val canRunDetection = MutableStateFlow(false)

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: Flow<Boolean>
        get() = _isAuthenticated

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

        observeFaceAuthGatingChecks()
        observeFaceDetectGatingChecks()
        observeFaceAuthResettingConditions()
        listenForSchedulingWatchdog()
    }

    private fun listenForSchedulingWatchdog() {
        keyguardTransitionInteractor.anyStateToGoneTransition
            .onEach { faceManager?.scheduleWatchdog() }
            .launchIn(applicationScope)
    }

    private fun observeFaceAuthResettingConditions() {
        // Clear auth status when keyguard is going away or when the user is switching.
        merge(keyguardRepository.isKeyguardGoingAway, userRepository.userSwitchingInProgress)
            .onEach { goingAwayOrUserSwitchingInProgress ->
                if (goingAwayOrUserSwitchingInProgress) {
                    _isAuthenticated.value = false
                }
            }
            .launchIn(applicationScope)
    }

    private fun observeFaceDetectGatingChecks() {
        // Face detection can run only when lockscreen bypass is enabled
        // & detection is supported & biometric unlock is not allowed.
        listOf(
                canFaceAuthOrDetectRun(faceDetectLog),
                logAndObserve(isBypassEnabled, "isBypassEnabled", faceDetectLog),
                logAndObserve(
                    biometricSettingsRepository.isNonStrongBiometricAllowed.isFalse(),
                    "nonStrongBiometricIsNotAllowed",
                    faceDetectLog
                ),
                // We don't want to run face detect if it's not possible to authenticate with FP
                // from the bouncer. UDFPS is the only fp sensor type that won't support this.
                logAndObserve(
                    and(isUdfps(), deviceEntryFingerprintAuthRepository.isRunning).isFalse(),
                    "udfpsAuthIsNotPossibleAnymore",
                    faceDetectLog
                )
            )
            .reduce(::and)
            .distinctUntilChanged()
            .onEach {
                faceAuthLogger.canRunDetectionChanged(it)
                canRunDetection.value = it
                if (!it) {
                    cancelDetection()
                }
            }
            .logDiffsForTable(faceDetectLog, "", "canFaceDetectRun", false)
            .launchIn(applicationScope)
    }

    private fun isUdfps() =
        deviceEntryFingerprintAuthRepository.availableFpSensorType.map {
            it == BiometricType.UNDER_DISPLAY_FINGERPRINT
        }

    private fun canFaceAuthOrDetectRun(tableLogBuffer: TableLogBuffer): Flow<Boolean> {
        return listOf(
                logAndObserve(
                    biometricSettingsRepository.isFaceEnrolled,
                    "isFaceEnrolled",
                    tableLogBuffer
                ),
                logAndObserve(
                    biometricSettingsRepository.isFaceAuthenticationEnabled,
                    "isFaceAuthenticationEnabled",
                    tableLogBuffer
                ),
                logAndObserve(
                    userRepository.userSwitchingInProgress.isFalse(),
                    "userSwitchingNotInProgress",
                    tableLogBuffer
                ),
                logAndObserve(
                    keyguardRepository.isKeyguardGoingAway.isFalse(),
                    "keyguardNotGoingAway",
                    tableLogBuffer
                ),
                logAndObserve(
                    keyguardRepository.wakefulness
                        .map { WakefulnessModel.isSleepingOrStartingToSleep(it) }
                        .isFalse(),
                    "deviceNotSleepingOrNotStartingToSleep",
                    tableLogBuffer
                ),
                logAndObserve(
                    combine(
                        keyguardInteractor.isSecureCameraActive,
                        alternateBouncerInteractor.isVisible,
                    ) { a, b ->
                        !a || b
                    },
                    "secureCameraNotActiveOrAltBouncerIsShowing",
                    tableLogBuffer
                ),
                logAndObserve(
                    biometricSettingsRepository.isFaceAuthSupportedInCurrentPosture,
                    "isFaceAuthSupportedInCurrentPosture",
                    tableLogBuffer
                ),
                logAndObserve(
                    biometricSettingsRepository.isCurrentUserInLockdown.isFalse(),
                    "userHasNotLockedDownDevice",
                    tableLogBuffer
                )
            )
            .reduce(::and)
    }

    private fun observeFaceAuthGatingChecks() {
        // Face auth can run only if all of the gating conditions are true.
        listOf(
                canFaceAuthOrDetectRun(faceAuthLog),
                logAndObserve(isLockedOut.isFalse(), "isNotInLockOutState", faceAuthLog),
                logAndObserve(
                    deviceEntryFingerprintAuthRepository.isLockedOut.isFalse(),
                    "fpLockedOut",
                    faceAuthLog
                ),
                logAndObserve(
                    trustRepository.isCurrentUserTrusted.isFalse(),
                    "currentUserTrusted",
                    faceAuthLog
                ),
                logAndObserve(
                    biometricSettingsRepository.isNonStrongBiometricAllowed,
                    "nonStrongBiometricIsAllowed",
                    faceAuthLog
                ),
                logAndObserve(
                    userRepository.selectedUserInfo.map { it.isPrimary },
                    "userIsPrimaryUser",
                    faceAuthLog
                ),
            )
            .reduce(::and)
            .distinctUntilChanged()
            .onEach {
                faceAuthLogger.canFaceAuthRunChanged(it)
                _canRunFaceAuth.value = it
                if (!it) {
                    // Cancel currently running auth if any of the gating checks are false.
                    faceAuthLogger.cancellingFaceAuth()
                    cancel()
                }
            }
            .logDiffsForTable(faceAuthLog, "", "canFaceAuthRun", false)
            .launchIn(applicationScope)
    }

    private val faceAuthCallback =
        object : FaceManager.AuthenticationCallback() {
            override fun onAuthenticationFailed() {
                _authenticationStatus.value = FailedAuthenticationStatus
                _isAuthenticated.value = false
                faceAuthLogger.authenticationFailed()
                onFaceAuthRequestCompleted()
            }

            override fun onAuthenticationAcquired(acquireInfo: Int) {
                _authenticationStatus.value = AcquiredAuthenticationStatus(acquireInfo)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                val errorStatus = ErrorAuthenticationStatus(errorCode, errString.toString())
                if (errorStatus.isLockoutError()) {
                    _isLockedOut.value = true
                }
                _authenticationStatus.value = errorStatus
                _isAuthenticated.value = false
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
                _isAuthenticated.value = true
                faceAuthLogger.faceAuthSuccess(result)
                onFaceAuthRequestCompleted()
            }
        }

    private fun onFaceAuthRequestCompleted() {
        cancellationInProgress = false
        _isAuthRunning.value = false
        authCancellationSignal = null
    }

    private val detectionCallback =
        FaceManager.FaceDetectionCallback { sensorId, userId, isStrong ->
            faceAuthLogger.faceDetected()
            _detectionStatus.value = DetectionStatus(sensorId, userId, isStrong)
        }

    private var cancellationInProgress = false
    private var faceAuthRequestedWhileCancellation: FaceAuthUiEvent? = null

    override suspend fun authenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) {
        if (_isAuthRunning.value) {
            faceAuthLogger.ignoredFaceAuthTrigger(uiEvent, "face auth is currently running")
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
                    FaceAuthenticateOptions.Builder().setUserId(currentUserId).build()
                )
            }
        } else if (fallbackToDetection && canRunDetection.value) {
            faceAuthLogger.ignoredFaceAuthTrigger(
                uiEvent,
                "face auth gating check is false, falling back to detection."
            )
            detect()
        } else {
            faceAuthLogger.ignoredFaceAuthTrigger(
                uiEvent,
                "face auth & detect gating check is false"
            )
        }
    }

    suspend fun detect() {
        if (!isDetectionSupported) {
            faceAuthLogger.detectionNotSupported(faceManager, faceManager?.sensorPropertiesInternal)
            return
        }
        if (_isAuthRunning.value || detectCancellationSignal != null) {
            faceAuthLogger.skippingDetection(_isAuthRunning.value, detectCancellationSignal != null)
            return
        }

        detectCancellationSignal = CancellationSignal()
        withContext(mainDispatcher) {
            // We always want to invoke face detect in the main thread.
            faceAuthLogger.faceDetectionStarted()
            faceManager?.detectFace(
                detectCancellationSignal,
                detectionCallback,
                FaceAuthenticateOptions.Builder().setUserId(currentUserId).build()
            )
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

    private fun logAndObserve(
        cond: Flow<Boolean>,
        conditionName: String,
        logBuffer: TableLogBuffer
    ): Flow<Boolean> {
        return cond
            .distinctUntilChanged()
            .logDiffsForTable(
                logBuffer,
                columnName = conditionName,
                columnPrefix = "",
                initialValue = false
            )
            .onEach { faceAuthLogger.observedConditionChanged(it, conditionName) }
    }

    companion object {
        const val TAG = "DeviceEntryFaceAuthRepository"

        /**
         * If no cancel signal has been received after this amount of time, assume that it is
         * cancelled.
         */
        const val DEFAULT_CANCEL_SIGNAL_TIMEOUT = 3000L
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
        pw.println(
            "  faceAuthRequestedWhileCancellation: ${faceAuthRequestedWhileCancellation?.reason}"
        )
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

/** "Not" the given flow. The return [Flow] will be true when [this] flow is false. */
private fun Flow<Boolean>.isFalse(): Flow<Boolean> {
    return this.map { !it }
}
