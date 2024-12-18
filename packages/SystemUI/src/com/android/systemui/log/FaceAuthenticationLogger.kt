package com.android.systemui.log

import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.FaceAuthLog
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "DeviceEntryFaceAuthRepositoryLog"

/**
 * Helper class for logging for
 * [com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepository]
 *
 * To enable logcat echoing for an entire buffer:
 * ```
 *   adb shell settings put global systemui/buffer/DeviceEntryFaceAuthRepositoryLog <logLevel>
 *
 * ```
 */
@SysUISingleton
class FaceAuthenticationLogger
@Inject
constructor(
    @FaceAuthLog private val logBuffer: LogBuffer,
) {

    fun ignoredWakeupReason(lastWakeReason: WakeSleepReason) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = "$lastWakeReason" },
            {
                "Ignoring off/aod/dozing -> Lockscreen transition " +
                    "because the last wake up reason is not allow-listed: $str1"
            }
        )
    }
    fun ignoredFaceAuthTrigger(uiEvent: FaceAuthUiEvent?, ignoredReason: String) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = "${uiEvent?.reason}"
                str2 = ignoredReason
            },
            { "Ignoring trigger because $str2, Trigger reason: $str1" }
        )
    }

    fun authenticating(uiEvent: FaceAuthUiEvent) {
        logBuffer.log(TAG, DEBUG, { str1 = uiEvent.reason }, { "Running authenticate for $str1" })
    }

    fun detectionNotSupported(
        faceManager: FaceManager?,
        sensorPropertiesInternal: MutableList<FaceSensorPropertiesInternal>?
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = faceManager == null
                bool2 = sensorPropertiesInternal.isNullOrEmpty()
                bool2 = sensorPropertiesInternal?.firstOrNull()?.supportsFaceDetection ?: false
            },
            {
                "skipping detection request because it is not supported, " +
                    "faceManager isNull: $bool1, " +
                    "sensorPropertiesInternal isNullOrEmpty: $bool2, " +
                    "supportsFaceDetection: $bool3"
            }
        )
    }

    fun skippingDetection(isAuthRunning: Boolean, detectCancellationNotNull: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = isAuthRunning
                bool2 = detectCancellationNotNull
            },
            {
                "Skipping running detection: isAuthRunning: $bool1, " +
                    "detectCancellationNotNull: $bool2"
            }
        )
    }

    fun faceDetectionStarted() {
        logBuffer.log(TAG, DEBUG, "Face detection started.")
    }

    fun faceDetected() {
        logBuffer.log(TAG, DEBUG, "Face detected")
    }

    fun cancelSignalNotReceived(
        isAuthRunning: Boolean,
        isLockedOut: Boolean,
        cancellationInProgress: Boolean,
        faceAuthRequestedWhileCancellation: FaceAuthUiEvent?
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = isAuthRunning
                bool2 = isLockedOut
                bool3 = cancellationInProgress
                str1 = "${faceAuthRequestedWhileCancellation?.reason}"
            },
            {
                "Cancel signal was not received, running timeout handler to reset state. " +
                    "State before reset: " +
                    "isAuthRunning: $bool1, " +
                    "isLockedOut: $bool2, " +
                    "cancellationInProgress: $bool3, " +
                    "faceAuthRequestedWhileCancellation: $str1"
            }
        )
    }

    fun authenticationFailed() {
        logBuffer.log(TAG, DEBUG, "Face authentication failed")
    }

    fun clearFaceRecognized() {
        logBuffer.log(TAG, DEBUG, "Clear face recognized")
    }

    fun authenticationError(
        errorCode: Int,
        errString: CharSequence?,
        lockoutError: Boolean,
        cancellationError: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = errorCode
                str1 = "$errString"
                bool1 = lockoutError
                bool2 = cancellationError
            },
            {
                "Received authentication error: errorCode: $int1, " +
                    "errString: $str1, " +
                    "isLockoutError: $bool1, " +
                    "isCancellationError: $bool2"
            }
        )
    }

    fun faceAuthSuccess(result: FaceManager.AuthenticationResult) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = result.userId
                bool1 = result.isStrongBiometric
            },
            { "Face authenticated successfully: userId: $int1, isStrongBiometric: $bool1" }
        )
    }

    fun canFaceAuthRunChanged(canRun: Boolean) {
        logBuffer.log(TAG, DEBUG, { bool1 = canRun }, { "canFaceAuthRun value changed to $bool1" })
    }

    fun cancellingFaceAuth() {
        logBuffer.log(TAG, DEBUG, "cancelling face auth because a gating condition became false")
    }

    fun interactorStarted() {
        logBuffer.log(TAG, DEBUG, "KeyguardFaceAuthInteractor started")
    }

    fun bouncerVisibilityChanged() {
        logBuffer.log(TAG, DEBUG, "Triggering face auth because primary bouncer is visible")
    }

    fun alternateBouncerVisibilityChanged() {
        logBuffer.log(TAG, DEBUG, "Triggering face auth because alternate bouncer is visible")
    }

    fun lockscreenBecameVisible(wake: WakefulnessModel?) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = "${wake?.lastWakeReason}" },
            { "Triggering face auth because lockscreen became visible due to wake reason: $str1" }
        )
    }

    fun addLockoutResetCallbackDone() {
        logBuffer.log(TAG, DEBUG, {}, { "addlockoutResetCallback done" })
    }

    fun authRequested(uiEvent: FaceAuthUiEvent) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = uiEvent.reason },
            { "Requesting face auth for trigger: $str1" }
        )
    }

    fun hardwareError(errorStatus: ErrorFaceAuthenticationStatus) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = "${errorStatus.msg}"
                int1 = errorStatus.msgId
            },
            { "Received face hardware error: $str1 , code: $int1" }
        )
    }

    fun attemptingRetryAfterHardwareError(retryCount: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            { int1 = retryCount },
            { "Attempting face auth again because of HW error: retry attempt $int1" }
        )
    }

    fun watchdogScheduled() {
        logBuffer.log(TAG, DEBUG, "FaceManager Biometric watchdog scheduled.")
    }

    fun faceLockedOut(@CompileTimeConstant reason: String) {
        logBuffer.log(TAG, DEBUG, "Face auth has been locked out: $reason")
    }

    fun queueingRequest(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = "$uiEvent"
                bool1 = fallbackToDetection
            },
            { "Queueing $str1 request for face auth, fallbackToDetection: $bool1" }
        )
    }

    fun notProcessingRequestYet(
        uiEvent: FaceAuthUiEvent?,
        canRunAuth: Boolean,
        canRunDetect: Boolean,
        cancelInProgress: Boolean
    ) {
        uiEvent?.let {
            logBuffer.log(
                TAG,
                DEBUG,
                {
                    str1 = uiEvent.reason
                    bool1 = canRunAuth
                    bool2 = canRunDetect
                    bool3 = cancelInProgress
                },
                {
                    "Waiting to process request: reason: $str1, " +
                        "canRunAuth: $bool1, " +
                        "canRunDetect: $bool2, " +
                        "cancelInProgress: $bool3"
                }
            )
        }
    }

    fun processingRequest(uiEvent: FaceAuthUiEvent?, fallbackToDetection: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = "${uiEvent?.reason}"
                bool1 = fallbackToDetection
            },
            { "Processing face auth request: $str1, fallbackToDetect: $bool1" }
        )
    }

    fun clearingPendingAuthRequest(
        @CompileTimeConstant loggingContext: String,
        uiEvent: FaceAuthUiEvent?,
        fallbackToDetection: Boolean?
    ) {
        uiEvent?.let {
            logBuffer.log(
                TAG,
                DEBUG,
                {
                    str1 = uiEvent.reason
                    str2 = "$fallbackToDetection"
                    str3 = loggingContext
                },
                {
                    "Clearing pending auth: $str1, " +
                        "fallbackToDetection: $str2, " +
                        "reason: $str3"
                }
            )
        }
    }
}
